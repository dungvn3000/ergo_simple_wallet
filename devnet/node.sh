#!/usr/bin/env bash
# Starts a local Ergo devnet node (REST API http://127.0.0.1:9053, API key "hello"), creates its mining wallet on the
# first run and unlocks it, so the node mines blocks and collects the rewards (spendable after 720 blocks).
# Data: devnet/data (delete it to start a fresh chain). Stop: Ctrl+C.
set -euo pipefail
cd "$(dirname "$0")"

VERSION=6.0.8
JAR=ergo.jar
if [ ! -f "$JAR" ]; then
  curl -fSL -o "$JAR.part" "https://github.com/ergoplatform/ergo/releases/download/v$VERSION/ergo-$VERSION.jar"
  want=$(curl -fsSL "https://github.com/ergoplatform/ergo/releases/download/v$VERSION/ergo-$VERSION.jar.sha256" | cut -d' ' -f1)
  [ "$(shasum -a 256 "$JAR.part" | cut -d' ' -f1)" = "$want" ] || { echo "checksum mismatch" >&2; exit 1; }
  mv "$JAR.part" "$JAR"
fi

export ERGO_DEVNET_DATA="$PWD/data"
API=http://127.0.0.1:9053
KEY="api_key: hello"
PASS=devnet

java -Xmx2g -jar "$JAR" --devnet -c devnet.conf &
NODE=$!
trap 'kill $NODE 2>/dev/null' EXIT INT TERM

until curl -fs "$API/info" >/dev/null 2>&1; do sleep 1; done
if curl -fs "$API/wallet/status" -H "$KEY" | grep -q '"isInitialized" *: *false'; then
  curl -fs -X POST "$API/wallet/init" -H "$KEY" -H "Content-Type: application/json" -d "{\"pass\":\"$PASS\"}" >/dev/null
fi
# init already unlocks: only an existing, locked wallet needs it
if ! curl -fs "$API/wallet/status" -H "$KEY" | grep -q '"isUnlocked" *: *true'; then
  curl -fs -X POST "$API/wallet/unlock" -H "$KEY" -H "Content-Type: application/json" -d "{\"pass\":\"$PASS\"}" >/dev/null
fi
echo "Devnet node running at $API (API key: hello), mining wallet unlocked."
wait $NODE
