#!/usr/bin/env bash
# Opens Ergo Simple Wallet on the local devnet (start devnet/node.sh first). Wallet data: ~/.ergo-simple-wallet-devnet
set -euo pipefail
cd "$(dirname "$0")/.."
JAR=target/scala-2.12/ergo-simple-wallet.jar
[ -f "$JAR" ] || sbt -batch assembly
# the devnet genesis is random per chain: pin the one of this node
GENESIS=$(curl -fs http://127.0.0.1:9053/blocks/at/1 | tr -d '[]" \n')
exec java -Dergo.network=devnet -Dergo.genesis="$GENESIS" -jar "$JAR"
