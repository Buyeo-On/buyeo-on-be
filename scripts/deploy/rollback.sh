#!/usr/bin/env bash
set -Eeuo pipefail

if [[ ${EUID} -ne 0 ]]; then
    echo "rollback.sh must run as root" >&2
    exit 1
fi

buyeoon_home=${BUYEOON_HOME:-/opt/buyeoon}
state_directory=${buyeoon_home}/state

for state_file in previous-sha previous-image previous-mode; do
    if [[ ! -s ${state_directory}/${state_file} ]]; then
        echo "Rollback state ${state_file} is missing" >&2
        exit 1
    fi
done

previous_sha=$(<"${state_directory}/previous-sha")
previous_image=$(<"${state_directory}/previous-image")
previous_mode=$(<"${state_directory}/previous-mode")
previous_deploy=${buyeoon_home}/releases/${previous_sha}/scripts/deploy/deploy.sh

if [[ ! -x ${previous_deploy} ]]; then
    echo "Rollback release ${previous_sha} is not available" >&2
    exit 1
fi

exec "${previous_deploy}" "${previous_image}" "${previous_sha}" "${previous_mode}"
