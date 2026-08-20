#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
    echo "bootstrap-release.sh must run as root" >&2
    exit 1
fi

image_uri=${1:?image URI is required}
release_sha=${2:?release SHA is required}
deployment_mode=${3:-full}
buyeoon_home=${BUYEOON_HOME:-/opt/buyeoon}
aws_region=${AWS_REGION:-ap-northeast-2}

if [[ ! ${release_sha} =~ ^[0-9a-f]{40}$ ]]; then
    echo "release SHA must be a 40-character lowercase Git SHA" >&2
    exit 1
fi
if [[ ${image_uri} != *:"${release_sha}" ]]; then
    echo "image tag must match the release SHA" >&2
    exit 1
fi
case "${deployment_mode}" in
    app-only | full) ;;
    *)
        echo "deployment mode must be app-only or full" >&2
        exit 1
        ;;
esac

registry=${image_uri%%/*}
aws ecr get-login-password --region "${aws_region}" | \
    docker login --username AWS --password-stdin "${registry}"
docker pull "${image_uri}"

release_directory=${buyeoon_home}/releases/${release_sha}
install -d -m 0750 "${release_directory}"

container_id=$(docker create "${image_uri}")
trap 'docker rm -f "${container_id}" >/dev/null 2>&1 || true' EXIT
docker cp "${container_id}:/opt/buyeoon/deploy-bundle/." "${release_directory}/"
docker rm "${container_id}" >/dev/null
trap - EXIT

find "${release_directory}/scripts/deploy" -type f -name '*.sh' -exec chmod 0750 {} +
touch "${release_directory}/.complete"

exec "${release_directory}/scripts/deploy/deploy.sh" "${image_uri}" "${release_sha}" "${deployment_mode}"
