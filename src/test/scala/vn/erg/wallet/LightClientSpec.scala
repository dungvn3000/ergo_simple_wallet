package vn.erg.wallet

import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets

import com.sun.net.httpserver.HttpServer
import org.scalatest.funsuite.AnyFunSuite

import scala.io.Source

/** LightClient.sync against local fake nodes serving the real mainnet proof fixture (no network needed). */
class LightClientSpec extends AnyFunSuite {
  private def resource(name: String): String = {
    val src = Source.fromInputStream(getClass.getResourceAsStream("/" + name), "UTF-8")
    try src.mkString finally src.close()
  }

  private lazy val proof = resource("proof-m6-k6.json")
  private lazy val block = s"""{"extension":{"fields":${resource("extension-1896448.json")}}}"""

  /** A fake node whose proof request takes `delayMs`; `broken` makes it fail every request; `extra`: path -> 200 body. */
  private def node(delayMs: Long, broken: Boolean = false, extra: Map[String, String] = Map.empty): HttpServer = {
    val server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0)
    server.createContext("/", exchange => {
      val path = exchange.getRequestURI.getPath
      if (path.startsWith("/nipopow/")) Thread.sleep(delayMs) // one slow request per node: elapsed time shows overlap
      val (code, body) =
        if (broken) (500, "{}")
        else if (extra.contains(path)) (200, extra(path))
        else if (path == "/nipopow/proof/6/6") (200, proof)
        else if (path.startsWith("/blocks/")) (200, block)
        else (404, "{}")
      val bytes = body.getBytes(StandardCharsets.UTF_8)
      exchange.sendResponseHeaders(code, bytes.length)
      exchange.getResponseBody.write(bytes)
      exchange.close()
    })
    server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool())
    server.start()
    server
  }

  private def url(s: HttpServer) = s"http://127.0.0.1:${s.getAddress.getPort}"

  test("proofs are fetched from all nodes in parallel; a broken node is skipped") {
    val (slowA, slowB, dead) = (node(2000), node(2000), node(0, broken = true))
    try {
      val client = new LightClient(m = 6, k = 6)
      val lines = new java.util.concurrent.ConcurrentLinkedQueue[String]
      val t = System.currentTimeMillis
      val best = client.sync(Seq(url(slowA), url(slowB), url(dead)), lines.add)
      val elapsed = System.currentTimeMillis - t
      assert(elapsed < 3500, s"two 2 s nodes must overlap, took $elapsed ms")
      assert(best.tip.height == 1897174)
      assert(best.source == url(slowA), "equal proofs: the first node in the list wins")
      assert(client.bestNode == url(slowA))
      assert(lines.toArray.exists(_.toString.startsWith(s"  rejected ${url(dead)}")))
    } finally Seq(slowA, slowB, dead).foreach(_.stop(0))
  }

  test("a node answering garbage with status 200 does not stop the other nodes") {
    val byAddress = "/blockchain/transaction/byAddress"
    // a proxy error page, broken JSON, a reply without the expected field: each fails differently inside Gson
    for (garbage <- Seq("<html>Bad gateway</html>", "{\"total\":", """{"error":"index disabled"}""")) {
      val (bad, good) = (node(0, extra = Map(byAddress -> garbage)), node(0, extra = Map(byAddress -> """{"total":3}""")))
      try {
        val client = new LightClient(m = 6, k = 6)
        client.sync(Seq(url(bad)), _ => ()) // the garbage node is the best node, so it is asked first
        assert(client.addressUsed(Seq(url(bad), url(good)), "9any", _ => ()), garbage)
      } finally Seq(bad, good).foreach(_.stop(0))
    }
  }

  test("a node answering with other m/k than asked is rejected") {
    val n = node(0)
    try {
      val client = new LightClient(m = 6, k = 10) // the fixture proof has k = 6, but the node is asked for k = 10
      val e = intercept[IllegalStateException](client.sync(Seq(url(n)), _ => ()))
      assert(e.getMessage.contains("no node returned a valid NiPoPoW proof"))
    } finally n.stop(0)
  }
}
