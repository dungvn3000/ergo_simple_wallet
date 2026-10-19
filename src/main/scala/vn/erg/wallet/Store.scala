package vn.erg.wallet

import java.io.IOException
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.channels.{FileChannel, FileLock, OverlappingFileLockException}
import java.nio.file.{Files, Path, Paths, StandardCopyOption, StandardOpenOption}
import java.nio.{ByteBuffer, CharBuffer}
import java.security.{GeneralSecurityException, MessageDigest, SecureRandom}
import java.util.Arrays
import javax.crypto.spec.{GCMParameterSpec, SecretKeySpec}
import javax.crypto.{AEADBadTagException, Cipher}

import com.nulabinc.zxcvbn.Zxcvbn
import io.circe.syntax._
import io.circe.{Decoder, Encoder, Json, Printer}
import org.bouncycastle.crypto.PasswordConverter
import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters

import scala.collection.mutable.ArrayBuffer

/**
 * Wallets, address book and settings in ~/.ergo-simple-wallet/data.json, encrypted as a whole with one password
 * (Argon2id -> AES-256-GCM). Inside, each mnemonic is sealed again with the same key, so it stays encrypted in
 * memory until a signature needs it.
 */
final class Store private (file: Path, val data: Store.Data) {
  import Store._

  private var mem = ArgonMemoryKiB
  private var iterations = ArgonIterations
  private var parallelism = ArgonParallelism
  private var salt: Array[Byte] = _
  private var key: Array[Byte] = _ // AES key, held while unlocked (re-saving and opening mnemonics need it); zeroed by lock()

  // ---------------------------------------------------------------- lock

  def locked: Boolean = key == null

  /** Zeroes the key; the wallet data stays loaded but nothing can be saved or signed until [[reunlock]]. */
  def lock(): Unit = synchronized {
    if (key != null) Arrays.fill(key, 0.toByte)
    key = null
  }

  /** Re-derives the key by decrypting the file on disk. Throws IllegalArgumentException on a wrong password. */
  def reunlock(password: Array[Char]): Unit = synchronized {
    key = read(file, password).key
  }

  private def currentKey: Array[Byte] = {
    if (key == null) throw new IllegalStateException("Wallet is locked")
    key
  }

  // ---------------------------------------------------------------- backup & restore

  /** Copies the (already encrypted) wallet file to `target`; the backup opens with the current password. */
  def backup(target: Path): Unit = synchronized {
    save()
    try {
      Files.copy(file, target, StandardCopyOption.REPLACE_EXISTING)
      restrict(target, "rw-------")
    } catch {
      case e: IOException => throw new IllegalStateException(s"Cannot write backup: ${e.getMessage}", e)
    }
  }

  /**
   * Makes this (opened backup) the wallet file in `dir`, keeping the backup's password.
   * The file being replaced is kept as data.json.before-restore.
   */
  def restoreInto(dir: Path): Store = {
    val target = dir.resolve("data.json")
    try {
      if (Files.exists(target)) Files.copy(target, dir.resolve("data.json.before-restore"), StandardCopyOption.REPLACE_EXISTING)
    } catch {
      case e: IOException => throw new IllegalStateException(s"Cannot keep the current wallet file: ${e.getMessage}", e)
    }
    val s = new Store(target, data)
    s.adopt(mem, iterations, parallelism, salt, key)
    key = null // moved, not copied: this backup object must not keep a live key
    s.save()
    s
  }

  private def adopt(m: Int, t: Int, p: Int, salt: Array[Byte], key: Array[Byte]): Unit = {
    mem = m
    iterations = t
    parallelism = p
    this.salt = salt
    this.key = key
  }

  def save(): Unit = synchronized {
    try {
      Files.createDirectories(file.getParent)
      restrict(file.getParent, "rwx------")
      val iv = random(12)
      val header = ByteBuffer.allocate(Header).putInt(Magic).putInt(mem).putInt(iterations).putInt(parallelism).put(salt).put(iv).array()
      val json = JsonPrinter.print(data.asJson).getBytes(StandardCharsets.UTF_8)
      val enc = aes(Cipher.ENCRYPT_MODE, currentKey, iv, header, json, 0, json.length)
      val out = Json.obj("encrypted" -> Json.fromString(Ergo.b64(header ++ enc))).noSpaces

      val tmp = file.resolveSibling("data.json.tmp")
      Files.deleteIfExists(tmp)
      // owner-only from creation, so the file is never readable by others, not even briefly
      if (posix(tmp)) Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")))
      Files.write(tmp, out.getBytes(StandardCharsets.UTF_8))
      // atomic replace so a crash never leaves a half-written wallet file
      Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    } catch {
      case e: IOException => throw new IllegalStateException(s"Cannot save wallets: ${e.getMessage}", e)
    }
  }

  def checkPassword(password: Array[Char]): Boolean = {
    val k = argon2(password, salt, mem, iterations, parallelism)
    try MessageDigest.isEqual(k, currentKey) finally Arrays.fill(k, 0.toByte)
  }

  /**
   * New salt and key; every sealed mnemonic is re-encrypted, then the file is saved. If saving fails, memory goes back
   * to the old key: otherwise the next successful save would switch the file to a password the user thinks was refused.
   */
  def changePassword(newPassword: Array[Char]): Unit = synchronized {
    val (oldMem, oldIterations, oldParallelism, oldSalt, oldKey) = (mem, iterations, parallelism, salt, currentKey.clone())
    val oldSecrets = data.wallets.map(_.secret)
    val plain = data.wallets.map(w => open(w.secret))
    rekey(newPassword)
    data.wallets.zip(plain).foreach { case (w, p) => w.secret = sealSecret(p) }
    try {
      save()
      Arrays.fill(oldKey, 0.toByte)
    } catch {
      case e: IllegalStateException =>
        Arrays.fill(key, 0.toByte)
        adopt(oldMem, oldIterations, oldParallelism, oldSalt, oldKey)
        data.wallets.zip(oldSecrets).foreach { case (w, s) => w.secret = s }
        throw e
    }
  }

  private def rekey(password: Array[Char]): Unit = {
    if (key != null) Arrays.fill(key, 0.toByte)
    adopt(ArgonMemoryKiB, ArgonIterations, ArgonParallelism, random(16), null)
    key = argon2(password, salt, mem, iterations, parallelism)
  }

  // ---------------------------------------------------------------- sealed mnemonics

  /** Encrypts secret bytes with the file key and zeroes them. */
  def sealSecret(plain: Array[Byte]): String = {
    val iv = random(12)
    try Ergo.b64(iv ++ aes(Cipher.ENCRYPT_MODE, currentKey, iv, null, plain, 0, plain.length))
    finally Arrays.fill(plain, 0.toByte)
  }

  /** The wallet's mnemonic as a wipeable Secret; close it right after use (see [[Secret.use]]). */
  def openSecret(w: Wallet): Secret = parseSecret(open(w.secret))

  private def open(sealedSecret: String): Array[Byte] = {
    val b = Ergo.unb64(sealedSecret)
    aes(Cipher.DECRYPT_MODE, currentKey, b.take(12), null, b, 12, b.length - 12)
  }
}

object Store {
  // ---------------------------------------------------------------- model (field names are the data.json format)

  val DefaultNodes: Vector[String] =
    if (Ergo.Devnet) Vector("http://127.0.0.1:9053") else Vector("https://sv1.erg.vn", "https://sv2.erg.vn", "https://node.ergo.watch")

  final class Settings(var nodes: Vector[String] = DefaultNodes, var explorerApi: String = "https://api.ergoplatform.com",
                       var fiat: String = "usd")

  final class Addr(val index: Int, val address: String, var label: Option[String] = None)

  final class Wallet(var name: String, val firstAddress: String, var secret: String, val xpub: String,
                     val addresses: ArrayBuffer[Addr] = ArrayBuffer.empty) {
    override def toString: String = name
  }

  final case class Contact(label: String, address: String)

  final class Data(var settings: Settings = new Settings, val wallets: ArrayBuffer[Wallet] = ArrayBuffer.empty,
                   val contacts: ArrayBuffer[Contact] = ArrayBuffer.empty)

  private implicit val settingsEnc: Encoder[Settings] = Encoder.forProduct3("nodes", "explorerApi", "fiat")(s => (s.nodes, s.explorerApi, s.fiat))
  private implicit val settingsDec: Decoder[Settings] = Decoder.forProduct3("nodes", "explorerApi", "fiat")(
    (n: Vector[String], e: String, f: String) => new Settings(n, e, f))
  private implicit val addrEnc: Encoder[Addr] = Encoder.forProduct3("index", "address", "label")(a => (a.index, a.address, a.label))
  private implicit val addrDec: Decoder[Addr] = Decoder.forProduct3("index", "address", "label")(
    (i: Int, a: String, l: Option[String]) => new Addr(i, a, l))
  private implicit val walletEnc: Encoder[Wallet] = Encoder.forProduct5("name", "firstAddress", "secret", "xpub", "addresses")(
    w => (w.name, w.firstAddress, w.secret, w.xpub, w.addresses.toVector))
  private implicit val walletDec: Decoder[Wallet] = Decoder.forProduct5("name", "firstAddress", "secret", "xpub", "addresses")(
    (n: String, f: String, s: String, x: String, a: Vector[Addr]) => new Wallet(n, f, s, x, ArrayBuffer(a: _*)))
  private implicit val contactEnc: Encoder[Contact] = Encoder.forProduct2("label", "address")(c => (c.label, c.address))
  private implicit val contactDec: Decoder[Contact] = Decoder.forProduct2("label", "address")(Contact.apply)
  private implicit val dataEnc: Encoder[Data] = Encoder.forProduct3("settings", "wallets", "contacts")(
    d => (d.settings, d.wallets.toVector, d.contacts.toVector))
  private implicit val dataDec: Decoder[Data] = Decoder.forProduct3("settings", "wallets", "contacts")(
    (s: Settings, w: Vector[Wallet], c: Vector[Contact]) => new Data(s, ArrayBuffer(w: _*), ArrayBuffer(c: _*)))
  private val JsonPrinter = Printer.noSpaces.copy(dropNullValues = true)

  // ---------------------------------------------------------------- file format

  // File: {"encrypted": base64(MAGIC | memoryKiB | iterations | parallelism | salt(16) | iv(12) | AES-256-GCM(json))}.
  // The header is GCM associated data, so editing the KDF parameters breaks decryption.
  private val Magic = 0x41524732 // "ARG2"
  val ArgonMemoryKiB: Int = 64 * 1024
  val ArgonIterations = 3
  val ArgonParallelism = 1
  private val Header = 4 * 4 + 16 + 12

  /** Devnet wallets live apart, so test keys and settings never mix with real ones. */
  def defaultDir: Path = Paths.get(System.getProperty("user.home"), ".ergo-simple-wallet" + (if (Ergo.Devnet) "-devnet" else ""))

  def exists(dir: Path): Boolean = Files.exists(dir.resolve("data.json"))

  private var folderLock: FileLock = _ // held until the JVM exits

  /**
   * False if another app instance has `dir` open: each saves its whole in-memory copy, so the second one would
   * overwrite (and lose) wallets the first one added.
   */
  def lockFolder(dir: Path): Boolean = synchronized {
    try {
      Files.createDirectories(dir)
      restrict(dir, "rwx------")
      val ch = FileChannel.open(dir.resolve("data.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE)
      val lock = try ch.tryLock() catch { case _: OverlappingFileLockException => null }
      if (lock == null) ch.close() else folderLock = lock
      lock != null
    } catch {
      case e: IOException => throw new IllegalStateException(s"Cannot open $dir: ${e.getMessage}", e)
    }
  }

  def create(dir: Path, password: Array[Char]): Store = {
    val s = new Store(dir.resolve("data.json"), new Data)
    s.rekey(password)
    s.save()
    s
  }

  /**
   * Throws IllegalArgumentException on a wrong password, IllegalStateException if the file is not a wallet file
   * of this version.
   */
  def unlock(dir: Path, password: Array[Char]): Store = {
    try {
      restrict(dir, "rwx------")
      restrict(dir.resolve("data.json"), "rw-------") // tighten files created by older versions
    } catch {
      case e: IOException => throw new IllegalStateException(e.getMessage, e)
    }
    read(dir.resolve("data.json"), password)
  }

  /** Decrypts a backup for preview; nothing is written until [[Store.restoreInto]]. */
  def openBackup(backup: Path, password: Array[Char]): Store = read(backup, password)

  private def read(file: Path, password: Array[Char]): Store = {
    val blob = try {
      val text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8)
      Ergo.unb64(io.circe.parser.parse(text).flatMap(_.hcursor.get[String]("encrypted")).fold(e => throw e, identity))
    } catch {
      case e: IOException => throw new IllegalStateException(s"Cannot read $file: ${e.getMessage}", e)
      case _: Exception => throw new IllegalStateException(s"$file is not an encrypted wallet file (or was written by an older version).\n" +
        "Move it away, then restore your wallets from their mnemonics.")
    }
    val b = ByteBuffer.wrap(blob)
    if (blob.length < Header + 16 || b.getInt != Magic) throw new IllegalStateException(s"$file is not an encrypted wallet file")
    val (m, t, p) = (b.getInt, b.getInt, b.getInt)
    checkParams(m, t, p)
    val salt = new Array[Byte](16)
    val iv = new Array[Byte](12)
    b.get(salt).get(iv)
    val key = argon2(password, salt, m, t, p)
    val plain = try aes(Cipher.DECRYPT_MODE, key, iv, blob.take(Header), blob, Header, blob.length - Header) catch {
      case e: IllegalArgumentException =>
        Arrays.fill(key, 0.toByte)
        throw e
    }
    // decoded straight from the byte buffer, never as one String
    val d = io.circe.jawn.decodeByteBuffer[Data](ByteBuffer.wrap(plain)).fold(e => throw new IllegalStateException(s"Corrupt wallet file: $e"), identity)
    Arrays.fill(plain, 0.toByte)
    val s = new Store(file, d)
    s.adopt(m, t, p, salt, key)
    s
  }

  // ---------------------------------------------------------------- password policy

  val MinPassword = 12
  private val zxcvbn = new Zxcvbn

  /** zxcvbn score 0..4 plus the reason a new password is refused (None if acceptable: 12+ characters, score >= 3). */
  final case class Strength(score: Int, problem: Option[String])

  /** Measures common passwords, names and keyboard patterns. The caller's array is left untouched. */
  def strength(password: Array[Char]): Strength = {
    // zxcvbn's wipe() overwrites the CharSequence it measured: give it a copy, never the caller's array
    val copy = password.clone()
    val st = zxcvbn.measure(CharBuffer.wrap(copy))
    try {
      val warning = Option(st.getFeedback.getWarning).filterNot(_.trim.isEmpty)
      val problem =
        if (password.length < MinPassword) Some(s"Use at least $MinPassword characters")
        else if (st.getScore < 3) Some("Too easy to guess" + warning.fold("")(w => s": $w"))
        else None
      Strength(st.getScore, problem)
    } finally {
      st.wipe()
      Arrays.fill(copy, '\u0000')
    }
  }

  // ---------------------------------------------------------------- secrets

  /** Prefix byte 9 marks the current (non pre-1627) key derivation. */
  def secretBytes(mnemonic: Array[Char], deprecatedDerivation: Boolean): Array[Byte] = {
    val bb = StandardCharsets.UTF_8.encode(CharBuffer.wrap(mnemonic))
    val off = if (deprecatedDerivation) 0 else 1
    val out = new Array[Byte](bb.remaining + off)
    if (off == 1) out(0) = 9
    bb.get(out, off, bb.remaining)
    Arrays.fill(bb.array(), 0.toByte)
    out
  }

  /** Decrypted mnemonic as a wipeable Array[Char]; zeroed by close(). */
  final class Secret(val mnemonic: Array[Char], val deprecatedDerivation: Boolean) extends AutoCloseable {
    override def close(): Unit = Arrays.fill(mnemonic, '\u0000')

    /** Runs `f` and wipes the mnemonic afterwards, whatever happens. */
    def use[T](f: Secret => T): T = try f(this) finally close()
  }

  /** Parses decrypted bytes and zeroes them. */
  def parseSecret(b: Array[Byte]): Secret = {
    val off = if (b.nonEmpty && b(0) == 9) 1 else 0
    val cb = StandardCharsets.UTF_8.decode(ByteBuffer.wrap(b, off, b.length - off))
    val m = Arrays.copyOfRange(cb.array(), cb.position(), cb.limit())
    Arrays.fill(cb.array(), '\u0000')
    Arrays.fill(b, 0.toByte)
    new Secret(m, off == 0)
  }

  // ---------------------------------------------------------------- crypto & files

  /** Bounds keep a tampered file from asking for absurd memory/time before the GCM tag is checked. */
  private def checkParams(m: Int, t: Int, p: Int): Unit =
    if (m < 8 * 1024 || m > 1024 * 1024 || t < 1 || t > 20 || p < 1 || p > 16) throw new IllegalArgumentException("Invalid Argon2 parameters")

  private def random(n: Int): Array[Byte] = {
    val b = new Array[Byte](n)
    new SecureRandom().nextBytes(b)
    b
  }

  private def aes(mode: Int, key: Array[Byte], iv: Array[Byte], aad: Array[Byte], data: Array[Byte], off: Int, len: Int): Array[Byte] =
    try {
      val c = Cipher.getInstance("AES/GCM/NoPadding")
      // shortcut: SecretKeySpec copies the key and cannot be destroyed; the copy is short-lived garbage
      c.init(mode, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv))
      if (aad != null) c.updateAAD(aad)
      c.doFinal(data, off, len)
    } catch {
      case _: AEADBadTagException => throw new IllegalArgumentException("Wrong password")
      case e: GeneralSecurityException => throw new IllegalStateException(e)
    }

  private def argon2(password: Array[Char], salt: Array[Byte], memKiB: Int, iterations: Int, parallelism: Int): Array[Byte] = {
    val g = new Argon2BytesGenerator
    g.init(new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id).withVersion(Argon2Parameters.ARGON2_VERSION_13)
      .withSalt(salt).withMemoryAsKB(memKiB).withIterations(iterations).withParallelism(parallelism)
      .withCharToByteConverter(PasswordConverter.UTF8).build())
    val key = new Array[Byte](32)
    g.generateBytes(password, key)
    key
  }

  // Windows has no POSIX permissions; files there stay under the user's profile ACLs
  private def posix(p: Path): Boolean = p.getFileSystem.supportedFileAttributeViews.contains("posix")

  private def restrict(p: Path, perms: String): Unit =
    if (posix(p) && Files.exists(p)) Files.setPosixFilePermissions(p, PosixFilePermissions.fromString(perms))
}
