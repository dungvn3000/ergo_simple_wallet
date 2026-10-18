#!/usr/bin/env bash
# Regenerates the README screenshots (docs/screenshots/*.png) from the real app on the running devnet node.
# Uses a throw-away home folder: your devnet and mainnet wallets are not touched.
set -euo pipefail
cd "$(dirname "$0")/.."
GENESIS=$(curl -fs http://127.0.0.1:9053/blocks/at/1 | tr -d '[]" \n')
HOME_DIR=$(mktemp -d)
trap 'rm -rf "$HOME_DIR"' EXIT
sbt -batch "set Test / run / javaOptions ++= Seq(\"-Dergo.network=devnet\", \"-Dergo.genesis=$GENESIS\", \"-Duser.home=$HOME_DIR\")" \
  "Test/runMain vn.erg.wallet.Screenshots docs/screenshots"
