package vn.erg.wallet

import java.nio.charset.StandardCharsets
import java.security.{MessageDigest, SecureRandom}
import java.util.Arrays
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

import scala.io.Source

/**
 * BIP39 over Array[Char] only. appkit's mnemonic APIs turn the phrase into a java.lang.String, which can never be
 * wiped from memory; here the phrase stays in arrays the caller can zero.
 */
object Bip39 {
  private val Words: Array[String] = {
    val src = Source.fromInputStream(getClass.getResourceAsStream("/wordlist/english.txt"), "UTF-8")
    val w = try src.getLines().map(_.trim).filter(_.nonEmpty).toArray finally src.close()
    if (w.length != 2048) throw new IllegalStateException(s"BIP39 wordlist has ${w.length} words")
    w
  }

  /** New mnemonic with `wordCount` words (12..24, multiple of 3). */
  def generate(wordCount: Int): Array[Char] = {
    require(wordCount >= 12 && wordCount <= 24 && wordCount % 3 == 0, s"BIP39 word count must be 12..24 and a multiple of 3, got $wordCount")
    val entropy = new Array[Byte](wordCount * 11 * 32 / 33 / 8)
    new SecureRandom().nextBytes(entropy)
    val idx = indices(entropy, wordCount)
    Arrays.fill(entropy, 0.toByte)
    try join(idx) finally Arrays.fill(idx, 0)
  }

  /**
   * Validates words and checksum; returns the canonical form (lowercase, single spaces) as a new array.
   * The input array is not modified.
   */
  def normalize(phrase: Array[Char]): Array[Char] = {
    val idx = new Array[Int](24)
    try {
      var n = 0
      var i = 0
      while (i < phrase.length) {
        while (i < phrase.length && Character.isWhitespace(phrase(i))) i += 1
        val start = i
        while (i < phrase.length && !Character.isWhitespace(phrase(i))) i += 1
        if (start < i) {
          if (n == 24) throw new IllegalArgumentException("Mnemonic has more than 24 words")
          val w = lookup(phrase, start, i)
          if (w < 0) throw new IllegalArgumentException(s"Word #${n + 1} is not in the BIP39 English list")
          idx(n) = w
          n += 1
        }
      }
      if (n < 12 || n % 3 != 0) throw new IllegalArgumentException("Mnemonic must have 12, 15, 18, 21 or 24 words")
      val words = Arrays.copyOf(idx, n)
      try {
        val e = entropy(words)
        val ok = Arrays.equals(indices(e, n), words)
        Arrays.fill(e, 0.toByte)
        if (!ok) throw new IllegalArgumentException("Mnemonic checksum is wrong (check the word order and spelling)")
        join(words)
      } finally Arrays.fill(words, 0)
    } finally Arrays.fill(idx, 0)
  }

  /** BIP39 seed with an empty passphrase: PBKDF2-HMAC-SHA512(phrase, "mnemonic", 2048). The phrase is ASCII, so NFKD is a no-op. */
  def seed(phrase: Array[Char]): Array[Byte] = {
    val spec = new PBEKeySpec(phrase, "mnemonic".getBytes(StandardCharsets.US_ASCII), 2048, 512)
    try SecretKeyFactory.getInstance("PBKDF2WithHmacSHA512").generateSecret(spec).getEncoded
    finally spec.clearPassword()
  }

  /** Word ranges of a canonical phrase, for display without building strings: (start, end) pairs. */
  def wordRanges(phrase: Array[Char]): Vector[(Int, Int)] = {
    val spaces = phrase.indices.filter(phrase(_) == ' ')
    ((-1 +: spaces) zip (spaces :+ phrase.length)).map { case (s, e) => (s + 1, e) }.toVector
  }

  // ---------------------------------------------------------------- internals

  private def lookup(p: Array[Char], start: Int, end: Int): Int = {
    var lo = 0
    var hi = Words.length - 1
    while (lo <= hi) {
      val mid = (lo + hi) >>> 1
      val c = compare(p, start, end, Words(mid))
      if (c == 0) return mid
      if (c < 0) hi = mid - 1 else lo = mid + 1
    }
    -1
  }

  private def compare(p: Array[Char], start: Int, end: Int, word: String): Int = {
    val len = end - start
    var i = 0
    while (i < math.min(len, word.length)) {
      val d = Character.toLowerCase(p(start + i)) - word.charAt(i)
      if (d != 0) return d
      i += 1
    }
    len - word.length
  }

  /** entropy ++ first (ENT/32) bits of SHA-256(entropy), split into 11-bit word indices. */
  private def indices(entropy: Array[Byte], wordCount: Int): Array[Int] = {
    val hash = MessageDigest.getInstance("SHA-256").digest(entropy)
    val out = new Array[Int](wordCount)
    val entBits = entropy.length * 8
    for (bit <- 0 until wordCount * 11) {
      val (b, pos) = if (bit < entBits) (entropy(bit / 8), bit % 8) else (hash((bit - entBits) / 8), (bit - entBits) % 8)
      if (((b >> (7 - pos)) & 1) == 1) out(bit / 11) |= 1 << (10 - bit % 11)
    }
    Arrays.fill(hash, 0.toByte)
    out
  }

  private def entropy(words: Array[Int]): Array[Byte] = {
    val e = new Array[Byte](words.length * 11 * 32 / 33 / 8)
    for (bit <- 0 until e.length * 8 if ((words(bit / 11) >> (10 - bit % 11)) & 1) == 1)
      e(bit / 8) = (e(bit / 8) | (1 << (7 - bit % 8))).toByte
    e
  }

  private def join(idx: Array[Int]): Array[Char] = {
    val out = new Array[Char](idx.map(Words(_).length).sum + idx.length - 1)
    var p = 0
    for ((w, k) <- idx.zipWithIndex) {
      if (k > 0) { out(p) = ' '; p += 1 }
      Words(w).getChars(0, Words(w).length, out, p)
      p += Words(w).length
    }
    out
  }
}
