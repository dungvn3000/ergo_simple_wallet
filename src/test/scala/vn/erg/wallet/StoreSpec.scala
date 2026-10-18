package vn.erg.wallet

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.nio.file.attribute.PosixFilePermissions
import java.nio.file.{Files, Path, Paths, StandardCopyOption}

import com.google.gson.JsonParser
import org.scalatest.funsuite.AnyFunSuite

import scala.collection.mutable.ArrayBuffer

class StoreSpec extends AnyFunSuite {
  private val Mnemonic = "slow silly start wash bundle suffer bulb ancient height spin express remind today effort helmet"
  private val Pass = "correct horse battery"

  private def tempDir(): Path = Files.createTempDirectory("store-spec")

  private def perms(p: Path): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(p))

  private def fileText(dir: Path): String = new String(Files.readAllBytes(dir.resolve("data.json")), StandardCharsets.UTF_8)

  /** Edits the decoded {"encrypted": ...} blob of a wallet file in place. */
  private def tamper(dir: Path)(f: Array[Byte] => Array[Byte]): Unit = {
    val o = JsonParser.parseString(fileText(dir)).getAsJsonObject
    o.addProperty("encrypted", Ergo.b64(f(Ergo.unb64(o.get("encrypted").getAsString))))
    Files.write(dir.resolve("data.json"), o.toString.getBytes(StandardCharsets.UTF_8))
  }

  private def newWallet(st: Store, name: String): Store.Wallet =
    new Store.Wallet(name, "addr-0", st.sealSecret(Store.secretBytes(Mnemonic.toCharArray, false)), "xpub-hex",
      ArrayBuffer(new Store.Addr(0, "addr-0")))

  private def storeWithWallet(): (Path, Store, Store.Wallet) = {
    val dir = tempDir()
    val st = Store.create(dir, Pass.toCharArray)
    val w = newWallet(st, "My savings")
    st.data.wallets += w
    st.save()
    (dir, st, w)
  }

  // ---------------------------------------------------------------- file on disk

  test("create writes an owner-only, fully encrypted file and nothing else") {
    val (dir, st, w) = storeWithWallet()
    st.data.contacts += Store.Contact("Alice", "addr-alice")
    st.save()
    assert(Store.exists(dir))
    assert(perms(dir.resolve("data.json")) == "rw-------")
    assert(perms(dir) == "rwx------")
    assert(!Files.exists(dir.resolve("data.json.tmp")), "temp file is moved into place")
    val text = fileText(dir)
    for (leak <- Seq("My savings", "Alice", "addr-0", "addr-alice", "xpub-hex", "slow", "wallets", "settings"))
      assert(!text.contains(leak), s"plaintext in file: $leak")
    assert(JsonParser.parseString(text).getAsJsonObject.keySet.toString == "[encrypted]")
  }

  test("everything round-trips: settings, wallets, addresses with and without labels, contacts") {
    val dir = tempDir()
    val st = Store.create(dir, Pass.toCharArray)
    val w = newWallet(st, "w")
    w.addresses += new Store.Addr(3, "addr-3", Some("savings"))
    st.data.wallets += w
    st.data.contacts += Store.Contact("Bob", "addr-bob")
    st.data.settings.fiat = "eur"
    st.data.settings.nodes = Vector("https://node.example")
    st.save()

    val back = Store.unlock(dir, Pass.toCharArray).data
    val bw = back.wallets.head
    assert((bw.name, bw.firstAddress, bw.xpub) == ("w", "addr-0", "xpub-hex"))
    assert(bw.addresses.map(a => (a.index, a.address, a.label)) == ArrayBuffer((0, "addr-0", None), (3, "addr-3", Some("savings"))))
    assert(back.contacts == ArrayBuffer(Store.Contact("Bob", "addr-bob")))
    assert(back.settings.fiat == "eur" && back.settings.nodes == Vector("https://node.example"))
  }

  test("a fresh store has the default settings and no wallets") {
    val dir = tempDir()
    Store.create(dir, Pass.toCharArray)
    val d = Store.unlock(dir, Pass.toCharArray).data
    assert(d.wallets.isEmpty && d.contacts.isEmpty)
    assert(d.settings.nodes == Store.DefaultNodes && d.settings.fiat == "usd" && d.settings.explorerApi == "https://api.ergoplatform.com")
  }

  test("the default folder is ~/.ergo-simple-wallet") {
    assert(Store.defaultDir == Paths.get(System.getProperty("user.home"), ".ergo-simple-wallet"))
  }

  test("a world-readable file and folder left by an older version are tightened on unlock") {
    val (dir, _, _) = storeWithWallet()
    Files.setPosixFilePermissions(dir.resolve("data.json"), PosixFilePermissions.fromString("rw-r--r--"))
    Files.setPosixFilePermissions(dir, PosixFilePermissions.fromString("rwxr-xr-x"))
    Store.unlock(dir, Pass.toCharArray)
    assert(perms(dir.resolve("data.json")) == "rw-------")
    assert(perms(dir) == "rwx------")
  }

  // ---------------------------------------------------------------- passwords

  test("a wrong password is refused, the right one opens") {
    val (dir, _, _) = storeWithWallet()
    val e = intercept[IllegalArgumentException](Store.unlock(dir, "wrong password!".toCharArray))
    assert(e.getMessage == "Wrong password")
    assert(Store.unlock(dir, Pass.toCharArray).data.wallets.head.name == "My savings")
  }

  test("checkPassword compares against the current key") {
    val (_, st, _) = storeWithWallet()
    assert(st.checkPassword(Pass.toCharArray))
    assert(!st.checkPassword("not the password".toCharArray))
  }

  test("changePassword re-seals every mnemonic under the new key") {
    val (dir, st, _) = storeWithWallet()
    st.data.wallets += newWallet(st, "second")
    st.save()
    st.changePassword("a brand new passphrase".toCharArray)
    assert(st.checkPassword("a brand new passphrase".toCharArray) && !st.checkPassword(Pass.toCharArray))
    assertThrows[IllegalArgumentException](Store.unlock(dir, Pass.toCharArray))
    val reopened = Store.unlock(dir, "a brand new passphrase".toCharArray)
    for (w <- reopened.data.wallets) reopened.openSecret(w).use(s => assert(new String(s.mnemonic) == Mnemonic, w.name))
  }

  test("if saving fails, changePassword leaves the old password in force (memory and file agree)") {
    val (dir, st, w) = storeWithWallet()
    val blocker = dir.resolve("data.json.tmp")
    Files.createDirectories(blocker.resolve("x")) // a non-empty directory where the temp file must go: save() fails
    intercept[IllegalStateException](st.changePassword("a brand new passphrase".toCharArray))
    assert(st.checkPassword(Pass.toCharArray), "the old key is back in memory")
    st.openSecret(w).use(s => assert(new String(s.mnemonic) == Mnemonic, "the old sealed mnemonic is back"))
    Files.delete(blocker.resolve("x"))
    Files.delete(blocker)
    st.save() // a later save must still write with the old password
    assert(Store.unlock(dir, Pass.toCharArray).data.wallets.head.name == "My savings")
    assertThrows[IllegalArgumentException](Store.unlock(dir, "a brand new passphrase".toCharArray))
  }

  // ---------------------------------------------------------------- tampering and broken files

  test("weakening the Argon2 parameters in the header breaks decryption (header is authenticated)") {
    val (dir, _, _) = storeWithWallet()
    tamper(dir) { b => ByteBuffer.wrap(b).putInt(8, 1); b } // iterations 3 -> 1
    assertThrows[IllegalArgumentException](Store.unlock(dir, Pass.toCharArray))
  }

  test("a flipped ciphertext byte is detected") {
    val (dir, _, _) = storeWithWallet()
    tamper(dir) { b => b(b.length - 1) = (b(b.length - 1) ^ 1).toByte; b }
    assertThrows[IllegalArgumentException](Store.unlock(dir, Pass.toCharArray))
  }

  test("absurd Argon2 parameters are refused before any work is done") {
    val (dir, _, _) = storeWithWallet()
    tamper(dir) { b => ByteBuffer.wrap(b).putInt(4, Int.MaxValue); b } // memory: 2 TiB
    val t = System.nanoTime
    val e = intercept[IllegalArgumentException](Store.unlock(dir, Pass.toCharArray))
    assert(e.getMessage == "Invalid Argon2 parameters")
    assert((System.nanoTime - t) / 1000000 < 1000, "no key derivation attempted")
  }

  test("files that are not wallet files of this version give a clear error, not a crash") {
    val dir = tempDir()
    val file = dir.resolve("data.json")
    def refused(content: String): String = {
      Files.write(file, content.getBytes(StandardCharsets.UTF_8))
      intercept[IllegalStateException](Store.unlock(dir, Pass.toCharArray)).getMessage
    }
    assert(refused("""{"wallets":[]}""").contains("not an encrypted wallet file")) // older plaintext format
    assert(refused("not json at all").contains("not an encrypted wallet file"))
    assert(refused("""{"encrypted":"AAAA"}""").contains("not an encrypted wallet file")) // too short / wrong magic
    Files.delete(file)
    assert(intercept[IllegalStateException](Store.unlock(dir, Pass.toCharArray)).getMessage.contains("Cannot read"))
  }

  // ---------------------------------------------------------------- sealed mnemonics

  test("sealSecret zeroes its input and uses a fresh IV each time") {
    val (_, st, _) = storeWithWallet()
    val plain = Store.secretBytes(Mnemonic.toCharArray, false)
    val copy = plain.clone()
    val a = st.sealSecret(plain)
    assert(plain.forall(_ == 0), "plaintext wiped")
    val b = st.sealSecret(copy)
    assert(a != b, "same plaintext, different ciphertext")
  }

  test("openSecret returns the mnemonic; use() wipes it even when the work fails") {
    val (_, st, w) = storeWithWallet()
    val s = st.openSecret(w)
    assert(new String(s.mnemonic) == Mnemonic && !s.deprecatedDerivation)
    intercept[RuntimeException](s.use(_ => throw new RuntimeException("signing failed")))
    assert(s.mnemonic.forall(_ == '\u0000'))
  }

  test("secretBytes / parseSecret keep the derivation flag and parseSecret wipes its input") {
    for (deprecated <- Seq(false, true)) {
      val bytes = Store.secretBytes(Mnemonic.toCharArray, deprecated)
      assert((bytes(0) == 9) == !deprecated, "prefix byte 9 marks the current derivation")
      val s = Store.parseSecret(bytes)
      assert(new String(s.mnemonic) == Mnemonic && s.deprecatedDerivation == deprecated)
      assert(bytes.forall(_ == 0), "decrypted bytes wiped")
    }
  }

  // ---------------------------------------------------------------- lock

  test("a locked store can neither save, sign nor check passwords until reunlocked") {
    val (_, st, w) = storeWithWallet()
    st.lock()
    assert(st.locked)
    assertThrows[IllegalStateException](st.openSecret(w))
    assertThrows[IllegalStateException](st.save())
    assertThrows[IllegalStateException](st.checkPassword(Pass.toCharArray))
    assertThrows[IllegalStateException](st.sealSecret(Array[Byte](1)))
    assertThrows[IllegalArgumentException](st.reunlock("wrong password!".toCharArray))
    assert(st.locked)
    st.reunlock(Pass.toCharArray)
    assert(!st.locked)
    st.openSecret(w).use(s => assert(new String(s.mnemonic) == Mnemonic))
    st.save()
  }

  // ---------------------------------------------------------------- backup & restore

  test("backup is owner-only and opens only with its own password") {
    val (_, st, _) = storeWithWallet()
    val target = tempDir().resolve("backup.json")
    st.backup(target)
    assert(perms(target) == "rw-------")
    assertThrows[IllegalArgumentException](Store.openBackup(target, "another password".toCharArray))
    assert(Store.openBackup(target, Pass.toCharArray).data.wallets.head.name == "My savings")
    assertThrows[IllegalStateException](Store.openBackup(target.resolveSibling("missing.json"), Pass.toCharArray))
  }

  test("restoreInto replaces the wallet file, keeps the old one, and moves the key out of the backup object") {
    val (_, src, _) = storeWithWallet()
    val backupFile = tempDir().resolve("backup.json")
    src.backup(backupFile)

    val dir = tempDir()
    val current = Store.create(dir, "the current password".toCharArray)
    current.data.contacts += Store.Contact("kept", "addr")
    current.save()

    val preview = Store.openBackup(backupFile, Pass.toCharArray)
    val restored = preview.restoreInto(dir)
    assert(preview.locked, "the preview object must not keep a live key")
    restored.openSecret(restored.data.wallets.head).use(s => assert(new String(s.mnemonic) == Mnemonic))
    assert(Store.unlock(dir, Pass.toCharArray).data.wallets.head.name == "My savings", "opens with the backup's password")

    Files.move(dir.resolve("data.json.before-restore"), dir.resolve("data.json"), StandardCopyOption.REPLACE_EXISTING)
    assert(Store.unlock(dir, "the current password".toCharArray).data.contacts.head.label == "kept")
  }

  // ---------------------------------------------------------------- password policy

  test("new passwords need 12+ characters and must be hard to guess") {
    def problem(p: String) = Store.strength(p.toCharArray).problem
    assert(problem("Tr0ub4dor&3").contains("Use at least 12 characters"))
    for (weak <- Seq("password1234", "qwertyuiop123", "aaaaaaaaaaaaaaaa")) assert(problem(weak).exists(_.startsWith("Too easy to guess")), weak)
    for (strong <- Seq("correct horse battery staple", "vX9#mQ2!pL7$wR4z")) assert(problem(strong).isEmpty, strong)
    assert(Store.strength("correct horse battery staple".toCharArray).score >= 3)
  }

  test("measuring a password leaves the caller's array untouched (the repeat-password check compares it afterwards)") {
    val pw = "correct horse battery staple".toCharArray
    Store.strength(pw)
    assert(new String(pw) == "correct horse battery staple")
  }
}
