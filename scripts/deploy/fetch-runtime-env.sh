#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
    echo "fetch-runtime-env.sh must run as root" >&2
    exit 1
fi

parameter_prefix=${PARAMETER_PREFIX:-/buyeoon}
lookup_region=${AWS_REGION:-ap-northeast-2}
output_path=${1:-/run/buyeoon/runtime.env}

install -d -m 0700 "$(dirname "${output_path}")"
temporary_path=$(mktemp "${output_path}.XXXXXX")
trap 'rm -f "${temporary_path}"' EXIT
chmod 0600 "${temporary_path}"

read_parameter() {
    local parameter_name=$1
    aws ssm get-parameter \
        --name "${parameter_name}" \
        --with-decryption \
        --region "${lookup_region}" \
        --query 'Parameter.Value' \
        --output text
}

read_optional_parameter() {
    local parameter_name=$1
    aws ssm get-parameter \
        --name "${parameter_name}" \
        --with-decryption \
        --region "${lookup_region}" \
        --query 'Parameter.Value' \
        --output text 2>/dev/null
}

write_export() {
    local environment_name=$1
    local value=$2
    printf 'export %s=%q\n' "${environment_name}" "${value}" >>"${temporary_path}"
}

write_parameter() {
    local environment_name=$1
    local parameter_name=$2
    local value
    value=$(read_parameter "${parameter_name}")
    write_export "${environment_name}" "${value}"
}

runtime_region=$(read_parameter "${parameter_prefix}/aws/region")
lookup_region=${runtime_region}
write_export AWS_REGION "${runtime_region}"
write_export AWS_DEFAULT_REGION "${runtime_region}"

write_parameter DB_URL "${parameter_prefix}/db/url"
write_parameter DB_USERNAME "${parameter_prefix}/db/username"
write_parameter DB_PASSWORD "${parameter_prefix}/db/password"
write_parameter JWT_SECRET "${parameter_prefix}/jwt/secret-base64"
write_parameter KAKAO_APP_ID "${parameter_prefix}/social/kakao/app-id"
write_parameter IMAGE_BUCKET "${parameter_prefix}/storage/image-bucket"
write_parameter TOURAPI_SERVICE_KEY "${parameter_prefix}/tourapi/service-key"
write_parameter ADMIN_API_KEY "${parameter_prefix}/admin/api-key"

apple_enabled=false
if configured_apple_enabled=$(read_optional_parameter "${parameter_prefix}/social/apple/enabled"); then
    apple_enabled=${configured_apple_enabled}
fi

case "${apple_enabled}" in
    true)
        write_export APPLE_LOGIN_ENABLED true
        write_parameter APPLE_CLIENT_ID "${parameter_prefix}/social/apple/client-id"
        write_parameter APPLE_TEAM_ID "${parameter_prefix}/social/apple/team-id"
        write_parameter APPLE_KEY_ID "${parameter_prefix}/social/apple/key-id"
        write_parameter APPLE_PRIVATE_KEY_BASE64 "${parameter_prefix}/social/apple/private-key-base64"
        ;;
    false)
        write_export APPLE_LOGIN_ENABLED false
        write_export APPLE_CLIENT_ID ""
        write_export APPLE_TEAM_ID ""
        write_export APPLE_KEY_ID ""
        write_export APPLE_PRIVATE_KEY_BASE64 ""
        ;;
    *)
        echo "${parameter_prefix}/social/apple/enabled must be true or false" >&2
        exit 1
        ;;
esac

mv "${temporary_path}" "${output_path}"
trap - EXIT
chmod 0600 "${output_path}"
chown root:root "${output_path}"

echo "Runtime environment written to ${output_path}"
