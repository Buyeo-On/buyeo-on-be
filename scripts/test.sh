#!/usr/bin/env bash
set -euo pipefail

if [ $# -ge 2 ] && [ "$1" = "--tests" ]; then
	echo "==> 관련 테스트: $2"
	./gradlew test --tests "$2"
fi

echo "==> 전체 테스트"
./gradlew test
