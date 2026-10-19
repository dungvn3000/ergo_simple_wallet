package vn.erg.wallet

import java.net.{URLDecoder, URLEncoder}
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.{Arrays, Base64, Locale}

import com.google.gson.{JsonObject, JsonParser}
import org.ergoplatform.ErgoBox
import org.ergoplatform.appkit._
import org.ergoplatform.appkit.impl.InputBoxImpl
import org.ergoplatform.sdk.wallet.secrets.ExtendedSecretKey
import org.ergoplatform.sdk.{ErgoToken, JavaHelpers}
import sigmastate.crypto.DLogProtocol.DLogProverInput
import sigmastate.interpreter.HintsBag

import scala.collection.JavaConverters._
import scala.collection.immutable.ListMap
import scala.util.Try
import scala.util.control.NonFatal

/** Wallet protocol logic, ported from ergo-wallet-app (common-jvm). */
object Ergo {
  /**
   * -Dergo.network=devnet: a local test chain (see devnet/): testnet addresses, its own genesis (-Dergo.genesis),
   * a separate data folder. Anything else is mainnet.
   */
  val Devnet: Boolean = sys.props.get("ergo.network").contains("devnet")
  val Net: NetworkType = if (Devnet) NetworkType.TESTNET else NetworkType.MAINNET
  private val MainnetFeeAddress = "2iHkR7CWvD1R4j1yZg5bkeDRQavjAaVPeTDFGGLZduHyfWMuYpmhHocX8GJoaieTx78FntzJbCBVL6rf96ocJoZdmWBL2fci7NqWgAirppPQmZ7fN9V6z13Ay6brPriBKYqLp1bT2Fk4FkFLCfdPpe"
  /** The miner-fee contract, encoded for the current network. */
  val FeeAddress: String = Address.fromErgoTree(Address.create(MainnetFeeAddress).getErgoAddress.script, Net).toString
  val ExplorerWeb = "https://explorer.ergoplatform.com/"

  // ---------------------------------------------------------------- keys & addresses

  /** Master key straight from the char[] mnemonic (see [[Bip39]]); caller must zeroSecret() it. */
  private def masterKey(s: Store.Secret): ExtendedSecretKey = {
    val seed = Bip39.seed(s.mnemonic)
    try ExtendedSecretKey.deriveMasterKey(seed, s.deprecatedDerivation) finally Arrays.fill(seed, 0.toByte)
  }

  def xpub(s: Store.Secret): String = {
    val m = masterKey(s)
    try Bip32Serialization.serializeExtendedPublicKeyToHex(m, Net) finally m.zeroSecret()
  }

  def address(s: Store.Secret, index: Int): String = derive(xpub(s), index)

  def derive(xpub: String, index: Int): String =
    Address.createEip3Address(index, Net, Bip32Serialization.parseExtendedPublicKeyFromHex(xpub, Net)).toString

  /** BIP44 gap-limit discovery: used indices, scanning until `gapLimit` unused ones in a row. */
  def discoverUsed(used: Int => Boolean, gapLimit: Int): Vector[Int] = {
    val out = Vector.newBuilder[Int]
    var gap = 0
    var i = 0
    while (gap < gapLimit && i < 10000) {
      if (used(i)) { out += i; gap = 0 } else gap += 1
      i += 1
    }
    out.result()
  }

  /** Discovered addresses whose index the wallet does not have yet. */
  def newAddresses(have: Seq[Store.Addr], found: Seq[Store.Addr]): Seq[Store.Addr] = {
    val known = have.map(_.index).toSet
    found.filterNot(a => known(a.index))
  }

  def isValidAddress(a: String): Boolean = Try(Address.create(a.trim).getNetworkType == Net).getOrElse(false)

  def cold: ErgoClient = new ColdErgoClient(Net, Parameters.ColdClientMaxBlockCost, Parameters.ColdClientBlockVersion)

  // createWithoutExplorer is broken in appkit 6.0.1; the explorer is never called by our code paths
  def rest(node: String): ErgoClient = {
    val c = RestApiErgoClient.create(node + "/", Net, "", RestApiErgoClient.getDefaultExplorerUrl(Net)).asInstanceOf[RestApiErgoClient]
    if (Devnet) devnetClient(c.getDataSource) else c
  }

  /**
   * appkit knows only mainnet and testnet: a devnet node reports network "devnet" and appkit refuses to build a context
   * ("Network type of NodeInfo does not match"). Same data source, its parameters relabelled with our (testnet) type.
   */
  private def devnetClient(ds: BlockchainDataSource): ErgoClient = {
    val relabelled = new BlockchainDataSource {
      def getParameters: BlockchainParameters = {
        val p = ds.getParameters
        new BlockchainParameters {
          def getNetworkType: NetworkType = Net
          def getStorageFeeFactor: Int = p.getStorageFeeFactor
          def getMinValuePerByte: Int = p.getMinValuePerByte
          def getMaxBlockSize: Int = p.getMaxBlockSize
          def getTokenAccessCost: Int = p.getTokenAccessCost
          def getInputCost: Int = p.getInputCost
          def getDataInputCost: Int = p.getDataInputCost
          def getOutputCost: Int = p.getOutputCost
          def getMaxBlockCost: Int = p.getMaxBlockCost
          def getBlockVersion: Byte = p.getBlockVersion
        }
      }
      def getLastBlockHeaders(count: Int, onlyFullHeaders: Boolean): java.util.List[BlockHeader] = ds.getLastBlockHeaders(count, onlyFullHeaders)
      def getBoxById(id: String, findInPool: Boolean, findInSpent: Boolean): InputBox = ds.getBoxById(id, findInPool, findInSpent)
      def sendTransaction(tx: SignedTransaction): String = ds.sendTransaction(tx)
      def getUnspentBoxesFor(a: Address, offset: Int, limit: Int): java.util.List[InputBox] = ds.getUnspentBoxesFor(a, offset, limit)
      def getUnconfirmedUnspentBoxesFor(a: Address, offset: Int, limit: Int): java.util.List[InputBox] =
        ds.getUnconfirmedUnspentBoxesFor(a, offset, limit)
      def getUnconfirmedTransactions(offset: Int, limit: Int): java.util.List[Transaction] = ds.getUnconfirmedTransactions(offset, limit)
    }
    new ErgoClient {
      def getDataSource: BlockchainDataSource = relabelled
      def execute[T](f: java.util.function.Function[BlockchainContext, T]): T =
        f.apply(new org.ergoplatform.appkit.impl.BlockchainContextBuilderImpl(relabelled, Net).build())
    }
  }

  /** EIP-3 keys handed to the prover as plain secrets, so appkit never sees the mnemonic. */
  private def prover(ctx: BlockchainContext, s: Store.Secret, indices: Seq[Int]): ErgoProver = {
    val b = ctx.newProverBuilder()
    dlogSecrets(s, indices).foreach(k => b.withDLogSecret(k.w))
    b.build()
  }

  /** The EIP-3 private keys at `indices`. */
  def dlogSecrets(s: Store.Secret, indices: Seq[Int]): Seq[DLogProverInput] = {
    val m = masterKey(s)
    try indices.map { i =>
      val k = m.derive(JavaHelpers.eip3DerivationWithLastIndex(i)).asInstanceOf[ExtendedSecretKey]
      try DLogProverInput(k.privateInput.w) finally k.zeroSecret()
    } finally m.zeroSecret()
  }

  // ---------------------------------------------------------------- transactions

  def tokensOf(box: ErgoBox): Map[String, Long] =
    new InputBoxImpl(box).getTokens.asScala.groupBy(_.getId.toString).map { case (id, ts) => id -> ts.map(_.getValue).sum }

  /** Picks verified boxes covering amount + fee + tokens; any change needs at least MinChangeValue. */
  def selectBoxes(boxes: Seq[ErgoBox], needErg: Long, needTokens: Map[String, Long]): Vector[ErgoBox] = {
    // boxes holding wanted tokens first, so we don't pull in unrelated token boxes
    val sorted = boxes.sortBy(b => !tokensOf(b).keySet.exists(needTokens.contains))
    var sel = Vector.empty[ErgoBox]
    var erg = 0L
    var have = Map.empty[String, Long]
    for (b <- sorted if !covered(erg, needErg, have, needTokens)) {
      val t = tokensOf(b)
      val wantsToken = t.keySet.exists(id => have.getOrElse(id, 0L) < needTokens.getOrElse(id, 0L))
      if (wantsToken || erg < needErg + Parameters.MinChangeValue) {
        sel :+= b
        erg += b.value
        t.foreach { case (id, v) => have = have.updated(id, have.getOrElse(id, 0L) + v) }
      }
    }
    if (!covered(erg, needErg, have, needTokens)) throw new IllegalStateException("Insufficient verified funds")
    sel
  }

  private def covered(erg: Long, needErg: Long, have: Map[String, Long], needTokens: Map[String, Long]): Boolean = {
    val tokensOk = needTokens.forall { case (id, v) => have.getOrElse(id, 0L) >= v }
    val leftoverTokens = have.exists { case (id, v) => v > needTokens.getOrElse(id, 0L) }
    tokensOk && (erg >= needErg + Parameters.MinChangeValue || (erg == needErg && !leftoverTokens))
  }

  /** Builds the transaction from verified boxes and returns it reduced (ready to sign offline). */
  def prepareTx(node: String, available: Seq[ErgoBox], changeAddress: String, recipient: String,
                nanoErg: Long, tokens: Map[String, Long], fee: Long): Array[Byte] = {
    val sel = selectBoxes(available, nanoErg + fee, tokens)
    val to = Address.create(recipient)
    val change = Address.create(changeAddress)
    rest(node).execute[Array[Byte]] { ctx =>
      val tb = ctx.newTxBuilder()
      val ob = tb.outBoxBuilder().value(nanoErg).contract(to.toErgoContract)
      if (tokens.nonEmpty) ob.tokens(tokens.map { case (id, v) => new ErgoToken(id, v) }.toSeq: _*)
      val utx = tb.addInputs(sel.map(b => new InputBoxImpl(b): InputBox): _*)
        .addOutputs(ob.build()).fee(fee).sendChangeTo(change).build()
      ctx.newProverBuilder().build().reduce(utx, 0).toBytes
    }
  }

  def signReduced(reducedTx: Array[Byte], s: Store.Secret, indices: Seq[Int]): Array[Byte] =
    try cold.execute[Array[Byte]](ctx => prover(ctx, s, indices).signReduced(ctx.parseReducedTransaction(reducedTx), 0).toBytes)
    catch {
      // sigma's prover asserts (an Error, not an Exception) when it holds none of the needed keys
      case e: AssertionError => throw new IllegalStateException("This wallet's keys cannot sign the transaction", e)
    }

  def submit(node: String, signedTx: Array[Byte]): String =
    rest(node).execute[String](ctx => ctx.sendTransaction(ctx.parseSignedTransaction(signedTx)).replace("\"", ""))

  def signedTxId(signedTx: Array[Byte]): String = cold.execute[String](ctx => ctx.parseSignedTransaction(signedTx).getId)

  final case class Output(address: String, value: Long, tokens: Map[String, Long])

  final case class TxSummary(inputIds: Seq[String], outputs: Seq[Output])

  def summarize(reducedTx: Array[Byte]): TxSummary = cold.execute[TxSummary] { ctx =>
    val tx = ctx.parseReducedTransaction(reducedTx)
    val outs = tx.getOutputs.asScala.map { o =>
      val tokens = o.getTokens.asScala.groupBy(_.getId.toString).map { case (id, ts) => id -> ts.map(_.getValue).sum }
      Output(Address.fromErgoTree(o.getErgoTree, Net).toString, o.getValue, tokens)
    }
    TxSummary(tx.getInputBoxesIds.asScala.toVector, outs.toVector)
  }

  /**
   * What the wallet gives up (positive) or gets (negative): its inputs minus what comes back to it.
   * `burned`: tokens of its inputs that no output carries (destroyed). `unknownInputs`: inputs that are not
   * verified boxes of the wallet, so their value is not counted.
   */
  final case class WalletEffect(erg: Long, tokens: Map[String, Long], burned: Map[String, Long], unknownInputs: Int)

  def walletEffect(s: TxSummary, myBoxes: Map[String, ErgoBox], mine: Set[String]): WalletEffect = {
    def sum(ms: Seq[Map[String, Long]]): Map[String, Long] = ms.flatten.groupBy(_._1).map { case (id, vs) => id -> vs.map(_._2).sum }
    val in = s.inputIds.flatMap(myBoxes.get)
    val back = s.outputs.filter(o => mine(o.address))
    val tokensIn = sum(in.map(tokensOf))
    val tokensBack = sum(back.map(_.tokens))
    val tokensOut = sum(s.outputs.map(_.tokens))
    WalletEffect(in.map(_.value).sum - back.map(_.value).sum,
      (tokensIn.keySet ++ tokensBack.keySet).map(id => id -> (tokensIn.getOrElse(id, 0L) - tokensBack.getOrElse(id, 0L))).toMap.filter(_._2 != 0),
      tokensIn.map { case (id, v) => id -> (v - tokensOut.getOrElse(id, 0L)) }.filter(_._2 > 0),
      s.inputIds.size - in.size)
  }

  // ---------------------------------------------------------------- payment requests (EIP-25 style links)

  val PaymentUrl = "https://explorer.ergoplatform.com/payment-request?"

  final case class PaymentRequest(address: String, nanoErg: Long, description: String, tokens: ListMap[String, String])

  def paymentRequestUrl(address: String, nanoErg: Long, description: String): String =
    PaymentUrl + "address=" + enc(address) + (if (nanoErg > 0) "&amount=" + enc(erg(nanoErg)) else "") + "&description=" + enc(description)

  def isPaymentRequest(s: String): Boolean = {
    val l = s.toLowerCase(Locale.ROOT)
    l.startsWith(PaymentUrl) || l.startsWith("ergo:") || isValidAddress(s)
  }

  def parsePaymentRequest(raw: String): Option[PaymentRequest] = {
    val s = raw.trim
    val l = s.toLowerCase(Locale.ROOT)
    val query =
      if (l.startsWith(PaymentUrl)) Some(s.substring(PaymentUrl.length))
      else if (l.startsWith("ergo:")) Some("address=" + s.substring(5).replaceFirst("^/+", "").replaceFirst("\\?", "&"))
      else None
    query match {
      case None => if (isValidAddress(s)) Some(PaymentRequest(s, 0, "", ListMap.empty)) else None
      case Some(q) =>
        val params = q.split("&").toVector.flatMap { kv =>
          kv.indexOf('=') match {
            case -1 => None
            // a stray % ("50%off") makes the decoder throw: drop that parameter, keep the link
            case eq => Try(URLDecoder.decode(kv.substring(eq + 1), StandardCharsets.UTF_8)).toOption.map(kv.substring(0, eq) -> _)
          }
        }
        val tokens = ListMap(params.collect {
          case (k, v) if k.startsWith("token-") && Try(BigDecimal(v)).isSuccess => k.substring(6) -> v
        }: _*)
        params.collectFirst { case ("address", a) => a }.map { address =>
          PaymentRequest(address,
            params.collectFirst { case ("amount", v) => Try(nanoErg(v)).toOption }.flatten.getOrElse(0L),
            params.collectFirst { case ("description", v) => v }.getOrElse(""),
            tokens)
        }
    }
  }

  // ---------------------------------------------------------------- ErgoPay (EIP-20)

  private val P2pkPlaceholder = "#P2PK_ADDRESS#"
  private val P2pkPlaceholderEnc = "#P2PK_ADDRESS%23"

  final case class ErgoPayRequest(reducedTx: Option[Array[Byte]], message: Option[String], severity: String, replyTo: Option[String])

  def isErgoPay(s: String): Boolean = s.toLowerCase(Locale.ROOT).startsWith("ergopay:")

  def ergoPayNeedsAddress(s: String): Boolean =
    s.toLowerCase(Locale.ROOT).startsWith("ergopay://") && (s.contains(P2pkPlaceholder) || s.contains(P2pkPlaceholderEnc))

  /** Static requests carry the reduced tx; dynamic ones (ergopay://) are fetched over https. */
  def fetchErgoPay(raw: String, address: Option[String]): ErgoPayRequest = {
    val uri = raw.trim
    if (!uri.toLowerCase(Locale.ROOT).startsWith("ergopay://"))
      ErgoPayRequest(Some(unb64(uri.substring("ergopay:".length))), None, "NONE", None)
    else {
      val filled =
        if (!ergoPayNeedsAddress(uri)) uri
        else {
          val a = address.getOrElse(throw new IllegalArgumentException("ErgoPay request needs an address"))
          uri.replace(P2pkPlaceholder, a).replace(P2pkPlaceholderEnc, a)
        }
      val o = JsonParser.parseString(Http.get(linkTarget(filled, "ergopay")._1)).getAsJsonObject
      val tx = str(o, "reducedTx").map(unb64)
      val msg = str(o, "message")
      if (tx.isEmpty && msg.isEmpty) throw new IllegalArgumentException("ErgoPay request has neither reducedTx nor message")
      ErgoPayRequest(tx, msg, str(o, "messageSeverity").getOrElse("NONE"), str(o, "replyTo"))
    }
  }

  def ergoPayReply(r: ErgoPayRequest, txId: String): Unit = r.replyTo.foreach { url =>
    val o = new JsonObject
    o.addProperty("txId", txId)
    Http.post(url, "application/json", o.toString)
  }

  // ---------------------------------------------------------------- ErgoAuth (EIP-28)

  final case class AuthRequest(signingMessage: String, sigma: SigmaProp, userMessage: Option[String], replyTo: String, host: String)

  def isErgoAuth(s: String): Boolean = s.toLowerCase(Locale.ROOT).startsWith("ergoauth://")

  def fetchErgoAuth(uri: String): AuthRequest = {
    val (url, host) = linkTarget(uri, "ergoauth")
    val o = JsonParser.parseString(Http.get(url)).getAsJsonObject
    val replyTo = str(o, "replyTo")
    // never post a signature anywhere but back to the requesting host
    if (!replyTo.exists(_.startsWith(host + "/"))) throw new IllegalStateException(s"ErgoAuth reply URL not on host $host")
    val sigma = str(o, "sigmaBoolean").map(b => SigmaProp.parseFromBytes(unb64(b)))
    val signing = str(o, "signingMessage")
    if (sigma.isEmpty || signing.isEmpty) throw new IllegalArgumentException("ErgoAuth request incomplete")
    AuthRequest(signing.get, sigma.get, str(o, "userMessage"), replyTo.get, host)
  }

  /** Signs prefix + signingMessage + host + suffix (random padding, as in the reference wallet) and posts it. */
  def respondErgoAuth(r: AuthRequest, s: Store.Secret, indices: Seq[Int]): Unit = {
    val signed = randomText(20) + r.signingMessage + r.host + randomText(20)
    val proof = cold.execute[Array[Byte]](ctx => prover(ctx, s, indices).signMessage(r.sigma, signed.getBytes(StandardCharsets.UTF_8), HintsBag.empty))
    val o = new JsonObject
    o.addProperty("signedMessage", signed)
    o.addProperty("proof", b64(proof))
    Http.post(r.replyTo, "application/json", o.toString)
  }

  // ---------------------------------------------------------------- explorer, prices, fees (display data, not verified)

  /** `resolved = false`: the explorer could not be reached, so decimals are unknown (amounts are raw units). */
  final case class TokenInfo(id: String, name: Option[String], description: Option[String], decimals: Int, emission: Long,
                             nftType: Option[String], contentLink: Option[String], resolved: Boolean = true) {
    /** Swing renders text starting with "<html>" as HTML; a token's creator must not load URLs or restyle the wallet. */
    def label: String = {
      val l = name.filterNot(_.trim.isEmpty).getOrElse(id.take(10) + "…")
      if (l.regionMatches(true, 0, "<html", 0, 5)) " " + l else l
    }
  }

  private val tokenCache = new ConcurrentHashMap[String, TokenInfo]

  private def unresolved(id: String) = TokenInfo(id, None, None, 0, 0, None, None, resolved = false)

  /** Cached info only, never a network call: safe on the Swing thread. */
  def cachedTokenInfo(id: String): TokenInfo = Option(tokenCache.get(id)).getOrElse(unresolved(id))

  /** Fetches from the explorer when not cached. Failures are not cached, so a passing outage cannot pin decimals to 0. */
  def tokenInfo(explorerApi: String, node: String, id: String): TokenInfo = Option(tokenCache.get(id)).getOrElse {
    val t = fetchTokenInfo(explorerApi, node, id)
    if (t.resolved) tokenCache.putIfAbsent(id, t)
    t
  }

  /** From the explorer; on the devnet (no explorer) from the node's index, which has the same token fields. */
  private def fetchTokenInfo(explorerApi: String, node: String, id: String): TokenInfo =
    try {
      val (tokens, boxes) = if (Devnet) (s"$node/blockchain/token/byId/", s"$node/blockchain/box/byId/")
        else (s"$explorerApi/api/v1/tokens/", s"$explorerApi/api/v1/boxes/")
      val t = JsonParser.parseString(Http.get(tokens + id)).getAsJsonObject
      val name = str(t, "name")
      val desc = str(t, "description")
      val dec = if (t.has("decimals") && !t.get("decimals").isJsonNull) t.get("decimals").getAsInt else 0
      val emission = t.get("emissionAmount").getAsLong
      // malformed R7+ registers: show the token without NFT data, like the reference wallet
      val nft = Try {
        val regs = JsonParser.parseString(Http.get(boxes + t.get("boxId").getAsString)).getAsJsonObject.getAsJsonObject("additionalRegisters")
        // explorer: {"serializedValue": hex, ...}; node: the hex itself
        def hex(r: String) = if (regs.get(r).isJsonPrimitive) regs.get(r).getAsString else regs.getAsJsonObject(r).get("serializedValue").getAsString
        def reg(r: String): ErgoValue[_] = if (regs.has(r)) ErgoValue.fromHex(hex(r)) else null
        if (!regs.has("R7")) None
        else {
          val e = new Eip4Token(id, emission, name.orNull, desc.orNull, dec, reg("R7"), reg("R8"), reg("R9"))
          if (e.isNftAssetType) Some((e.getAssetType.name, Option(e.getNftContentLink))) else None
        }
      }.toOption.flatten
      TokenInfo(id, name, desc, dec, emission, nft.map(_._1), nft.flatMap(_._2))
    } catch {
      case NonFatal(_) => unresolved(id)
    }

  final case class TxRow(id: String, timestamp: Long, height: Int, confirmations: Int, ergDelta: Long,
                         tokenDelta: Map[String, Long], raw: JsonObject)

  /** From the explorer; on the devnet (no explorer) from the node's own address index, which has the same fields. */
  def history(explorerApi: String, node: String, addresses: Seq[String], limit: Int): Vector[TxRow] = {
    val txs = addresses.flatMap { a =>
      val page =
        if (Devnet) Http.post(s"$node/blockchain/transaction/byAddress?offset=0&limit=$limit", "text/plain", a)
        else Http.get(s"$explorerApi/api/v1/addresses/$a/transactions?offset=0&limit=$limit")
      JsonParser.parseString(page).getAsJsonObject.getAsJsonArray("items").asScala.map(_.getAsJsonObject)
    }
    val mine = addresses.toSet
    txs.groupBy(_.get("id").getAsString).values.map(ts => txRow(ts.head, mine)).toVector.sortBy(-_.timestamp)
  }

  private def txRow(t: JsonObject, mine: Set[String]): TxRow = {
    val moves = for {
      (side, sign) <- Seq("inputs" -> -1L, "outputs" -> 1L)
      b <- t.getAsJsonArray(side).asScala.map(_.getAsJsonObject)
      if b.has("address") && mine(b.get("address").getAsString)
    } yield (sign, b)
    val erg = moves.map { case (sign, b) => sign * b.get("value").getAsLong }.sum
    val tokens = for {
      (sign, b) <- moves if b.has("assets")
      a <- b.getAsJsonArray("assets").asScala.map(_.getAsJsonObject)
    } yield a.get("tokenId").getAsString -> sign * a.get("amount").getAsLong
    val delta = tokens.groupBy(_._1).map { case (id, vs) => id -> vs.map(_._2).sum }.filter(_._2 != 0)
    TxRow(t.get("id").getAsString, t.get("timestamp").getAsLong, t.get("inclusionHeight").getAsInt,
      t.get("numConfirmations").getAsInt, erg, delta, t)
  }

  def fiatPrice(currency: String): Option[Double] = if (Devnet) None else Try { // devnet coins have no price
    val o = JsonParser.parseString(Http.get(s"https://api.coingecko.com/api/v3/simple/price?ids=ergo&vs_currencies=${enc(currency)}"))
      .getAsJsonObject.getAsJsonObject("ergo")
    if (o.has(currency)) Some(o.get(currency).getAsDouble) else None
  }.toOption.flatten

  /** Node's mempool-based fee estimate for confirmation within `minutes`. */
  def suggestedFee(node: String, minutes: Int): Long =
    math.max(Parameters.MinFee, Http.get(s"$node/transactions/getFee?waitTime=$minutes&txSize=2000").trim.toLong)

  // ---------------------------------------------------------------- utils

  def erg(nano: Long): String = java.math.BigDecimal.valueOf(nano, 9).stripTrailingZeros.toPlainString

  def nanoErg(erg: String): Long = new java.math.BigDecimal(erg.trim).movePointRight(9).longValueExact

  def tokenAmount(raw: Long, decimals: Int): String = java.math.BigDecimal.valueOf(raw, decimals).stripTrailingZeros.toPlainString

  def rawTokenAmount(amount: String, decimals: Int): Long = new java.math.BigDecimal(amount.trim).movePointRight(decimals).longValueExact

  def b64(b: Array[Byte]): String = Base64.getEncoder.encodeToString(b)

  /** Accepts standard and url-safe base64, with or without padding. */
  def unb64(s: String): Array[Byte] = {
    val clean = s.trim.replace('-', '+').replace('_', '/').replaceAll("\\s", "")
    Base64.getDecoder.decode(clean + "=" * ((4 - clean.length % 4) % 4))
  }

  def enc(s: String): String = URLEncoder.encode(s, StandardCharsets.UTF_8)

  /**
   * (URL to fetch, host shown to the user) for a scheme://host/... link. User-info before the host is refused:
   * ergopay://localhost:1@evil.com/ would pass the local check yet fetch evil.com over plain http, and
   * ergoauth://good.com@evil.com/ would display good.com while connecting to evil.com.
   */
  private[wallet] def linkTarget(uri: String, scheme: String): (String, String) = {
    val rest = uri.trim.substring(scheme.length + "://".length)
    val proto = if (isLocal("//" + rest)) "http" else "https"
    val u = java.net.URI.create(s"$proto://$rest")
    if (u.getRawUserInfo != null || u.getHost == null)
      throw new IllegalArgumentException(s"Link refused: unusual host part in ${uri.trim}")
    (u.toString, s"$proto://${u.getHost}" + (if (u.getPort >= 0) s":${u.getPort}" else ""))
  }

  private def str(o: JsonObject, k: String): Option[String] =
    if (o.has(k) && !o.get(k).isJsonNull) Some(o.get(k).getAsString) else None

  /**
   * Plain http only for this machine and private networks (dApps under development), as in the reference wallet's
   * intent; a public IP gets https, so nobody on the way can swap the transaction to sign.
   */
  private[wallet] def isLocal(schemeLessUrl: String): Boolean =
    schemeLessUrl.replaceFirst("^//", "").matches(
      "^(localhost|127(\\.\\d{1,3}){3}|10(\\.\\d{1,3}){3}|192\\.168(\\.\\d{1,3}){2}|172\\.(1[6-9]|2\\d|3[01])(\\.\\d{1,3}){2})([:/].*)?$")

  def randomText(len: Int): String = {
    val chars = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"
    val r = new SecureRandom
    Seq.fill(len)(chars.charAt(r.nextInt(chars.length))).mkString
  }
}
