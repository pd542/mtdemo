#!/usr/bin/env sh
DIR=$(cd "$(dirname "$0")" && pwd)
if [ ! -f "$DIR/gradle/wrapper/gradle-wrapper.jar" ]; then
  echo "gradle-wrapper.jar is not bundled. Install Gradle and run: gradle wrapper --gradle-version 8.7" >&2
  exit 1
fi
exec java -jar "$DIR/gradle/wrapper/gradle-wrapper.jar" "$@"
