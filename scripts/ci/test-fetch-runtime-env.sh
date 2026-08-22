#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
    echo "test-fetch-runtime-env.sh must run as root (matches fetch-runtime-env.sh's own requirement)" >&2
    exit 1
fi

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_directory=$(mktemp -d)
trap 'rm -rf "${test_directory}"' EXIT
mkdir -p "${test_directory}/bin"

cat >"${test_directory}/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail

name=""
args=("$@")
for ((i = 0; i < ${#args[@]}; i++)); do
    if [[ "${args[i]}" == "--name" ]]; then
        name=${args[i + 1]}
    fi
done

case "${name}" in
    /buyeoon/aws/region) echo ap-northeast-2 ;;
    /buyeoon/db/url) echo jdbc:postgresql://db:5432/buyeoon ;;
    /buyeoon/db/username) echo buyeoon_app ;;
    /buyeoon/db/password) echo test-password ;;
    /buyeoon/jwt/secret-base64) echo test-secret ;;
    /buyeoon/social/kakao/app-id) echo 1234 ;;
    /buyeoon/storage/image-bucket) echo buyeoon-images ;;
    /buyeoon/tourapi/service-key) echo test-tourapi-key ;;
    /buyeoon/admin/api-key) echo test-admin-key ;;
    /buyeoon/social/apple/enabled) echo false ;;
    /buyeoon/fcm/enabled) echo "${FAKE_FCM_ENABLED:-false}" ;;
    /buyeoon/fcm/service-account-json) echo '{"type":"service_account","project_id":"fake"}' ;;
    *)
        echo "fake aws: unknown parameter ${name}" >&2
        exit 254
        ;;
esac
EOF
chmod +x "${test_directory}/bin/aws"

export PATH="${test_directory}/bin:${PATH}"

output_disabled="${test_directory}/runtime-disabled.env"
FAKE_FCM_ENABLED=false "${script_directory}/../deploy/fetch-runtime-env.sh" "${output_disabled}"
grep -q "^export FCM_ENABLED=false$" "${output_disabled}"
grep -q "^export GOOGLE_APPLICATION_CREDENTIALS=''$" "${output_disabled}"
if grep -q "service-account" "${output_disabled}"; then
    echo "FCM disabled must not reference a service account path" >&2
    exit 1
fi
[[ ! -f /opt/buyeoon/fcm/service-account.json ]]

output_enabled="${test_directory}/runtime-enabled.env"
FAKE_FCM_ENABLED=true "${script_directory}/../deploy/fetch-runtime-env.sh" "${output_enabled}"
grep -q "^export FCM_ENABLED=true$" "${output_enabled}"
grep -q "^export GOOGLE_APPLICATION_CREDENTIALS=/opt/buyeoon/fcm/service-account.json$" "${output_enabled}"
[[ -f /opt/buyeoon/fcm/service-account.json ]]
[[ $(stat -c '%a' /opt/buyeoon/fcm/service-account.json) == "640" ]]
[[ $(stat -c '%U:%G' /opt/buyeoon/fcm/service-account.json) == "root:10001" || $(stat -c '%u:%g' /opt/buyeoon/fcm/service-account.json) == "0:10001" ]]
rm -rf /opt/buyeoon/fcm

echo "fetch-runtime-env FCM branch tests passed"
