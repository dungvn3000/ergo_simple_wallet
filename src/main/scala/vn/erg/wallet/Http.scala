package vn.erg.wallet

import java.io.{ByteArrayOutputStream, IOException, InputStream}
import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.nio.charset.StandardCharsets
import java.time.Duration

object Http {
  /** Largest response read into memory; the biggest normal one is an AVL proof of a few MB. */
  val MaxBody: Int = 64 * 1024 * 1024

  private val client = HttpClient.newBuilder()
    .connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NORMAL).build()

  /** Absolute http(s) URL with a host, e.g. https://sv1.erg.vn or http://192.168.1.5:9053. */
  def isHttpUrl(s: String): Boolean =
    scala.util.Try(java.net.URI.create(s)).toOption.exists(u => Seq("http", "https").contains(u.getScheme) && u.getHost != null)

  def get(url: String): String = text(HttpRequest.newBuilder(URI.create(url)).GET())

  def post(url: String, contentType: String, body: String): String =
    text(HttpRequest.newBuilder(URI.create(url)).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofString(body)))

  def getBytes(url: String): Array[Byte] = send(HttpRequest.newBuilder(URI.create(url)).GET())

  private def text(b: HttpRequest.Builder): String = new String(send(b), StandardCharsets.UTF_8)

  private def send(b: HttpRequest.Builder): Array[Byte] =
    try {
      val r = client.send(b.timeout(Duration.ofSeconds(60)).build(), HttpResponse.BodyHandlers.ofInputStream())
      val body = try readLimited(r.body, MaxBody) finally r.body.close()
      if (r.statusCode / 100 != 2) throw new IllegalStateException(s"HTTP ${r.statusCode}: ${new String(body.take(2000), StandardCharsets.UTF_8)}")
      body
    } catch {
      case e: IOException => throw new IllegalStateException(e.getMessage, e)
      case e: InterruptedException =>
        Thread.currentThread().interrupt()
        throw new IllegalStateException(e)
    }

  /** Reads the stream fully, refusing more than `max` bytes (a misbehaving node must not exhaust memory). */
  private[wallet] def readLimited(in: InputStream, max: Int): Array[Byte] = {
    val out = new ByteArrayOutputStream
    val buf = new Array[Byte](64 * 1024)
    var n = in.read(buf)
    while (n >= 0) {
      if (out.size + n > max) throw new IllegalStateException(s"Response larger than ${max / (1024 * 1024)} MB refused")
      out.write(buf, 0, n)
      n = in.read(buf)
    }
    out.toByteArray
  }
}
