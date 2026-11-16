#!/bin/zsh
set -e
cd "${0:A:h}"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
if ! command -v node >/dev/null || ! command -v npm >/dev/null; then
  echo 'Install Node.js and npm before starting the host.'
  exit 1
fi
if ! command -v cloudflared >/dev/null; then
  echo 'Install cloudflared first: brew install cloudflared'
  exit 1
fi
if lsof -nP -iTCP:8787 -sTCP:LISTEN >/dev/null 2>&1; then
  echo 'Port 8787 is already in use. Stop the existing host before starting another.'
  exit 1
fi
if [[ ! -d signaling-server/node_modules/ws ]]; then
  npm ci --prefix signaling-server
fi
export ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-http://localhost:5173,http://127.0.0.1:5173,https://akramhossainapu.github.io}"
echo 'Starting the SkyCop signaling host and a temporary public tunnel.'
echo 'For testing only: share the tunnel address and meeting code privately.'
node signaling-server/index.mjs &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
cloudflared tunnel --no-autoupdate --url http://127.0.0.1:8787