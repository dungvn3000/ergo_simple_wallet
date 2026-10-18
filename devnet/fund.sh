#!/usr/bin/env bash
# Sends test ERG from the devnet node's mining wallet: devnet/fund.sh <address> [ERG, default 100]
set -euo pipefail
ADDRESS=${1:?usage: fund.sh <address> [ERG]}
NANO=$(python3 -c "print(int(round(float('${2:-100}') * 1e9)))")
curl -fsS -X POST http://127.0.0.1:9053/wallet/payment/send -H "api_key: hello" -H "Content-Type: application/json" \
  -d "[{\"address\":\"$ADDRESS\",\"value\":$NANO}]"
echo
