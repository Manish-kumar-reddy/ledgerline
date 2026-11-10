#!/bin/sh
# Render injects PORT; Spring Boot binds the SERVER_PORT environment variable to server.port (relaxed binding).
# An explicitly configured SERVER_PORT always wins. Default 8080.
set -eu
if [ -z "${SERVER_PORT:-}" ] && [ -n "${PORT:-}" ]; then
  SERVER_PORT="$PORT"
  export SERVER_PORT
fi
exec java -jar "${APP_JAR:-/app/app.jar}" "$@"
