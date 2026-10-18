package vn.erg.wallet

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.ergoplatform.appkit.{Address, Parameters}

/**
 * End-to-end run against a local devnet node (devnet/node.sh), through the same code paths as the app:
 * NiPoPoW sync -> funding -> AVL-proven balance -> address discovery -> build, sign and submit -> proven new balance.
 * Run it with devnet/check.sh (it pins the devnet genesis).
 */
object DevnetCheck {
  private val Node = Store.DefaultNodes.head
  private val ApiKey = "hello"

  def main(args: Array[String]): Unit = {
    require(Ergo.Devnet, "run with -Dergo.network=devnet -Dergo.genesis=<id> (see devnet/check.sh)")
    val log: String => Unit = l => println("  " + l)
    val client = new LightClient

    step("NiPoPoW sync against the devnet")
    val p = client.sync(Seq(Node), log)
    check(p.prefix.head.idHex == Nipopow.genesisId, "proof starts at the pinned devnet genesis")

    // a fresh wallet each run, so the expected balances are exact
    val mnemonic = Bip39.generate(15)
    def secret = new Store.Secret(mnemonic.clone(), false)
    val me = Ergo.address(secret, 0)
    val nodeAddress = JsonParser.parseString(nodeWallet("addresses", null)).getAsJsonArray.get(0).getAsString
    println(s"  wallet address $me, node wallet $nodeAddress")

    step("Node wallet pays 10 ERG to the wallet")
    val pay = new JsonArray
    val o = new JsonObject
    o.addProperty("address", me)
    o.addProperty("value", 10000000000L)
    pay.add(o)
    println("  tx " + nodeWallet("payment/send", pay.toString))
    val funded = waitForBalance(client, me, 10000000000L, log)

    step("Address discovery sees the address as used, index 1 as unused")
    check(client.addressUsed(Seq(Node), me, log), "index 0 used")
    check(!client.addressUsed(Seq(Node), Ergo.address(secret, 1), log), "index 1 unused")

    step("Send 1 ERG back: build -> confirm summary -> sign -> submit")
    val reduced = Ergo.prepareTx(Node, funded, me, nodeAddress, 1000000000L, Map.empty, Parameters.MinFee)
    val summary = Ergo.summarize(reduced)
    val effect = Ergo.walletEffect(summary, funded.map(b => org.bouncycastle.util.encoders.Hex.toHexString(b.id) -> b).toMap, Set(me))
    check(effect.erg == 1000000000L + Parameters.MinFee && effect.burned.isEmpty && effect.unknownInputs == 0,
      s"confirmation would show: gives ${Ergo.erg(effect.erg)} ERG, nothing burned, all inputs ours")
    check(summary.outputs.exists(o => o.address == Ergo.FeeAddress && o.value == Parameters.MinFee), "fee output on the devnet fee address")
    val txId = Ergo.submit(client.bestNode, secret.use(s => Ergo.signReduced(reduced, s, Seq(0))))
    println(s"  submitted $txId")
    waitForBalance(client, me, 10000000000L - 1000000000L - Parameters.MinFee, log)

    step("Tokens: mint 1000.00 DevUSD to the wallet, read its info from the node, send 12.34 back")
    val issue = new JsonObject
    issue.add("requests", JsonParser.parseString(
      s"""[{"address":"$me","ergValue":1000000,"amount":100000,"name":"DevUSD","description":"Devnet test token","decimals":2}]"""))
    issue.addProperty("fee", Parameters.MinFee)
    println("  mint tx " + nodeWallet("transaction/send", issue.toString))
    val afterMint = 10000000000L - 1000000000L - Parameters.MinFee + 1000000L
    val withToken = waitForBalance(client, me, afterMint, log)
    val tokenId = withToken.flatMap(Ergo.tokensOf).head._1
    val info = Ergo.tokenInfo("(no explorer on the devnet)", Node, tokenId)
    check(info.resolved && info.name.contains("DevUSD") && info.decimals == 2 && info.emission == 100000, "token info from the node: DevUSD, 2 decimals")
    check(Ergo.tokenAmount(withToken.flatMap(Ergo.tokensOf).map(_._2).sum, info.decimals) == "1000", "wallet holds 1000 DevUSD")
    val raw = Ergo.rawTokenAmount("12.34", info.decimals)
    val tokenTx = Ergo.prepareTx(Node, withToken, me, nodeAddress, Parameters.MinChangeValue, Map(tokenId -> raw), Parameters.MinFee)
    val te = Ergo.walletEffect(Ergo.summarize(tokenTx), withToken.map(b => org.bouncycastle.util.encoders.Hex.toHexString(b.id) -> b).toMap, Set(me))
    check(te.tokens == Map(tokenId -> 1234L) && te.burned.isEmpty, "confirmation would show: -12.34 DevUSD, nothing burned")
    Ergo.submit(client.bestNode, secret.use(s => Ergo.signReduced(tokenTx, s, Seq(0))))
    val left = waitForBalance(client, me, afterMint - Parameters.MinChangeValue - Parameters.MinFee, log)
    check(left.flatMap(Ergo.tokensOf).map(_._2).sum == 100000 - 1234, "987.66 DevUSD left")

    step("History (from the node's index on the devnet)")
    val h = Ergo.history("(no explorer on the devnet)", Node, Seq(me), 50)
    check(h.size == 4 && h.exists(_.tokenDelta == Map(tokenId -> 100000L)) && h.exists(_.tokenDelta == Map(tokenId -> -1234L)),
      "four rows, including +1000 and -12.34 DevUSD")

    println("\nDEVNET CHECK PASSED")
  }

  /** Polls the proven balance (blocks come every second or so) until it equals `nano`. */
  private def waitForBalance(client: LightClient, address: String, nano: Long, log: String => Unit): Seq[org.ergoplatform.ErgoBox] = {
    val deadline = System.currentTimeMillis + 120000
    var last = -1L
    while (System.currentTimeMillis < deadline) {
      client.sync(Seq(Node), _ => ())
      val boxes = client.verifiedUnspent(Seq(Node), address, Address.create(address).toPropositionBytes, log)
      last = boxes.map(_.value).sum
      if (last == nano) {
        check(true, s"proven balance ${Ergo.erg(nano)} ERG in ${boxes.size} box(es)")
        return boxes
      }
      Thread.sleep(2000)
    }
    throw new IllegalStateException(s"balance stayed ${Ergo.erg(last)} ERG, expected ${Ergo.erg(nano)}")
  }

  /** The node's own wallet API (needs the API key, which the app never uses). */
  private def nodeWallet(path: String, jsonBody: String): String = {
    val b = java.net.http.HttpRequest.newBuilder(java.net.URI.create(s"$Node/wallet/$path")).header("api_key", ApiKey)
    val req = if (jsonBody == null) b.GET() else b.header("Content-Type", "application/json").POST(java.net.http.HttpRequest.BodyPublishers.ofString(jsonBody))
    val r = java.net.http.HttpClient.newHttpClient().send(req.build(), java.net.http.HttpResponse.BodyHandlers.ofString())
    if (r.statusCode / 100 != 2) throw new IllegalStateException(s"node wallet $path: HTTP ${r.statusCode} ${r.body}")
    r.body
  }

  private def step(s: String): Unit = println(s"\n== $s")

  private def check(ok: Boolean, what: String): Unit =
    if (ok) println(s"  ✔ $what") else throw new IllegalStateException(s"FAILED: $what")
}
