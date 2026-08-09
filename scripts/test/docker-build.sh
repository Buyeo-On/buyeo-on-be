#!/usr/bin/env bash
set -euo pipefail

echo "==> Docker Compose build"
docker compose build

echo "==> Docker Compose up (detached)"
docker compose up -d

echo "==> Health check"
for i in $(seq 1 30); do
	if curl -sf http://localhost:8080/actuator/health >/dev/null 2>&1; then
		echo "==> Health check: PASS"
		docker compose down
		exit 0
	fi
	echo "Waiting... ($i/30)"
	sleep 2
done

echo "==> Health check: FAIL (timeout)"
docker compose logs app
docker compose down
exit 1
