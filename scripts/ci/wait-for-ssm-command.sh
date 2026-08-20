#!/usr/bin/env bash
set -Eeuo pipefail

command_id=${1:?command ID is required}
instance_id=${2:?instance ID is required}
aws_region=${3:?AWS region is required}
timeout_seconds=${SSM_COMMAND_TIMEOUT_SECONDS:-900}
poll_interval_seconds=${SSM_COMMAND_POLL_INTERVAL_SECONDS:-5}

if [[ ! ${timeout_seconds} =~ ^[1-9][0-9]*$ ]]; then
    echo "SSM_COMMAND_TIMEOUT_SECONDS must be a positive integer" >&2
    exit 2
fi
if [[ ! ${poll_interval_seconds} =~ ^[0-9]+$ ]]; then
    echo "SSM_COMMAND_POLL_INTERVAL_SECONDS must be a non-negative integer" >&2
    exit 2
fi

deadline=$((SECONDS + timeout_seconds))
while ((SECONDS < deadline)); do
    if status=$(aws ssm get-command-invocation \
        --command-id "${command_id}" \
        --instance-id "${instance_id}" \
        --region "${aws_region}" \
        --query Status \
        --output text 2>&1); then
        case "${status}" in
            Success)
                exit 0
                ;;
            Pending | InProgress | Delayed)
                sleep "${poll_interval_seconds}"
                ;;
            Cancelled | Cancelling | Failed | TimedOut)
                echo "SSM command ${command_id} finished with status ${status}" >&2
                exit 1
                ;;
            *)
                echo "SSM command ${command_id} returned unknown status ${status}" >&2
                exit 1
                ;;
        esac
    elif [[ ${status} == *InvocationDoesNotExist* ]]; then
        sleep "${poll_interval_seconds}"
    else
        printf '%s\n' "${status}" >&2
        exit 1
    fi
done

aws ssm cancel-command \
    --command-id "${command_id}" \
    --instance-ids "${instance_id}" \
    --region "${aws_region}" >/dev/null 2>&1 || true
echo "SSM command ${command_id} did not finish within ${timeout_seconds} seconds; cancellation requested" >&2
exit 124
