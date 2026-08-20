#!/usr/bin/env bash
set -Eeuo pipefail

script_directory=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
test_directory=$(mktemp -d)
trap 'rm -rf "${test_directory}"' EXIT
mkdir -p "${test_directory}/bin" "${test_directory}/state"

cat >"${test_directory}/bin/aws" <<'EOF'
#!/usr/bin/env bash
set -Eeuo pipefail

if [[ " $* " == *" cancel-command "* ]]; then
    touch "${FAKE_AWS_STATE_DIR}/cancelled"
    exit 0
fi

count_file=${FAKE_AWS_STATE_DIR}/count
count=0
if [[ -f ${count_file} ]]; then
    count=$(<"${count_file}")
fi
count=$((count + 1))
printf '%s\n' "${count}" >"${count_file}"

case "${FAKE_AWS_SCENARIO}" in
    slow-success)
        if ((count <= 21)); then echo InProgress; else echo Success; fi
        ;;
    missing-then-success)
        if ((count == 1)); then echo InvocationDoesNotExist >&2; exit 254; else echo Success; fi
        ;;
    failed)
        echo Failed
        ;;
    never)
        echo InProgress
        ;;
    *)
        echo "unknown fake scenario" >&2
        exit 2
        ;;
esac
EOF
chmod +x "${test_directory}/bin/aws"

export PATH="${test_directory}/bin:${PATH}"
export FAKE_AWS_STATE_DIR="${test_directory}/state"
export SSM_COMMAND_POLL_INTERVAL_SECONDS=0
export SSM_COMMAND_TIMEOUT_SECONDS=30

export FAKE_AWS_SCENARIO=slow-success
"${script_directory}/wait-for-ssm-command.sh" command-1 instance-1 ap-northeast-2
[[ $(<"${FAKE_AWS_STATE_DIR}/count") -eq 22 ]]

rm -f "${FAKE_AWS_STATE_DIR}/count"
export FAKE_AWS_SCENARIO=missing-then-success
"${script_directory}/wait-for-ssm-command.sh" command-2 instance-1 ap-northeast-2

rm -f "${FAKE_AWS_STATE_DIR}/count"
export FAKE_AWS_SCENARIO=failed
if "${script_directory}/wait-for-ssm-command.sh" command-3 instance-1 ap-northeast-2; then
    echo "failed SSM status must return a non-zero exit code" >&2
    exit 1
fi

rm -f "${FAKE_AWS_STATE_DIR}/count" "${FAKE_AWS_STATE_DIR}/cancelled"
export FAKE_AWS_SCENARIO=never
export SSM_COMMAND_POLL_INTERVAL_SECONDS=1
export SSM_COMMAND_TIMEOUT_SECONDS=1
set +e
"${script_directory}/wait-for-ssm-command.sh" command-4 instance-1 ap-northeast-2
timeout_status=$?
set -e
[[ ${timeout_status} -eq 124 ]]
[[ -f ${FAKE_AWS_STATE_DIR}/cancelled ]]

echo "SSM command polling tests passed"
