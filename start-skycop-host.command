#!/bin/zsh
set -e
setopt pipefail
cd "${0:A:h}"
export PATH="/opt/homebrew/bin:/usr/local/bin:$PATH"
export PORT="${PORT:-8787}"
if [[ ! "$PORT" =~ '^[0-9]+$' ]] || (( PORT < 1 || PORT > 65535 )); then
  echo 'PORT must be a number between 1 and 65535.'
  exit 1
fi
if ! command -v node >/dev/null || ! command -v npm >/dev/null; then
  echo 'Install Node.js and npm before starting the host.'
  exit 1
fi
if ! command -v cloudflared >/dev/null; then
  echo 'Install cloudflared first: brew install cloudflared'
  exit 1
fi
if lsof -nP "-iTCP:$PORT" -sTCP:LISTEN >/dev/null 2>&1; then
  echo "Port $PORT is already in use. Stop the existing host before starting another."
  exit 1
fi
if [[ ! -d signaling-server/node_modules/ws ]]; then
  npm ci --prefix signaling-server
fi
export ALLOWED_ORIGINS="${ALLOWED_ORIGINS:-http://localhost:5173,http://127.0.0.1:5173,https://akramhossainapu.github.io}"
echo 'Starting the SkyCop signaling host and a temporary public tunnel.'
echo 'For testing only: share invitations privately.'
node signaling-server/index.mjs &
SERVER_PID=$!
trap 'kill "$SERVER_PID" 2>/dev/null || true' EXIT
trap 'exit 130' INT
trap 'exit 143' TERM
opened=0
cloudflared tunnel --no-autoupdate --url "http://127.0.0.1:$PORT" 2>&1 | while IFS= read -r line; do
  print -r -- "$line"
  if (( ! opened )) && [[ "$line" =~ 'https://[a-z0-9-]+\.trycloudflare\.com' ]]; then
    opened=1
    signaling_url="wss://${MATCH#https://}"
    site_url="${SKYCOP_SITE_URL:-https://akramhossainapu.github.io/SkyCop---Autonomous-Drone-Based-Traffic-Monitoring/}"
    launch_url=$(node -e 'const url = new URL(process.argv[1]); url.searchParams.set("signaling", process.argv[2]); process.stdout.write(url.toString());' "$site_url" "$signaling_url")
    site_origin=$(node -e 'process.stdout.write(new URL(process.argv[1]).origin)' "$site_url")
    echo 'Waiting for the new tunnel to accept signaling connections...'
    if node signaling-server/wait-for-tunnel.mjs "$signaling_url" "$site_origin"; then
      echo "Current website link (old links stop working after a restart): $launch_url"
      open "$launch_url" || echo 'Could not open the browser. Use the current website link above.'
    else
      echo 'The website was not opened because the tunnel is not ready.'
    fi
  fi
done