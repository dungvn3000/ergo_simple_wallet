package vn.erg.wallet

import org.ergoplatform.appkit.Address
import org.ergoplatform.sdk.{ReducedErgoLikeTransaction, ReducedErgoLikeTransactionSerializer, ReducedInputData}
import org.ergoplatform.{ErgoBoxCandidate, UnsignedErgoLikeTransaction, UnsignedInput}
import org.scalatest.funsuite.AnyFunSuite
import sigma.data.{CAND, COR, CTHRESHOLD, SigmaBoolean}
import sigma.interpreter.ContextExtension
import sigmastate.crypto.DLogProtocol.DLogProverInput
import sigmastate.interpreter.Interpreter.ReductionResult

class MultisigSpec extends AnyFunSuite {
  private def secret = new Store.Secret("slow silly start wash bundle suffer bulb ancient height spin express remind today effort helmet".toCharArray, false)

  test("k-of-n address: a P2S contract over the given P2PK addresses, inputs validated") {
    val ps = (0 until 3).map(Ergo.address(secret, _))
    val a = Multisig.address(2, ps)
    assert(!Address.create(a).isP2PK && Ergo.isValidAddress(a))
    assert(Multisig.address(2, ps) == a)
    assert(Multisig.address(2, ps.reverse) != a)
    assertThrows[IllegalArgumentException](Multisig.address(4, ps))
    assertThrows[IllegalArgumentException](Multisig.address(0, ps))
    assertThrows[IllegalArgumentException](Multisig.address(1, Seq(ps(0), ps(0))))
    assertThrows[IllegalArgumentException](Multisig.address(1, Seq(ps(0), a)))
  }

  /** A reduced tx whose `inputs` inputs each need `prop` (as the node would reduce a multisig box). */
  private def session(prop: SigmaBoolean, inputs: Int): Multisig.Session = {
    val fee = Address.create(Ergo.FeeAddress).getErgoAddress.script
    val ins = (0 until inputs).map(i => new UnsignedInput(scorex.crypto.authds.ADKey @@ Array.fill(32)(i.toByte)))
    val utx = new UnsignedErgoLikeTransaction(ins.toIndexedSeq, IndexedSeq.empty, IndexedSeq(new ErgoBoxCandidate(1000000L, fee, 1)))
    val red = ReducedErgoLikeTransaction(utx, ins.map(_ => ReducedInputData(ReductionResult(prop, 0L), ContextExtension.empty)), 0)
    Multisig.fromJson(Multisig.toJson(Multisig.start(ReducedErgoLikeTransactionSerializer.toBytes(red))))
  }

  /** Runs both rounds with `signers`, passing the session through JSON between every step as the file would. */
  private def run(s0: Multisig.Session, signers: Seq[DLogProverInput]): Multisig.Session = {
    var s = s0
    val nonces = signers.map { k =>
      val (next, ns) = Multisig.commit(s, Seq(k.publicImage))
      s = Multisig.fromJson(Multisig.toJson(next))
      Multisig.noncesFromBytes(Multisig.noncesToBytes(ns))
    }
    signers.zip(nonces).foreach { case (k, ns) =>
      assert(!Multisig.complete(s))
      s = Multisig.fromJson(Multisig.toJson(Multisig.sign(s, Seq(k), ns)))
    }
    s
  }

  test("distributed signing: 2-of-3, 3-of-4, 1-of-2 (OR), 2-of-2 (AND), over several inputs") {
    def keys(n: Int) = Vector.fill(n)(DLogProverInput.random())
    val k3 = keys(3)
    val k4 = keys(4)
    val k2 = keys(2)
    val cases = Seq(
      (CTHRESHOLD(2, k3.map(_.publicImage)), Seq(k3(2), k3(0))),
      (CTHRESHOLD(3, k4.map(_.publicImage)), Seq(k4(1), k4(3), k4(0))),
      (COR(k2.map(_.publicImage)), Seq(k2(1))),
      (CAND(k2.map(_.publicImage)), Seq(k2(0), k2(1))))
    for ((prop, signers) <- cases) {
      val s = run(session(prop, 2), signers)
      assert(Multisig.complete(s), Multisig.status(s))
      assert(Ergo.signedTxId(Multisig.signedTx(s)) == Multisig.id(s))
    }
  }

  test("one wallet holding two of the signer keys commits and signs for both in one step each") {
    val ks = Vector.fill(3)(DLogProverInput.random())
    val (s1, ns) = Multisig.commit(session(CTHRESHOLD(2, ks.map(_.publicImage)), 2), ks.take(2).map(_.publicImage))
    assert(Multisig.committed(s1).size == 2)
    val s2 = Multisig.sign(s1, ks.take(2), ns)
    assert(Multisig.complete(s2))
    assert(Ergo.signedTxId(Multisig.signedTx(s2)) == Multisig.id(s2))
  }

  test("signing needs the file's condition to be exactly the known multisig and every input a verified box of it") {
    val ps = (0 until 3).map(Ergo.address(secret, _))
    val pks = ps.map(a => Address.create(a).getPublicKey)
    val s = session(CTHRESHOLD(2, pks), 2)
    Multisig.checkInputs(s, 2, ps, Set("00" * 32, "01" * 32))
    assert(Multisig.matches(s, 2, ps) && !Multisig.matches(s, 2, ps.reverse) && !Multisig.matches(s, 3, ps))
    assertThrows[IllegalArgumentException](Multisig.checkInputs(s, 2, ps, Set("00" * 32))) // one input not verified
    // a forged file claiming a threshold over an attacker's keys for our boxes
    val forged = session(CTHRESHOLD(2, pks.take(1) ++ Seq(DLogProverInput.random().publicImage, DLogProverInput.random().publicImage)), 2)
    assertThrows[IllegalArgumentException](Multisig.checkInputs(forged, 2, ps, Set("00" * 32, "01" * 32)))
  }

  test("refuses: extra commitments, early signing, a nonce that does not match the file, a tampered proof") {
    val ks = Vector.fill(3)(DLogProverInput.random())
    val s0 = session(CTHRESHOLD(2, ks.map(_.publicImage)), 1)
    val outsider = DLogProverInput.random()
    assertThrows[IllegalArgumentException](Multisig.commit(s0, Seq(outsider.publicImage)))
    val (s1, n0) = Multisig.commit(s0, Seq(ks(0).publicImage))
    assertThrows[IllegalArgumentException](Multisig.commit(s1, Seq(ks(0).publicImage))) // already committed
    assertThrows[IllegalArgumentException](Multisig.sign(s1, Seq(ks(0)), n0)) // only 1 of 2 commitments
    val (s2, n1) = Multisig.commit(s1, Seq(ks(1).publicImage))
    assertThrows[IllegalArgumentException](Multisig.commit(s2, Seq(ks(2).publicImage))) // 2 of 2 already
    val (_, other) = Multisig.commit(s0, Seq(ks(0).publicImage)) // a nonce for another session state
    assertThrows[IllegalArgumentException](Multisig.sign(s2, Seq(ks(0)), other))
    val s3 = Multisig.sign(s2, Seq(ks(0)), n0)
    assertThrows[IllegalArgumentException](Multisig.sign(s3, Seq(ks(0)), n0)) // already signed
    val s4 = Multisig.sign(s3, Seq(ks(1)), n1)
    val bad = s4.proofs.head.clone()
    bad(bad.length - 1) = (bad(bad.length - 1) ^ 1).toByte
    assertThrows[IllegalStateException](Multisig.signedTx(s4.copy(proofs = Vector(bad))))
    Multisig.signedTx(s4)
  }
}
