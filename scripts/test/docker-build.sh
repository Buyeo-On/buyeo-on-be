#!/usr/bin/env bash
set -euo pipefail

export COMPOSE_PROJECT_NAME="${COMPOSE_PROJECT_NAME:-buyeoon-test-$$}"
started=false

cleanup() {
	if [ "${started}" = true ]; then
		docker compose down --remove-orphans
	fi
}

trap cleanup EXIT

echo "==> Docker Compose build"
docker compose build

echo "==> Docker Compose up (detached)"
started=true
docker compose up -d

http_port="${HTTP_PORT:-}"
if [ -z "${http_port}" ]; then
	published_address="$(docker compose port nginx 80)"
	http_port="${published_address##*:}"
fi

echo "==> Health check"
for i in $(seq 1 30); do
	if curl -sf "http://localhost:${http_port}/actuator/health" >/dev/null 2>&1; then
		echo "==> Health check: PASS"
		exit 0
	fi
	echo "Waiting... ($i/30)"
	sleep 2
done

echo "==> Health check: FAIL (timeout)"
docker compose logs app
exit 1
