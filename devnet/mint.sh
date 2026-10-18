#!/usr/bin/env bash
# Mints a new test token on the devnet straight to an address: devnet/mint.sh <address> <name> <amount> [decimals, default 2]
# e.g. devnet/mint.sh 3W... DevUSD 1000 2   ->  1000.00 DevUSD
set -euo pipefail
ADDRESS=${1:?usage: mint.sh <address> <name> <amount> [decimals]}
NAME=${2:?name}
DECIMALS=${4:-2}
RAW=$(python3 -c "from decimal import Decimal; print(int(Decimal('${3:?amount}') * 10**$DECIMALS))")
curl -fsS -X POST http://127.0.0.1:9053/wallet/transaction/send -H "api_key: hello" -H "Content-Type: application/json" \
  -d "{\"requests\":[{\"address\":\"$ADDRESS\",\"ergValue\":1000000,\"amount\":$RAW,\"name\":\"$NAME\",\"description\":\"Devnet test token\",\"decimals\":$DECIMALS}],\"fee\":1000000}"
echo
