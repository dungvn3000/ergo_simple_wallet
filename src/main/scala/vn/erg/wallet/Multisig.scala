package vn.erg.wallet

import java.math.BigInteger
import java.nio.charset.StandardCharsets

import io.circe.syntax._
import io.circe.{Decoder, Encoder}
import org.bouncycastle.util.encoders.Hex
import org.ergoplatform.appkit.{Address, ConstantsBuilder, Parameters, SigmaProp, Signature}
import org.ergoplatform.sdk.{AppkitProvingInterpreter, CBlockchainParameters, ReducedErgoLikeTransaction}
import org.ergoplatform.{ErgoLikeTransaction, ErgoLikeTransactionSerializer, Input}
import sigma.crypto.Platform.Ecp
import sigma.data.{CAND, COR, CTHRESHOLD, ProveDlog, SigmaBoolean}
import sigma.interpreter.ProverResult
import sigma.serialization.{GroupElementSerializer, SigmaSerializer}
import sigmastate.NodePosition
import sigmastate.crypto.DLogProtocol.{DLogProverInput, FirstDLogProverMessage}
import sigmastate.interpreter.{HintsBag, OwnCommitment, RealCommitment}

/**
 * k-of-n multisig: the P2S address `atLeast(k, Coll(PK(a), PK(b), ...))` and the distributed signing of sigma protocols.
 * The signers pass one session file around. Round 1: k of them add a commitment each (its secret nonce stays sealed in
 * the signer's wallet file). Round 2: each signs in turn on top of the previous partial proof; the k-th completes it.
 */
object Multisig {
  def address(k: Int, participants: Seq[String]): String = {
    val ps = participants.map(_.trim).filter(_.nonEmpty)
    require(ps.size >= 2, "A multisig address needs at least two participants")
    require(ps.distinct.size == ps.size, "Participants must be different addresses")
    require(k >= 1 && k <= ps.size, s"Required signatures must be between 1 and ${ps.size}")
    ps.foreach(p => require(Ergo.isValidAddress(p) && Address.create(p).isP2PK, s"Not a P2PK address of this network: $p"))
    Ergo.cold.execute[String] { ctx =>
      val c = ctx.compileContract(ConstantsBuilder.empty(), s"atLeast($k, Coll(${ps.map(p => s"""PK("$p")""").mkString(", ")}))")
      Address.fromErgoTree(c.getErgoTree, Ergo.Net).toString
    }
  }

  /** The signer key (as in session files) of a P2PK address. */
  def pkOf(address: String): String = hex(Address.create(address).getPublicKey)

  // ---------------------------------------------------------------- session file

  /** Public commitment of signer `pk` (hex of its group element) for input `input`. */
  final case class Commit(input: Int, pk: String, a: String, pos: Vector[Int])

  /** The secret half of an own commitment; must never sign two different challenges (that would reveal the key). */
  final case class Nonce(c: Commit, r: String)

  final case class Session(reducedTx: Array[Byte], commits: Vector[Commit], proofs: Vector[Array[Byte]], signed: Vector[String])

  private implicit val commitEnc: Encoder[Commit] = Encoder.forProduct4("input", "pk", "a", "pos")(c => (c.input, c.pk, c.a, c.pos))
  private implicit val commitDec: Decoder[Commit] = Decoder.forProduct4("input", "pk", "a", "pos")(Commit.apply)
  private implicit val nonceEnc: Encoder[Nonce] = Encoder.forProduct2("c", "r")(n => (n.c, n.r))
  private implicit val nonceDec: Decoder[Nonce] = Decoder.forProduct2("c", "r")(Nonce.apply)
  private implicit val sessionEnc: Encoder[Session] = Encoder.forProduct4("reducedTx", "commits", "proofs", "signed")(
    s => (Ergo.b64(s.reducedTx), s.commits, s.proofs.map(Ergo.b64), s.signed))
  private implicit val sessionDec: Decoder[Session] = Decoder.forProduct4("reducedTx", "commits", "proofs", "signed")(
    (t: String, c: Vector[Commit], p: Vector[String], s: Vector[String]) => Session(Ergo.unb64(t), c, p.map(Ergo.unb64), s))

  def toJson(s: Session): String = s.asJson.spaces2

  def fromJson(text: String): Session = {
    val s = io.circe.parser.decode[Session](text).fold(e => throw new IllegalArgumentException(s"Not a multisig signing file: ${e.getMessage}"), identity)
    policy(reduced(s)) // fails early on a file that is not a multisig transaction
    s
  }

  def noncesToBytes(ns: Seq[Nonce]): Array[Byte] = ns.asJson.noSpaces.getBytes(StandardCharsets.UTF_8)

  def noncesFromBytes(b: Array[Byte]): Vector[Nonce] =
    io.circe.parser.decode[Vector[Nonce]](new String(b, StandardCharsets.UTF_8)).fold(e => throw new IllegalStateException(e.getMessage), identity)

  // ---------------------------------------------------------------- protocol

  def start(reducedTx: Array[Byte]): Session = {
    val s = Session(reducedTx, Vector.empty, Vector.empty, Vector.empty)
    policy(reduced(s))
    s
  }

  /** The unsigned transaction id: the key under which a signer keeps its nonces. */
  def id(s: Session): String = reduced(s).unsignedTx.id

  /** Required signatures and the signers' keys, the same for every input. */
  def required(s: Session): (Int, Seq[String]) = {
    val (k, pks) = policy(reduced(s))
    (k, pks.map(hex))
  }

  def committed(s: Session): Vector[String] = s.commits.map(_.pk).distinct

  /** Whether the file's signing condition is exactly `k` of these participants, in this order. */
  def matches(s: Session, k: Int, participants: Seq[String]): Boolean =
    policy(reduced(s)) == ((k, participants.map(a => Address.create(a).getPublicKey)))

  /**
   * Before any commitment or signature: the file's spending conditions are not proof of anything. A forged file could
   * claim a multisig condition for a box of a plain address of ours, then steer the simulated challenges so that our
   * partial signature is a valid single signature for that box. So every input must be a verified box of the known
   * multisig address, and the condition must be exactly its k and participants.
   */
  def checkInputs(s: Session, k: Int, participants: Seq[String], verifiedBoxIds: Set[String]): Unit = {
    if (!matches(s, k, participants))
      throw new IllegalArgumentException("The transaction's signing condition does not match this multisig address")
    if (!reduced(s).unsignedTx.inputs.forall(in => verifiedBoxIds(Hex.toHexString(in.boxId))))
      throw new IllegalArgumentException("Some inputs are not verified boxes of this multisig address. Refresh its balance; " +
        "if they still are not, do not sign this file.")
  }

  def complete(s: Session): Boolean = s.signed.nonEmpty && s.signed.size == required(s)._1

  def status(s: Session): String = {
    val k = required(s)._1
    if (complete(s)) s"Fully signed ($k signatures): ready to submit"
    else if (committed(s).size < k) s"Round 1: ${committed(s).size} of $k commitments"
    else s"Round 2: ${s.signed.size} of $k signatures"
  }

  /** Round 1: commitments for these signer keys, as long as fewer than k have committed. Random nonces: no secret key needed. */
  def commit(s: Session, keys: Seq[ProveDlog]): (Session, Vector[Nonce]) = {
    require(s.signed.isEmpty, "Signing has started: no more commitments")
    val tx = reduced(s)
    val (k, pks) = policy(tx)
    val mine = keys.filter(pks.contains).filterNot(p => committed(s).contains(hex(p)))
    require(mine.nonEmpty, "None of this wallet's addresses is a signer without a commitment yet")
    val take = mine.take(k - committed(s).size)
    require(take.nonEmpty, s"Already $k commitments: enough signers")
    val p = interpreter(Nil)
    val nonces = for {
      (sb, i) <- tx.reducedInputs.map(_.reductionResult.value).toVector.zipWithIndex
      oc <- p.generateCommitmentsFor(sb, take).ownCommitments
    } yield Nonce(Commit(i, hex(oc.image.asInstanceOf[ProveDlog]), point(oc.commitment.asInstanceOf[FirstDLogProverMessage].ecData),
      oc.position.positions.toVector), oc.secretRandomness.toString(16))
    (s.copy(commits = s.commits ++ nonces.map(_.c)), nonces)
  }

  /** Round 2: adds this wallet's signatures on top of the previous partial proof. The caller must then drop `nonces`. */
  def sign(s: Session, keys: Seq[DLogProverInput], nonces: Seq[Nonce]): Session = {
    val tx = reduced(s)
    val (k, pks) = policy(tx)
    require(committed(s).size == k, s"Waiting for commitments: ${committed(s).size} of $k")
    // our nonce must match the commitment in the file: anything else would sign a challenge we did not commit to
    val usable = nonces.filter(n => s.commits.contains(n.c))
    val mine = usable.map(_.c.pk).distinct.filterNot(s.signed.contains)
    require(mine.nonEmpty, "This wallet holds no unused commitment for this transaction")
    val p = interpreter(keys.filter(key => mine.contains(hex(key.publicImage))))
    val simulated = pks.filterNot(pk => committed(s).contains(hex(pk)))
    val msg = tx.unsignedTx.messageToSign
    val proofs = tx.reducedInputs.map(_.reductionResult.value).toVector.zipWithIndex.map { case (sb, i) =>
      val others = s.commits.filter(c => c.input == i && !mine.contains(c.pk))
        .map(c => RealCommitment(pk(c.pk), FirstDLogProverMessage(ecp(c.a)), NodePosition(c.pos)))
      val own = usable.filter(n => n.c.input == i && mine.contains(n.c.pk))
        .map(n => OwnCommitment(pk(n.c.pk), new BigInteger(n.r, 16), FirstDLogProverMessage(ecp(n.c.a)), NodePosition(n.c.pos)))
      val previous = s.proofs.lift(i).fold(HintsBag.empty)(pr => p.bagForMultisig(sb, pr, s.signed.map(pk), simulated))
      p.generateProof(sb, msg, previous.addHints(others ++ own: _*))
    }
    s.copy(proofs = proofs, signed = s.signed ++ mine)
  }

  /** The signed transaction, checked input by input before it leaves the wallet. */
  def signedTx(s: Session): Array[Byte] = {
    require(complete(s), "Not fully signed yet")
    val tx = reduced(s)
    val msg = tx.unsignedTx.messageToSign
    tx.reducedInputs.zip(s.proofs).foreach { case (r, proof) =>
      if (!Signature.verifySignature(new SigmaProp(r.reductionResult.value), msg, proof))
        throw new IllegalStateException("The combined signature is invalid")
    }
    val inputs = tx.unsignedTx.inputs.zip(tx.reducedInputs).zip(s.proofs).map { case ((in, r), proof) =>
      new Input(in.boxId, ProverResult(proof, r.extension))
    }
    // appkit's signed-transaction format: the transaction, then its cost
    val w = SigmaSerializer.startWriter()
    ErgoLikeTransactionSerializer.serialize(new ErgoLikeTransaction(inputs, tx.unsignedTx.dataInputs, tx.unsignedTx.outputCandidates), w)
    w.putUInt(tx.cost.toLong).toBytes
  }

  // ---------------------------------------------------------------- helpers

  private def reduced(s: Session): ReducedErgoLikeTransaction =
    try Ergo.cold.execute[ReducedErgoLikeTransaction](ctx => ctx.parseReducedTransaction(s.reducedTx).getTx)
    catch { case e: RuntimeException => throw new IllegalArgumentException("Corrupt transaction in the signing file", e) }

  private def policy(tx: ReducedErgoLikeTransaction): (Int, Seq[ProveDlog]) = {
    def dlogs(ch: Seq[SigmaBoolean]): Seq[ProveDlog] = ch.map {
      case p: ProveDlog => p
      case _ => throw new IllegalArgumentException("Only multisig over plain public keys is supported")
    }
    // atLeast(1, ..) reduces to an OR, atLeast(n, ..) of n keys to an AND
    val ps = tx.reducedInputs.map(_.reductionResult.value match {
      case CTHRESHOLD(k, ch) => (k, dlogs(ch))
      case COR(ch) => (1, dlogs(ch))
      case CAND(ch) => (ch.size, dlogs(ch))
      case _ => throw new IllegalArgumentException("The transaction does not spend from a multisig address")
    }).distinct
    require(ps.size == 1, "All inputs must come from the same multisig address")
    ps.head
  }

  private def interpreter(keys: Seq[DLogProverInput]): AppkitProvingInterpreter =
    // the parameters only price reduction, which happened when the transaction was built
    new AppkitProvingInterpreter(IndexedSeq.empty, keys.toIndexedSeq, IndexedSeq.empty,
      CBlockchainParameters(0, 0, 0, 0, 0, 0, 0, Parameters.ColdClientMaxBlockCost, None, None, Parameters.ColdClientBlockVersion))

  private def point(e: Ecp): String = Hex.toHexString(GroupElementSerializer.toBytes(e))

  private def ecp(h: String): Ecp = GroupElementSerializer.fromBytes(Hex.decode(h)).asInstanceOf[Ecp]

  private def hex(p: ProveDlog): String = point(p.value)

  private def pk(h: String): ProveDlog = ProveDlog(ecp(h))
}
