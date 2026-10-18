package vn.erg.wallet

import java.util.Arrays

import com.google.gson.{JsonArray, JsonParser}
import vn.erg.wallet.Nipopow.Proof
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.ErgoBox
import scorex.crypto.authds.avltree.batch.{BatchAVLVerifier, Lookup}
import scorex.crypto.authds.{ADDigest, ADKey, SerializedAdProof}
import scorex.crypto.hash.{Blake2b256, Digest32}

import scala.collection.JavaConverters._
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future, blocking}
import scala.util.Try
import scala.util.control.NonFatal

/**
 * Light client: bootstraps the chain tip from NiPoPoW proofs of several nodes (best valid proof wins),
 * then checks wallet boxes against the stateRoot of a verified header with an AVL+ lookup proof.
 */
final class LightClient(m: Int = LightClient.M, k: Int = LightClient.K) {
  import LightClient._

  @volatile var best: Option[Proof] = None

  def bestNode: String = best.getOrElse(throw new IllegalStateException("Chain not verified yet")).source

  /** Fetches and verifies a proof from every node in parallel (a dead node must not hold up the others), keeps the best. */
  def sync(nodes: Seq[String], log: String => Unit): Proof = {
    val pending = nodes.map(node => Future(blocking(fetchProof(node, log))))
    val valid = pending.flatMap(f => Await.result(f, Duration.Inf)) // node order kept: ties go to the first node
    val winner = valid.reduceOption((a, b) => if (b.isBetterThan(a)) b else a)
      .getOrElse(throw new IllegalStateException("no node returned a valid NiPoPoW proof"))
    best = Some(winner)
    log(s"Best chain from ${winner.source}, tip ${winner.tip.idHex} @ ${winner.tip.height}")
    staleWarning(winner.tip.timestamp, System.currentTimeMillis).foreach(w => log("WARNING " + w))
    winner
  }

  private def fetchProof(node: String, log: String => Unit): Option[Proof] =
    try {
      log(s"Fetching NiPoPoW proof (m=$m, k=$k) from $node")
      val p = new Proof(JsonParser.parseString(Http.get(s"$node/nipopow/proof/$m/$k")).getAsJsonObject, node)
      // the node must not pick its own security parameters: a smaller m or k makes a forged proof cheaper
      Nipopow.check(p.m == m && p.k == k, s"proof uses m=${p.m}, k=${p.k} instead of m=$m, k=$k")
      p.verify(id => JsonParser.parseString(Http.get(s"$node/blocks/$id")).getAsJsonObject
        .getAsJsonObject("extension").getAsJsonArray("fields"))
      log(s"  valid from $node: ${p.prefix.size} prefix + ${p.suffix.size} suffix headers, tip height ${p.tip.height}")
      Some(p)
    } catch {
      case NonFatal(e) =>
        log(s"  rejected $node: ${e.getMessage}")
        None
    }

  /**
   * Unspent boxes of the address that are proven to be in the UTXO set of a verified header.
   * shortcut: the node's address index chooses which box ids we ask about, so it can hide boxes (not forge them).
   */
  def verifiedUnspent(nodes: Seq[String], address: String, propositionBytes: Array[Byte], log: String => Unit): Seq[ErgoBox] =
    anyNode(nodes, log) { node =>
      val ids = boxIds(node, address)
      if (ids.isEmpty) Vector.empty
      // a block may arrive while a big address is listed: re-sync, then ask for the proof again at once (same ids)
      else withResync(proven(node, ids, propositionBytes, log)) {
        log("Node state is newer than the verified tip: re-syncing NiPoPoW")
        sync(nodes, log)
      }
    }

  /**
   * Whether the address appears in any transaction (address index of a node), for gap-limit address discovery.
   * shortcut: unverified like the box list; a node can hide usage (an address is then missed), not invent funds.
   */
  def addressUsed(nodes: Seq[String], address: String, log: String => Unit): Boolean =
    anyNode(nodes, log) { node =>
      JsonParser.parseString(Http.post(s"$node/blockchain/transaction/byAddress?offset=0&limit=1", "text/plain", address))
        .getAsJsonObject.get("total").getAsLong > 0
    }

  /** Best node first, then the others: not every node has the extra address index. */
  private def anyNode[T](nodes: Seq[String], log: String => Unit)(call: String => T): T = {
    val errors = ArrayBuffer.empty[Throwable]
    (bestNode +: nodes.filterNot(_ == bestNode)).iterator
      .map { node =>
        // not only HTTP errors: an HTML page or a missing field (proxy, other API version) must not stop the others
        try Some(call(node)) catch {
          case NonFatal(e) =>
            log(s"  $node: ${Option(e.getMessage).getOrElse(e.toString).split("\n").head}")
            errors += e
            None
        }
      }
      .collectFirst { case Some(r) => r }
      .getOrElse(throw errors.last)
  }

  /**
   * Box ids of the address from the node's index, all pages (newest first).
   * shortcut: a box spent while the pages are read shifts the list up and one other box is skipped until the next
   * refresh; the balance then shows too low, never too high. A snapshot API on the node would remove this.
   */
  private def boxIds(node: String, address: String): Vector[Array[Byte]] =
    allPages(PageSize) { offset =>
      JsonParser.parseString(Http.post(s"$node/blockchain/box/unspent/byAddress?offset=$offset&limit=$PageSize", "text/plain", address))
        .getAsJsonArray.asScala.map(_.getAsJsonObject.get("boxId").getAsString).toVector
    }.distinct.map(Hex.decode) // a box arriving between pages shifts the list: the same id can show up twice

  /** Boxes among `ids` proven unspent by the node's AVL proof against the stateRoot of a verified header. */
  private def proven(node: String, ids: Vector[Array[Byte]], propositionBytes: Array[Byte], log: String => Unit): Seq[ErgoBox] = {
    val body = new JsonArray
    ids.foreach(id => body.add(Hex.toHexString(id)))
    val proof = Hex.decode(JsonParser.parseString(Http.post(s"$node/utxo/getBoxesBinaryProof", "application/json", body.toString)).getAsString)
    // The node proves against its current state; match it with a header from our verified suffix (newest first).
    best.get.suffix.reverseIterator
      .map(h => h -> lookupAll(h.stateRoot, proof, ids, propositionBytes))
      .collectFirst { case (h, Some(boxes)) =>
        log(s"${boxes.size}/${ids.size} boxes from $node proven unspent at verified height ${h.height}")
        boxes
      }
      .getOrElse(throw new StaleTip(s"UTXO proof of $node does not match any verified header (the node moved on)"))
  }
}

object LightClient {
  val M = 6
  val K = 10
  val PageSize = 500
  val MaxPages = 200 // 100,000 boxes per address; also stops a node that ignores `offset` from looping forever

  val StaleTipMs: Long = 30 * 60000L // blocks come every ~2 minutes; half an hour without one is very unlikely

  /** A stuck (or withholding) node can only offer an old tip: its balances are proven, but out of date. */
  def staleWarning(tipTimestamp: Long, now: Long): Option[String] = {
    val min = (now - tipTimestamp) / 60000
    if (now - tipTimestamp <= StaleTipMs) None
    else Some(s"verified tip is ${if (min >= 120) s"${min / 60} hours" else s"$min minutes"} old: balances may be out of date")
  }

  /** The node's UTXO proof is for a state newer than every header we verified (the suffix spans ~20 minutes). */
  final class StaleTip(msg: String) extends IllegalStateException(msg)

  /** Runs `call`; if the verified tip is stale, runs `resync` once and tries again. */
  def withResync[T](call: => T)(resync: => Unit): T =
    try call catch {
      case _: StaleTip =>
        resync
        call
    }

  /** Reads `page(offset)` until a page comes back short (or MaxPages pages were read). */
  def allPages[T](pageSize: Int)(page: Int => Seq[T]): Vector[T] = {
    val out = Vector.newBuilder[T]
    var (offset, n, last) = (0, 0, pageSize)
    while (last == pageSize && n < MaxPages) {
      val p = page(offset)
      out ++= p
      last = p.size
      offset += pageSize
      n += 1
    }
    out.result()
  }

  private implicit val hf: Blake2b256.type = Blake2b256

  /** None if the proof does not open against this state root; absent keys (spent boxes) are skipped. */
  private def lookupAll(stateRoot: Array[Byte], proof: Array[Byte], ids: Seq[Array[Byte]], propositionBytes: Array[Byte]): Option[Seq[ErgoBox]] =
    Try {
      val v = new BatchAVLVerifier[Digest32, Blake2b256.type](ADDigest @@ stateRoot, SerializedAdProof @@ proof, 32, None, None, None)
      ids.flatMap { id =>
        v.performOneOperation(Lookup(ADKey @@ id)).get
          .map(bytes => ErgoBox.sigmaSerializer.fromBytes(bytes))
          .filter(box => Arrays.equals(box.id, id) && Arrays.equals(box.ergoTree.bytes, propositionBytes))
      }
    }.toOption
}
