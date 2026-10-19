package vn.erg.wallet

import java.io.ByteArrayOutputStream
import java.security.SecureRandom

import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoBox
import org.ergoplatform.appkit.{Address, Parameters}
import org.ergoplatform.sdk.{ErgoToken, SecretString}
import org.scalatest.funsuite.AnyFunSuite
import scorex.crypto.authds.avltree.batch.{BatchAVLProver, Insert, Lookup}
import scorex.crypto.authds.ADValue
import scorex.crypto.hash.{Blake2b256, Digest32}

import scala.collection.mutable.ArrayBuffer

class ErgoSpec extends AnyFunSuite {
  private val Mnemonic = "slow silly start wash bundle suffer bulb ancient height spin express remind today effort helmet"
  private val Token = "03faf2cb329f2e90d6d23b58d91bbb6c046aa143261cc21f52fbe2824bfcbf04"

  private def secret(deprecated: Boolean = false) = new Store.Secret(Mnemonic.toCharArray, deprecated)

  test("our Array[Char] key derivation matches appkit's (String-based) EIP-3 derivation") {
    for (deprecated <- Seq(false, true)) {
      val xpub = Ergo.xpub(secret(deprecated))
      for (i <- 0 until 3) {
        val appkit = Address.createEip3Address(i, Ergo.Net, SecretString.create(Mnemonic), SecretString.empty(), deprecated).toString
        assert(Ergo.address(secret(deprecated), i) == appkit)
        assert(Ergo.derive(xpub, i) == appkit)
      }
    }
  }

  test("BIP39 validation, generation and seed") {
    assert(new String(Bip39.normalize(("  " + Mnemonic.toUpperCase.replace(" ", "\n ") + " ").toCharArray)) == Mnemonic)
    assertThrows[IllegalArgumentException](Bip39.normalize(Mnemonic.replace("slow", "fast").toCharArray)) // bad checksum
    assertThrows[IllegalArgumentException](Bip39.normalize(Mnemonic.replace("slow", "xyzzy").toCharArray)) // unknown word
    assertThrows[IllegalArgumentException](Bip39.normalize("abandon abandon".toCharArray)) // too short
    for (n <- Seq(12, 15, 24)) {
      val g = Bip39.generate(n)
      assert(Bip39.wordRanges(g).size == n)
      assert(Bip39.normalize(g).sameElements(g))
    }
    for (n <- Seq(0, 11, 13, 27)) assertThrows[IllegalArgumentException](Bip39.generate(n))
    // official BIP39 vector (entropy 0x00..00, 12 words, empty passphrase)
    val v = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".toCharArray
    assert(Hex.toHexString(Bip39.seed(Bip39.normalize(v))) ==
      "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4")
  }

  test("gap-limit address discovery") {
    val used = Set(0, 3, 22, 50)
    val probed = ArrayBuffer.empty[Int]
    val found = Ergo.discoverUsed(i => { probed += i; used(i) }, 20)
    assert(found == Vector(0, 3, 22), "50 is beyond a 20-address gap after 22")
    assert(probed.size == 43, "stops right after 20 unused in a row (23..42)")
    assert(Ergo.discoverUsed(_ => false, 20).isEmpty)
  }

  test("payment requests") {
    val addr = Ergo.address(secret(), 0)
    val p = Ergo.parsePaymentRequest(Ergo.paymentRequestUrl(addr, 1500000000L, "coffee & cake")).get
    assert(p.address == addr)
    assert(p.nanoErg == 1500000000L)
    assert(p.description == "coffee & cake")
    val u = Ergo.parsePaymentRequest(s"ergo:$addr?amount=0.1&token-$Token=2.5").get
    assert(u.nanoErg == 100000000L)
    assert(u.tokens(Token) == "2.5")
    assert(Ergo.parsePaymentRequest(addr).map(_.address).contains(addr))
    assert(Ergo.parsePaymentRequest("hello").isEmpty)
    val stray = Ergo.parsePaymentRequest(s"ergo:$addr?amount=1&description=50%off").get // stray % only drops that parameter
    assert(stray.address == addr && stray.nanoErg == 1000000000L && stray.description == "")
  }

  test("wallet effect: what leaves the wallet, tokens burned, inputs that are not ours") {
    val me = Ergo.address(secret(), 0)
    val nft = box(me, 1000000L, new ErgoToken(Token, 1))
    val coins = box(me, 5000000000L)
    def id(b: ErgoBox) = Hex.toHexString(b.id)
    val mine = Map(id(nft) -> nft, id(coins) -> coins)
    // a dApp tx: spends both boxes and one of its own, returns 4 ERG to us, the NFT goes nowhere
    val s = Ergo.TxSummary(Seq(id(nft), id(coins), "ff" * 32),
      Seq(Ergo.Output(me, 4000000000L, Map.empty), Ergo.Output("dapp", 1000000L, Map.empty), Ergo.Output(Ergo.FeeAddress, 1000000L, Map.empty)))
    val e = Ergo.walletEffect(s, mine, Set(me))
    assert(e.erg == 1001000000L, "5.001 ERG in, 4 ERG back")
    assert(e.tokens == Map(Token -> 1L) && e.burned == Map(Token -> 1L))
    assert(e.unknownInputs == 1)
    // the same NFT sent to someone: given away, not burned
    val sent = Ergo.walletEffect(s.copy(outputs = s.outputs :+ Ergo.Output("friend", 1000000L, Map(Token -> 1L))), mine, Set(me))
    assert(sent.tokens == Map(Token -> 1L) && sent.burned.isEmpty)
  }

  test("Ergo logo paths parse to the expected shapes") {
    val o = ErgoLogo.Octagon.getBounds2D
    assert(o.getMinX > -0.01 && o.getMaxX < 1080.01 && o.getWidth > 1079, s"octagon spans the 1080 view box: $o")
    val s = ErgoLogo.Sigma.getBounds2D
    assert(math.abs(s.getMinX - 365.28) < 1 && math.abs(s.getMaxX - 714.73) < 1, s"sigma x range: $s")
    assert(math.abs(s.getMinY - 304.25) < 1 && math.abs(s.getMaxY - 775.64) < 1, s"sigma y range: $s")
    assert(!ErgoLogo.Octagon.contains(540, 540), "the octagon is a ring: its centre is a hole")
    assert(ErgoLogo.Octagon.contains(540, 20), "the ring itself is filled")
  }

  test("a failed token lookup is not cached (its decimals would be wrong forever)") {
    val unreachable = "http://127.0.0.1:1"
    val t = Ergo.tokenInfo(unreachable, unreachable, Token)
    assert(!t.resolved)
    assert(!Ergo.cachedTokenInfo(Token).resolved, "failure must not be cached")
    // shortcut: the success half needs the public explorer; upgrade to a local stub if CI is offline
    val ok = Ergo.tokenInfo("https://api.ergoplatform.com", unreachable, Token)
    assert(ok.resolved && ok.decimals == 2 && ok.name.contains("SigUSD"))
    assert(Ergo.cachedTokenInfo(Token).decimals == 2, "success is cached")
  }

  test("a stale verified tip triggers exactly one re-sync, other errors do not") {
    var calls = 0
    var resyncs = 0
    val r = LightClient.withResync {
      calls += 1
      if (calls == 1) throw new LightClient.StaleTip("node moved on") else "boxes"
    } { resyncs += 1 }
    assert(r == "boxes" && calls == 2 && resyncs == 1)

    resyncs = 0
    intercept[IllegalStateException](LightClient.withResync[String](throw new IllegalStateException("HTTP 500"))(resyncs += 1))
    assert(resyncs == 0)
    intercept[LightClient.StaleTip](LightClient.withResync[String](throw new LightClient.StaleTip("still stale"))(()))
  }

  test("box ids are read page by page until a short page") {
    val all = (0 until 1123).toVector
    val offsets = ArrayBuffer.empty[Int]
    val got = LightClient.allPages(500) { off => offsets += off; all.slice(off, off + 500) }
    assert(got == all, "more than one page of boxes must all be read")
    assert(offsets == Seq(0, 500, 1000))
    assert(LightClient.allPages(500)(off => (0 until 1000).slice(off, off + 500)).size == 1000) // exact multiple: one extra empty page
    var pages = 0
    LightClient.allPages(500) { _ => pages += 1; Vector.fill(500)(0) } // a node ignoring offset must not loop forever
    assert(pages == LightClient.MaxPages)
  }

  test("plain http only for this machine and private networks") {
    for (local <- Seq("localhost:9000/pay", "127.0.0.1/x", "10.1.2.3:8080/x", "192.168.1.5/x", "172.16.0.1/x", "172.31.255.1/x"))
      assert(Ergo.isLocal(local), local)
    for (public <- Seq("1.2.3.4/x", "8.8.8.8:443/x", "172.32.0.1/x", "172.15.0.1/x", "192.169.1.1/x", "dapp.example.com/x", "localhost.evil.com/x", "10.example.com/x"))
      assert(!Ergo.isLocal(public), public)
    assert(Ergo.isLocal("//192.168.0.2/auth"), "ErgoAuth passes a // prefix")
  }

  test("QR round trip") {
    val link = Ergo.paymentRequestUrl(Ergo.address(secret(), 0), 1000000000L, "test")
    assert(Qr.decode(Qr.encode(link, 400)) == link)
  }

  test("HTTP bodies above the limit are refused instead of filling memory") {
    def stream(n: Int) = new java.io.ByteArrayInputStream(new Array[Byte](n))
    assert(Http.readLimited(stream(1000), 1000).length == 1000)
    assertThrows[IllegalStateException](Http.readLimited(stream(1001), 1000))
  }

  test("data too large for a QR code is reported as such") {
    val e = intercept[IllegalArgumentException](Qr.encode("x" * 5000, 300))
    assert(e.getMessage.contains("too large"))
  }

  test("rescan never adds an address index twice") {
    val have = Seq(new Store.Addr(0, "a0"), new Store.Addr(1, "a1")) // index 1 added by hand while the rescan ran
    val found = Seq(new Store.Addr(1, "a1"), new Store.Addr(4, "a4"))
    assert(Ergo.newAddresses(have, found).map(_.index) == Seq(4))
  }

  test("ErgoPay/ErgoAuth links: the host shown is the host contacted; user-info tricks are refused") {
    assert(Ergo.linkTarget("ergoauth://dapp.example.com/auth?id=1", "ergoauth") == ("https://dapp.example.com/auth?id=1", "https://dapp.example.com"))
    assert(Ergo.linkTarget("ergoauth://192.168.1.5:8080/a", "ergoauth") == ("http://192.168.1.5:8080/a", "http://192.168.1.5:8080"))
    assert(Ergo.linkTarget("ergopay://dapp.example.com/pay/9abc", "ergopay")._1 == "https://dapp.example.com/pay/9abc")
    for (trick <- Seq("ergoauth://good.com@evil.com/auth", "ergoauth://good.com:pw@evil.com/auth", "ergoauth:///nohost"))
      assertThrows[IllegalArgumentException](Ergo.linkTarget(trick, "ergoauth"))
    // passes the "local" check, but would fetch evil.com over plain http
    assertThrows[IllegalArgumentException](Ergo.linkTarget("ergopay://localhost:1@evil.com/tx", "ergopay"))
  }

  test("token names from their creator never render as HTML") {
    def label(name: String) = Ergo.TokenInfo("ab" * 32, Some(name), None, 0, 1, None, None).label
    for (evil <- Seq("<html><img src='http://tracker/t.png'>", "<HTML><b>1000 ERG</b>"))
      assert(!javax.swing.plaf.basic.BasicHTML.isHTMLString(label(evil)), evil)
    assert(label("SigUSD") == "SigUSD")
  }

  test("an old verified tip is reported, a recent one is not") {
    val now = 1760000000000L
    assert(LightClient.staleWarning(now - 10 * 60000L, now).isEmpty)
    assert(LightClient.staleWarning(now - 45 * 60000L, now).exists(_.contains("45 minutes")))
    assert(LightClient.staleWarning(now - 26 * 3600000L, now).exists(_.contains("26 hours")))
  }

  test("node and explorer URLs must be absolute http(s) URLs") {
    for (ok <- Seq("https://sv1.erg.vn", "http://192.168.1.5:9053", "https://api.ergoplatform.com")) assert(Http.isHttpUrl(ok), ok)
    for (bad <- Seq("sv1.erg.vn", "ftp://x.org", "https://", "http//sv1.erg.vn", "", "https://a b.com")) assert(!Http.isHttpUrl(bad), bad)
  }

  test("dialogs are owned by the main window itself, not by its (missing) ancestor") {
    assume(!java.awt.GraphicsEnvironment.isHeadless)
    val f = new javax.swing.JFrame
    val inner = new javax.swing.JButton
    f.add(inner)
    try {
      assert(javax.swing.SwingUtilities.getWindowAncestor(f) == null, "the trap: a frame has no ancestor")
      assert(Dialogs.windowOf(f) eq f)
      assert(Dialogs.windowOf(inner) eq f)
      assert(Dialogs.windowOf(null) == null)
    } finally f.dispose()
  }

  test("box selection") {
    val plain1 = box(1000000000L)
    val plain2 = box(2000000000L)
    val withToken = box(1000000L, new ErgoToken(Token, 500))
    val all = Seq(plain1, plain2, withToken)
    assert(Ergo.selectBoxes(all, 500000000L, Map.empty) == Vector(plain1))
    val t = Ergo.selectBoxes(all, 500000000L, Map(Token -> 100L))
    assert(t.head == withToken)
    assert(t.contains(plain1))
    assertThrows[IllegalStateException](Ergo.selectBoxes(all, 10000000000L, Map.empty))
    assertThrows[IllegalStateException](Ergo.selectBoxes(all, 1, Map(Token -> 501L)))
  }

  test("build -> reduce -> summarize -> sign offline with the derived key of the input box") {
    val s = secret()
    val me = Ergo.address(s, 1)
    val in = box(me, 5000000000L)
    // shortcut: needs a node for the blockchain context (cold contexts cannot build txs); upgrade to a mocked data source if CI is offline
    val reduced = Ergo.prepareTx(Store.DefaultNodes.head, Seq(in), me, Ergo.address(s, 0), 1000000000L, Map.empty, Parameters.MinFee)

    val sum = Ergo.summarize(reduced)
    assert(sum.outputs.size == 3)
    assert(sum.outputs.exists(o => o.address == Ergo.FeeAddress && o.value == Parameters.MinFee))

    assertThrows[IllegalStateException](Ergo.signReduced(reduced, s, Seq(0))) // index 0 key cannot sign index 1 box
    assert(Ergo.signedTxId(Ergo.signReduced(reduced, s, Seq(0, 1))).length == 64)
  }

  test("only boxes of the asked address, proven against the given state root, are returned") {
    implicit val hf: Blake2b256.type = Blake2b256
    val mine = box(1000000000L)
    val others = box(Ergo.address(secret(), 5), 2000000000L) // listed by a lying address index
    val spent = box(3000000000L) // not in the UTXO set
    val prover = new BatchAVLProver[Digest32, Blake2b256.type](32, None)
    Seq(mine, others).foreach(b => prover.performOneOperation(Insert(b.id, ADValue @@ b.bytes)))
    prover.generateProof()
    val root = prover.digest.clone()
    val ids = Seq(mine.id, others.id, spent.id)
    ids.foreach(id => prover.performOneOperation(Lookup(id)))
    val proof = prover.generateProof()
    val myTree = Address.create(Ergo.address(secret(), 0)).toPropositionBytes
    val got = LightClient.lookupAll(root, proof, ids, myTree)
    assert(got.map(_.map(b => Hex.toHexString(b.id))) == Some(Seq(Hex.toHexString(mine.id))))
    val otherRoot = root.clone()
    otherRoot(5) = (otherRoot(5) ^ 1).toByte
    assert(LightClient.lookupAll(otherRoot, proof, ids, myTree).isEmpty)
  }

  private def box(value: Long, tokens: ErgoToken*): ErgoBox = box(Ergo.address(secret(), 0), value, tokens: _*)

  /** Serializes a box by hand (cold contexts cannot build transactions): value, tree, height, tokens, no registers, txId, index. */
  private def box(address: String, value: Long, tokens: ErgoToken*): ErgoBox = {
    val o = new ByteArrayOutputStream
    Nipopow.vlq(o, value)
    o.writeBytes(Address.create(address).toPropositionBytes)
    Nipopow.vlq(o, 1000000)
    o.write(tokens.size)
    tokens.foreach { t =>
      o.writeBytes(t.getId.getBytes)
      Nipopow.vlq(o, t.getValue)
    }
    o.write(0)
    val txId = new Array[Byte](32)
    new SecureRandom().nextBytes(txId)
    o.writeBytes(txId)
    Nipopow.vlq(o, 0)
    ErgoBox.sigmaSerializer.fromBytes(o.toByteArray)
  }
}
