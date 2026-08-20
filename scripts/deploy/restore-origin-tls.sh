#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
    echo "restore-origin-tls.sh must run as root" >&2
    exit 1
fi

parameter_prefix=${PARAMETER_PREFIX:-/buyeoon}
aws_region=${AWS_REGION:-ap-northeast-2}
tls_directory=${TLS_DIR:-/opt/buyeoon/tls}
certificate_parameter=${ORIGIN_CERTIFICATE_PARAMETER:-${parameter_prefix}/tls/origin-certificate}
private_key_parameter=${ORIGIN_PRIVATE_KEY_PARAMETER:-${parameter_prefix}/tls/origin-private-key}

install -d -m 0700 -o root -g root "${tls_directory}"
certificate_tmp=$(mktemp "${tls_directory}/.origin-cert.XXXXXX")
private_key_tmp=$(mktemp "${tls_directory}/.origin-key.XXXXXX")
trap 'rm -f "${certificate_tmp}" "${private_key_tmp}"' EXIT

aws ssm get-parameter \
    --name "${certificate_parameter}" \
    --with-decryption \
    --region "${aws_region}" \
    --query 'Parameter.Value' \
    --output text >"${certificate_tmp}"

aws ssm get-parameter \
    --name "${private_key_parameter}" \
    --with-decryption \
    --region "${aws_region}" \
    --query 'Parameter.Value' \
    --output text >"${private_key_tmp}"

if ! grep -q -- 'BEGIN CERTIFICATE' "${certificate_tmp}"; then
    echo "Origin certificate parameter is not PEM encoded" >&2
    exit 1
fi
if ! grep -Eq -- 'BEGIN ([A-Z]+ )?PRIVATE KEY' "${private_key_tmp}"; then
    echo "Origin private key parameter is not PEM encoded" >&2
    exit 1
fi

chmod 0644 "${certificate_tmp}"
chmod 0600 "${private_key_tmp}"
mv "${certificate_tmp}" "${tls_directory}/origin.pem"
mv "${private_key_tmp}" "${tls_directory}/origin.key"
trap - EXIT

echo "Cloudflare origin TLS material restored to ${tls_directory}"
