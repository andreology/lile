#!/bin/sh
set -euo pipefail

APP_JAR="${APP_JAR:-/app/app.jar}"
JAVA_OPTS="${JAVA_OPTS:-}"

# Ensure tessdata env defaults are set for consistent OCR.
export TESSDATA_PREFIX="${TESSDATA_PREFIX:-/opt/tessdata}"
export FORM_PROCESSING_TESS_DATA_PATH="${FORM_PROCESSING_TESS_DATA_PATH:-${TESSDATA_PREFIX}}"

exec java ${JAVA_OPTS} -jar "${APP_JAR}"
