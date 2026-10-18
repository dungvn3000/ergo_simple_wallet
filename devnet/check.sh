#!/usr/bin/env bash
# End-to-end wallet check against the running devnet node (start devnet/node.sh first; the node wallet needs
# matured mining rewards, i.e. height > ~730).
set -euo pipefail
cd "$(dirname "$0")/.."
GENESIS=$(curl -fs http://127.0.0.1:9053/blocks/at/1 | tr -d '[]" \n')
# runMain forks (build.sbt): the properties go to the forked JVM
sbt -batch "set Test / run / javaOptions ++= Seq(\"-Dergo.network=devnet\", \"-Dergo.genesis=$GENESIS\")" \
  "Test/runMain vn.erg.wallet.DevnetCheck"
