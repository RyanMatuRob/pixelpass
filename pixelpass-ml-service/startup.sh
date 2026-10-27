#!/usr/bin/env bash
# Azure App Service entrypoint for the PixelPass ML service.
# The deployment package ships its dependencies in ./python-packages, resolved on the GitHub runner,
# because the platform build left wwwroot holding only output.tar.zst (issue #111).
# App Service injects PORT (8000 by default) for Linux code deployments, and uvicorn must bind
# 0.0.0.0 so the platform reverse proxy can reach it. .gitattributes forces LF on this file.
set -euo pipefail

APP_DIR="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)"

# Keep the bundled packages importable even if the PYTHONPATH app setting goes missing.
export PYTHONPATH="${APP_DIR}/python-packages${PYTHONPATH:+:${PYTHONPATH}}"
export PORT="${PORT:-8000}"

cd "${APP_DIR}"
exec python -m uvicorn main:app --host 0.0.0.0 --port "${PORT}"
