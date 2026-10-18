package vn.erg.wallet

import java.security.SecureRandom

import com.google.gson.{JsonArray, JsonObject, JsonParser}
import org.bouncycastle.util.encoders.Hex
import org.scalatest.funsuite.AnyFunSuite

import scala.io.Source

/** Real mainnet proof (tip 1897174), includes Autolykos v1 + v2 headers and an epoch-start block (1896448). */
class NipopowSpec extends AnyFunSuite {
  private def resource(name: String): String = {
    val src = Source.fromInputStream(getClass.getResourceAsStream("/" + name), "UTF-8")
    try src.mkString finally src.close()
  }

  private val extensions: String => JsonArray = id =>
    if (id != "23003640d320c3ae0c3b6d62269b377c3eb73b0819a855328a8abcd9276f8250") new JsonArray
    else JsonParser.parseString(resource("extension-1896448.json")).getAsJsonArray

  private def proofJson: JsonObject = JsonParser.parseString(resource("proof-m6-k6.json")).getAsJsonObject

  /** Same proof with the last two suffix headers cut off (a node lagging behind). */
  private def truncated: Nipopow.Proof = {
    val j = proofJson
    val tail = j.getAsJsonArray("suffixTail")
    tail.remove(tail.size - 1)
    tail.remove(tail.size - 1)
    j.addProperty("k", 4)
    new Nipopow.Proof(j, "short")
  }

  test("real mainnet proof verifies and the longer chain wins") {
    val p = new Nipopow.Proof(proofJson, "fixture")
    p.verify(extensions)
    assert(p.tip.height == 1897174)
    assert(p.isBetterThan(truncated), "longer chain must win")
    assert(!truncated.isBetterThan(p))
  }

  /** A header JSON with difficulty 1 (nBits 0x01010000): its proof-of-work costs nothing. */
  private def cheapHeader(parent: Array[Byte], height: Int): (JsonObject, Array[Byte]) = {
    val rnd = new SecureRandom
    def bytes(n: Int) = { val b = new Array[Byte](n); rnd.nextBytes(b); b }
    val (adp, txs, state, ext, pk, w, nonce) = (bytes(32), bytes(32), bytes(33), bytes(32), bytes(33), bytes(33), bytes(8))
    val nBits = 0x01010000L
    val h = new Nipopow.Header(2, height, 1792313157757L + height, nBits, parent, adp, txs, state, ext, Array[Byte](0, 0, 0),
      Array.emptyByteArray, pk, w, nonce, BigInt(0), Vector.empty)
    val j = new JsonObject
    j.addProperty("version", 2); j.addProperty("height", height); j.addProperty("timestamp", 1792313157757L + height)
    j.addProperty("nBits", nBits); j.addProperty("parentId", Hex.toHexString(parent)); j.addProperty("adProofsRoot", Hex.toHexString(adp))
    j.addProperty("transactionsRoot", Hex.toHexString(txs)); j.addProperty("stateRoot", Hex.toHexString(state))
    j.addProperty("extensionHash", Hex.toHexString(ext)); j.addProperty("votes", "000000"); j.addProperty("unparsedBytes", "")
    j.addProperty("id", h.idHex)
    val pow = new JsonObject
    pow.addProperty("pk", Hex.toHexString(pk)); pow.addProperty("w", Hex.toHexString(w)); pow.addProperty("n", Hex.toHexString(nonce))
    pow.addProperty("d", 0)
    j.add("powSolutions", pow)
    (j, h.id)
  }

  test("a longer fork of cheap low-difficulty headers loses against real proof-of-work") {
    val honest = new Nipopow.Proof(proofJson, "honest")
    // same prefix and suffix head as the real proof, then 20 forged headers instead of the real 5
    val j = proofJson
    val head = honest.suffix.head
    val tail = new JsonArray
    var parent = head.id
    for (i <- 1 to 20) {
      val (h, id) = cheapHeader(parent, head.height + i)
      tail.add(h)
      parent = id
    }
    j.add("suffixTail", tail)
    j.addProperty("k", 21)
    val forged = new Nipopow.Proof(j, "forged")
    forged.verify(extensions) // links and (trivial) PoW are valid: only the work-weighted score can tell
    assert(forged.tip.height > honest.tip.height)
    assert(honest.isBetterThan(forged), "real work must win")
    assert(!forged.isBetterThan(honest), "more headers of no work must not win")
  }

  test("forged header id is rejected") {
    val j = proofJson
    j.getAsJsonArray("suffixTail").get(0).getAsJsonObject.addProperty("timestamp", 1)
    assertThrows[IllegalArgumentException](new Nipopow.Proof(j, "x"))
  }

  test("broken suffix is rejected") {
    val j = proofJson
    j.getAsJsonArray("suffixTail").remove(1)
    j.addProperty("k", 5)
    val p = new Nipopow.Proof(j, "x")
    assertThrows[IllegalStateException](p.verify(extensions))
  }

  test("dropped interlink is rejected") {
    val j = proofJson
    j.getAsJsonArray("prefix").get(40).getAsJsonObject.getAsJsonArray("interlinks").remove(3)
    val p = new Nipopow.Proof(j, "x")
    assertThrows[IllegalStateException](p.verify(extensions))
  }
}
