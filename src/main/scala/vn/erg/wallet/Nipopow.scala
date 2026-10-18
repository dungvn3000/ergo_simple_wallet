package vn.erg.wallet

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.util.Arrays

import com.google.gson.{JsonArray, JsonObject}
import org.bouncycastle.crypto.digests.Blake2bDigest
import org.bouncycastle.crypto.ec.CustomNamedCurves
import org.bouncycastle.util.encoders.Hex

import scala.collection.JavaConverters._

/** NiPoPoW proof verification for Ergo (port of NipopowProof / AutolykosPowScheme from the reference node). */
object Nipopow {
  val MainnetGenesisId = "b0244dfc267baca974a4caee06120321562784303a8a688976ae56170e4d175b"

  /** Every proof must start here: mainnet, or a devnet's own (random) genesis pinned with -Dergo.genesis=<id>. */
  def genesisId: String = if (Ergo.Devnet) sys.props.getOrElse("ergo.genesis", "(not set: pass -Dergo.genesis)") else MainnetGenesisId

  private val Curve = CustomNamedCurves.getByName("secp256k1")
  val Q: BigInt = BigInt(Curve.getN)
  val K = 32
  val NBase: Int = 1 << 26
  val M: Array[Byte] = {
    val b = ByteBuffer.allocate(1024 * 8)
    (0L until 1024L).foreach(i => b.putLong(i))
    b.array()
  }
  val MaxDiffHeaders: Int = 8 + 1 // useLastEpochs + 1

  def hash(parts: Array[Byte]*): Array[Byte] = {
    val d = new Blake2bDigest(256)
    parts.foreach(p => d.update(p, 0, p.length))
    val out = new Array[Byte](32)
    d.doFinal(out, 0)
    out
  }

  def int4(v: Long): Array[Byte] = ByteBuffer.allocate(4).putInt(v.toInt).array()

  // ---------------------------------------------------------------- header

  final class Header private[wallet] (val version: Int, val height: Int, val timestamp: Long, nBits: Long,
                              val parentId: Array[Byte], adProofsRoot: Array[Byte], transactionsRoot: Array[Byte],
                              val stateRoot: Array[Byte], val extensionRoot: Array[Byte], votes: Array[Byte],
                              unparsed: Array[Byte], pk: Array[Byte], w: Array[Byte], nonce: Array[Byte], d: BigInt,
                              val interlinks: Seq[Array[Byte]]) {

    val id: Array[Byte] = hash(bytes)

    def idHex: String = Hex.toHexString(id)

    def bytesWithoutPow: Array[Byte] = {
      val o = new ByteArrayOutputStream
      o.write(version)
      Seq(parentId, adProofsRoot, transactionsRoot, stateRoot).foreach(b => o.writeBytes(b))
      vlq(o, timestamp)
      o.writeBytes(extensionRoot)
      o.writeBytes(int4(nBits))
      vlq(o, height)
      o.writeBytes(votes)
      if (version > 1) {
        o.write(unparsed.length)
        o.writeBytes(unparsed)
      }
      o.toByteArray
    }

    def bytes: Array[Byte] = {
      val o = new ByteArrayOutputStream
      o.writeBytes(bytesWithoutPow)
      o.writeBytes(pk)
      if (version == 1) {
        o.writeBytes(w)
        o.writeBytes(nonce)
        val db = unsigned(d)
        o.write(db.length)
        o.writeBytes(db)
      } else o.writeBytes(nonce)
      o.toByteArray
    }

    def difficulty: BigInt = decodeCompactBits(nBits)

    def target: BigInt = Q / difficulty

    /** Autolykos check, computed once: (solution valid, PoW hit). */
    private lazy val pow: (Boolean, BigInt) = {
      val msg = hash(bytesWithoutPow)
      val n = calcN(version, height)
      if (version == 1) {
        val f = genIndexes(concat(msg, nonce), n).map(idx => hashModQ(concat(int4(idx), M, pk, msg, w))).sum.mod(Q)
        val pkP = Curve.getCurve.decodePoint(pk)
        val wP = Curve.getCurve.decodePoint(w)
        val ok = !pkP.isInfinity && !wP.isInfinity && d < target &&
          wP.multiply(f.bigInteger).normalize() == Curve.getG.multiply(d.bigInteger).add(pkP).normalize()
        (ok, d)
      } else {
        val hb = int4(height)
        val i = BigInt(1, hash(msg, nonce).slice(24, 32)).mod(n)
        val e = hash(int4(i.toLong), hb, M).drop(1)
        val sum = genIndexes(concat(e, msg, nonce), n).map(idx => BigInt(1, hash(int4(idx), hb, M).drop(1))).sum
        val hit = BigInt(1, hash(fixed32(sum)))
        (hit < target, hit)
      }
    }

    def powValid: Boolean = pow._1

    /** µ-level of a superblock: how many times the hit beats the target by a factor of 2. */
    def maxLevel: Int = if (height == 1) Int.MaxValue else (log2(target) - log2(pow._2)).toInt
  }

  object Header {
    def fromJson(j: JsonObject, interlinks: Seq[Array[Byte]] = Vector.empty): Header = {
      val s = j.getAsJsonObject("powSolutions")
      val h = new Header(j.get("version").getAsInt, j.get("height").getAsInt, j.get("timestamp").getAsLong, j.get("nBits").getAsLong,
        hex(j, "parentId"), hex(j, "adProofsRoot"), hex(j, "transactionsRoot"), hex(j, "stateRoot"), hex(j, "extensionHash"),
        hex(j, "votes"), if (j.has("unparsedBytes")) hex(j, "unparsedBytes") else Array.emptyByteArray,
        hex(s, "pk"), hex(s, "w"), hex(s, "n"), BigInt(s.get("d").getAsBigInteger), interlinks)
      // never trust the id sent by the node, only the one we compute
      if (h.idHex != j.get("id").getAsString) throw new IllegalArgumentException(s"header id mismatch at height ${h.height}")
      h
    }

    def fromPopowJson(j: JsonObject): Header =
      fromJson(j.getAsJsonObject("header"), j.getAsJsonArray("interlinks").asScala.map(e => Hex.decode(e.getAsString)).toVector)
  }

  def calcN(version: Int, headerHeight: Int): Int =
    if (version == 1) NBase
    else {
      val height = math.min(4198400, headerHeight)
      if (height < 600 * 1024) NBase
      else (1 to (height - 600 * 1024) / (50 * 1024) + 1).foldLeft(NBase)((n, _) => n / 100 * 105)
    }

  def genIndexes(seed: Array[Byte], n: Int): Seq[Int] = {
    val h = hash(seed)
    val ext = h ++ h.take(3)
    (0 until K).map(i => BigInt(1, ext.slice(i, i + 4)).mod(n).toInt)
  }

  private val ValidRange = (BigInt(2).pow(256) / Q) * Q

  def hashModQ(input: Array[Byte]): BigInt = {
    var h = hash(input)
    while (BigInt(1, h) >= ValidRange) h = hash(h)
    BigInt(1, h).mod(Q)
  }

  def decodeCompactBits(nBits: Long): BigInt = {
    val size = ((nBits >>> 24) & 0xff).toInt
    val b = new Array[Byte](size)
    for (i <- 0 until math.min(3, size)) b(i) = (nBits >>> (16 - 8 * i)).toByte
    BigInt(1, b)
  }

  // ---------------------------------------------------------------- interlinks (extension merkle root)

  def merkleRoot(leafData: Seq[Array[Byte]]): Array[Byte] =
    if (leafData.isEmpty) new Array[Byte](32)
    else {
      var lvl = leafData.map(d => hash(Array[Byte](0), d)).toVector
      do lvl = lvl.grouped(2).map(p => hash(Array[Byte](1), p.head, if (p.size > 1) p(1) else Array.emptyByteArray)).toVector
      while (lvl.size > 1)
      lvl.head
    }

  /** Extension leaf: [keyLength] ++ key ++ value. Interlinks are run-length packed: key [0x01, idx], value [count] ++ id. */
  def packInterlinks(links: Seq[Array[Byte]]): Seq[Array[Byte]] = {
    val out = Vector.newBuilder[Array[Byte]]
    var i = 0
    while (i < links.size) {
      val count = links.drop(i).takeWhile(l => Arrays.equals(l, links(i))).size
      out += concat(Array[Byte](2, 1, i.toByte, count.toByte), links(i))
      i += count
    }
    out.result()
  }

  /**
   * Binds the interlinks to the header's extensionRoot. The node's own interlinksProof is built over an
   * interlinks-only tree, which does not match the header when the extension also carries parameters
   * (epoch-start blocks); for those we download the full extension and check it ourselves.
   */
  def interlinksValid(h: Header, extensionFields: String => JsonArray): Boolean =
    if (h.interlinks.isEmpty) h.height == 1
    else {
      val packed = packInterlinks(h.interlinks)
      Arrays.equals(merkleRoot(packed), h.extensionRoot) || {
        val fields = extensionFields(h.idHex).asScala.toVector.map { e =>
          val kv = e.getAsJsonArray
          val key = Hex.decode(kv.get(0).getAsString)
          (key, concat(Array(key.length.toByte), key, Hex.decode(kv.get(1).getAsString)))
        }
        val links = fields.collect { case (key, leaf) if key(0) == 1 => leaf }
        Arrays.equals(merkleRoot(fields.map(_._2)), h.extensionRoot) && links.size == packed.size &&
          links.zip(packed).forall { case (a, b) => Arrays.equals(a, b) }
      }
    }

  // ---------------------------------------------------------------- proof

  final class Proof(j: JsonObject, val source: String) {
    val m: Int = j.get("m").getAsInt
    val k: Int = j.get("k").getAsInt
    val prefix: Vector[Header] = j.getAsJsonArray("prefix").asScala.map(e => Header.fromPopowJson(e.getAsJsonObject)).toVector
    /** suffixHead followed by suffixTail */
    val suffix: Vector[Header] = Header.fromPopowJson(j.getAsJsonObject("suffixHead")) +:
      j.getAsJsonArray("suffixTail").asScala.map(e => Header.fromJson(e.getAsJsonObject)).toVector

    def chain: Vector[Header] = prefix ++ suffix

    def tip: Header = suffix.last

    /** Throws with a reason if the proof is invalid. */
    def verify(extensionFields: String => JsonArray): Unit = {
      val chain = this.chain
      check(prefix.nonEmpty && prefix.head.idHex == genesisId, s"prefix does not start at genesis $genesisId")
      check(suffix.size == k, "suffix length != k")
      for (i <- 1 until chain.size) check(chain(i - 1).height < chain(i).height, s"heights not increasing at ${chain(i).height}")
      // like the reference node: difficulty headers (continuous mode) may sit between linked blocks
      val linked = prefix :+ suffix.head
      for (i <- 1 until linked.size) {
        val next = linked(i)
        val ok = (i - 1 to math.max(0, i - MaxDiffHeaders - 2) by -1).exists { j =>
          val prev = linked(j).id
          Arrays.equals(next.parentId, prev) || next.interlinks.exists(l => Arrays.equals(l, prev))
        }
        check(ok, s"broken interlink at ${next.height}")
      }
      for (i <- 1 until suffix.size)
        check(Arrays.equals(suffix(i).parentId, suffix(i - 1).id), s"broken parent link at ${suffix(i).height}")
      linked.foreach(h => check(interlinksValid(h, extensionFields), s"interlinks not in extension at ${h.height}"))
      chain.foreach(h => check(h.powValid, s"invalid PoW at ${h.height}"))
    }

    /** NiPoPoW comparison: best-argument score of the chains after their lowest common ancestor. */
    def isBetterThan(that: Proof): Boolean = {
      val thatIds = that.chain.map(_.idHex).toSet
      val fork = (0 +: chain.filter(h => thatIds(h.idHex)).map(_.height)).max
      bestArg(chain.filter(_.height > fork), m) > bestArg(that.chain.filter(_.height > fork), m)
    }
  }

  /**
   * Best-argument score, weighted by work: level µ scores Σ difficulty·2^µ over its headers (while at least m remain),
   * level 0 sums all difficulties. Counting headers alone would let a node win with a cheap chain of low-difficulty
   * headers (difficulty is not checked against the adjustment rules); difficulty·2^µ ≤ q/hit, so a score always
   * costs that much real proof-of-work.
   */
  def bestArg(chain: Seq[Header], m: Int): BigInt = {
    val levels = Iterator.range(1, 256).map(l => (l, chain.filter(_.maxLevel >= l))).takeWhile(_._2.size >= m)
    (chain.map(_.difficulty).sum +: levels.map { case (l, hs) => hs.map(_.difficulty).sum << l }.toVector).max
  }

  // ---------------------------------------------------------------- utils

  def check(ok: Boolean, msg: => String): Unit = if (!ok) throw new IllegalStateException(msg)

  def hex(o: JsonObject, key: String): Array[Byte] = Hex.decode(o.get(key).getAsString)

  def concat(parts: Array[Byte]*): Array[Byte] = Array.concat(parts: _*)

  def unsigned(v: BigInt): Array[Byte] = {
    val b = v.toByteArray
    if (b.length > 1 && b(0) == 0) b.drop(1) else b
  }

  def fixed32(v: BigInt): Array[Byte] = {
    val b = unsigned(v)
    new Array[Byte](32 - b.length) ++ b
  }

  def vlq(o: ByteArrayOutputStream, value: Long): Unit = {
    var v = value
    while ((v & ~0x7FL) != 0) {
      o.write(((v & 0x7F) | 0x80).toInt)
      v >>>= 7
    }
    o.write(v.toInt)
  }

  def log2(v: BigInt): Double = {
    val shift = math.max(0, v.bitLength - 60)
    math.log((v >> shift).toDouble) / math.log(2) + shift
  }
}
