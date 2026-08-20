#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
    echo "deploy.sh must run as root" >&2
    exit 1
fi

image_uri=${1:?image URI is required}
release_sha=${2:?release SHA is required}
deployment_mode=${3:-full}
buyeoon_home=${BUYEOON_HOME:-/opt/buyeoon}
release_directory=${buyeoon_home}/releases/${release_sha}
compose_file=${release_directory}/compose.prod.yaml
runtime_environment=${buyeoon_home}/runtime/runtime.env
state_directory=${buyeoon_home}/state
tls_directory=${TLS_DIR:-${buyeoon_home}/tls}

if [[ ! -f ${compose_file} ]]; then
    echo "Production Compose file is missing for ${release_sha}" >&2
    exit 1
fi
case "${deployment_mode}" in
    app-only | full) ;;
    *)
        echo "deployment mode must be app-only or full" >&2
        exit 1
        ;;
esac

install -d -m 0700 "$(dirname "${runtime_environment}")" "${state_directory}"
install -d -m 0750 -o 10001 -g 10001 /var/log/buyeoon/app
install -d -m 0750 /var/log/buyeoon/nginx

"${release_directory}/scripts/deploy/fetch-runtime-env.sh" "${runtime_environment}"
source "${runtime_environment}"

export APP_IMAGE=${image_uri}
export RELEASE_DIR=${release_directory}
export TLS_DIR=${tls_directory}

if [[ ${deployment_mode} == full ]]; then
    "${release_directory}/scripts/deploy/restore-origin-tls.sh"
fi

current_sha=""
current_image=""
current_mode=""
if [[ -f ${state_directory}/current-sha ]]; then
    current_sha=$(<"${state_directory}/current-sha")
    current_image=$(<"${state_directory}/current-image")
    current_mode=$(<"${state_directory}/current-mode")
fi

wait_for_application() {
    local compose_path=$1
    local container_id
    local health_status
    container_id=$(docker compose -f "${compose_path}" ps -q app)
    if [[ -z ${container_id} ]]; then
        return 1
    fi
    for _ in $(seq 1 60); do
        health_status=$(docker inspect --format '{{.State.Health.Status}}' "${container_id}" 2>/dev/null || true)
        if [[ ${health_status} == healthy ]] && curl --fail --silent --show-error \
            http://127.0.0.1:18080/actuator/health >/dev/null; then
            return 0
        fi
        if [[ ${health_status} == unhealthy ]]; then
            return 1
        fi
        sleep 2
    done
    return 1
}

wait_for_nginx() {
    for _ in $(seq 1 30); do
        if curl --fail --silent --show-error http://127.0.0.1:8080/actuator/health >/dev/null; then
            return 0
        fi
        sleep 1
    done
    return 1
}

start_release() {
    local target_sha=$1
    local target_image=$2
    local target_mode=$3
    local target_directory=${buyeoon_home}/releases/${target_sha}
    local target_compose=${target_directory}/compose.prod.yaml

    export APP_IMAGE=${target_image}
    export RELEASE_DIR=${target_directory}
    if [[ ${target_mode} == full ]]; then
        docker compose -f "${target_compose}" up -d --remove-orphans
    else
        docker compose -f "${target_compose}" up -d app
    fi
    wait_for_application "${target_compose}"
    if [[ ${target_mode} == full ]]; then
        wait_for_nginx
    fi
}

if ! start_release "${release_sha}" "${image_uri}" "${deployment_mode}"; then
    echo "Release ${release_sha} failed its internal health check" >&2
    if [[ -n ${current_sha} && -f ${buyeoon_home}/releases/${current_sha}/compose.prod.yaml ]]; then
        echo "Rolling back to ${current_sha}" >&2
        start_release "${current_sha}" "${current_image}" "${current_mode}" || {
            echo "Automatic rollback to ${current_sha} also failed" >&2
            exit 1
        }
    fi
    exit 1
fi

if [[ -n ${current_sha} && ${current_sha} != "${release_sha}" ]]; then
    printf '%s\n' "${current_sha}" >"${state_directory}/previous-sha"
    printf '%s\n' "${current_image}" >"${state_directory}/previous-image"
    printf '%s\n' "${current_mode}" >"${state_directory}/previous-mode"
fi
printf '%s\n' "${release_sha}" >"${state_directory}/current-sha"
printf '%s\n' "${image_uri}" >"${state_directory}/current-image"
printf '%s\n' "${deployment_mode}" >"${state_directory}/current-mode"
ln -sfn "${release_directory}" "${buyeoon_home}/current"

echo "Release ${release_sha} is healthy in ${deployment_mode} mode"
