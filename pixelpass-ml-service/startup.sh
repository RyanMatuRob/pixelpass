#!/usr/bin/env bash
# Azure App Service entrypoint for the PixelPass ML service.
# App Service injects PORT (8000 by default) for Linux code deployments, and uvicorn must bind
# 0.0.0.0 so the platform reverse proxy can reach it. .gitattributes forces LF on this file.
set -euo pipefail

export PORT="${PORT:-8000}"

exec python -m uvicorn main:app --host 0.0.0.0 --port "${PORT}"
