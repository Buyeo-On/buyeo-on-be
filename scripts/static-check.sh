#!/usr/bin/env bash
set -euo pipefail

echo "==> compileJava"
./gradlew compileJava

echo "==> spotlessCheck"
./gradlew spotlessCheck

echo "==> checkstyle"
./gradlew checkstyleMain checkstyleTest

echo "==> spotbugs"
./gradlew spotbugsMain spotbugsTest

echo "==> check"
./gradlew check
