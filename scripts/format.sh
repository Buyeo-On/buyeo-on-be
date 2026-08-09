#!/usr/bin/env bash
set -euo pipefail

echo "==> spotlessApply"
./gradlew spotlessApply

echo "==> 변경 사항 확인"
git diff
