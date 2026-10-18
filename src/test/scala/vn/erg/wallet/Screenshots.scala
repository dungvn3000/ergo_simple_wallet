package vn.erg.wallet

import java.awt.image.BufferedImage
import java.awt.{Component, Container, Window}
import java.nio.file.{Files, Paths}

import com.google.gson.JsonParser
import javax.imageio.ImageIO
import javax.swing._
import javax.swing.table.DefaultTableModel

import scala.collection.mutable.ArrayBuffer

/**
 * README screenshots of the real app on the local devnet (devnet/screenshots.sh): a sample wallet is created in a
 * throw-away home folder and funded, then the app is opened and driven through its screens, each painted to a PNG.
 */
object Screenshots {
  private val Password = "correct horse battery staple"
  private val Node = Store.DefaultNodes.head

  def main(args: Array[String]): Unit = {
    require(Ergo.Devnet, "run with devnet/screenshots.sh")
    val out = Paths.get(args.headOption.getOrElse("docs/screenshots"))
    Files.createDirectories(out)

    // ---- sample data: two wallets, one funded with ERG and a token, a contact
    val st = Store.create(Store.defaultDir, Password.toCharArray)
    def wallet(name: String): Store.Wallet = {
      val mnemonic = Bip39.generate(15)
      val xpub = Ergo.xpub(new Store.Secret(mnemonic.clone(), false))
      val first = Ergo.derive(xpub, 0)
      new Store.Wallet(name, first, st.sealSecret(Store.secretBytes(mnemonic, false)), xpub,
        ArrayBuffer(new Store.Addr(0, first), new Store.Addr(1, Ergo.derive(xpub, 1), Some("Savings"))))
    }
    val main = wallet("Main wallet")
    st.data.wallets += main
    st.data.wallets += wallet("Trading")
    val nodeAddress = JsonParser.parseString(DevnetCheck.nodeWallet("addresses", null)).getAsJsonArray.get(0).getAsString
    st.data.contacts += Store.Contact("Alice", nodeAddress)
    st.save()
    st.lock()

    val (a0, a1) = (main.addresses(0).address, main.addresses(1).address)
    DevnetCheck.nodeWallet("payment/send", s"""[{"address":"$a0","value":125500000000}]""")
    DevnetCheck.nodeWallet("payment/send", s"""[{"address":"$a1","value":40000000000}]""")
    DevnetCheck.nodeWallet("transaction/send", s"""{"requests":[{"address":"$a0","ergValue":1000000,"amount":250000,""" +
      s""""name":"DevUSD","description":"Devnet stablecoin for testing","decimals":2}],"fee":1000000}""")
    waitUntil("funding mined", 120) {
      val b = JsonParser.parseString(Http.post(s"$Node/blockchain/balance", "text/plain", a0)).getAsJsonObject.getAsJsonObject("confirmed")
      b.get("nanoErgs").getAsLong == 125501000000L && b.getAsJsonArray("tokens").size == 1
    }

    // ---- open the app and unlock it
    WalletApp.main(Array.empty)
    val unlock = waitFor("unlock dialog", 30)(dialogWith[JPasswordField])
    onEdt {
      find[JPasswordField](unlock).head.setText(Password)
      find[JOptionPane](unlock).head.setValue(Integer.valueOf(JOptionPane.OK_OPTION))
    }
    val splash = waitFor("splash", 30)(frames.find(f => f.isUndecorated && f.isShowing))
    Thread.sleep(400) // a few log lines and a turned logo
    shot(out, "splash", splash.getRootPane)

    val frame = waitFor("main window", 60)(frames.find(f => !f.isUndecorated && f.isShowing))
    onEdt(frame.setSize(1150, 760))
    // both addresses: 125.501 + 40
    waitUntil("verified balance", 120)(onEdt(find[JLabel](frame).exists(_.getText == "165.501 ERG")))
    waitUntil("token names", 60)(onEdt(find[JTable](frame).exists(t => (0 until t.getRowCount).exists(r => t.getValueAt(r, 0) == "DevUSD"))))
    Thread.sleep(500)
    val tabs = onEdt(find[JTabbedPane](frame).head)
    def tab(i: Int): Unit = { onEdt(tabs.setSelectedIndex(i)); Thread.sleep(300) }
    shot(out, "overview", frame.getRootPane)

    // send: recipient, amount and a token, then the confirmation with what leaves the wallet
    tab(1)
    val send = tabs.getComponentAt(1).asInstanceOf[JComponent]
    onEdt {
      val fields = find[JTextField](send).filter(f => SwingUtilities.getAncestorOfClass(classOf[JComboBox[_]], f) == null)
      fields(0).setText(nodeAddress)
      fields(1).setText("12.5")
      // the token row, as "Add token…" would add it (id taken from the overview's token table)
      val model = find[JTable](send).head.getModel.asInstanceOf[DefaultTableModel]
      val id = find[JTable](frame).flatMap(t => (0 until t.getRowCount).collect { case r if t.getValueAt(r, 0) == "DevUSD" => t.getValueAt(r, 2) }).head
      model.addRow(Array[AnyRef]("DevUSD", "150", id))
    }
    Thread.sleep(300)
    shot(out, "send", frame.getRootPane)
    onEdt(find[JButton](send).find(_.getText == "Send…").get.doClick())
    val confirm = waitFor("confirmation", 60)(dialogs.find(_.getTitle == "Confirm transaction"))
    Thread.sleep(300)
    shot(out, "confirm", confirm.getRootPane)
    onEdt(find[JOptionPane](confirm).head.setValue(Integer.valueOf(JOptionPane.CANCEL_OPTION)))

    // receive: a payment request with its QR code
    tab(2)
    val receive = tabs.getComponentAt(2).asInstanceOf[JComponent]
    onEdt {
      val fields = find[JTextField](receive).filter(_.isEditable)
      fields(0).setText("5")
      fields(1).setText("Coffee beans")
    }
    Thread.sleep(400)
    shot(out, "receive", frame.getRootPane)

    // history from the node's index
    tab(3)
    val history = tabs.getComponentAt(3).asInstanceOf[JComponent]
    onEdt(find[JButton](history).find(_.getText == "Load history").get.doClick())
    waitUntil("history", 60)(onEdt(find[JTable](history).head.getRowCount >= 3))
    Thread.sleep(300)
    shot(out, "history", frame.getRootPane)

    tab(4)
    shot(out, "addresses", frame.getRootPane)

    println(s"Screenshots written to $out")
    sys.exit(0)
  }

  // ---------------------------------------------------------------- helpers

  private def frames: Seq[JFrame] = onEdt(Window.getWindows.toSeq.collect { case f: JFrame => f })

  private def dialogs: Seq[JDialog] = onEdt(Window.getWindows.toSeq.collect { case d: JDialog if d.isShowing => d })

  private def dialogWith[T <: Component](implicit m: Manifest[T]): Option[JDialog] = dialogs.find(d => find[T](d).nonEmpty)

  /** All components of type T under `c`, in layout order. */
  private def find[T <: Component](c: Component)(implicit m: Manifest[T]): Seq[T] = {
    val here = if (m.runtimeClass.isInstance(c)) Seq(c.asInstanceOf[T]) else Nil
    here ++ (c match {
      case k: Container => k.getComponents.toSeq.flatMap(find[T](_))
      case _ => Nil
    })
  }

  private def onEdt[T](f: => T): T = {
    var r: Option[T] = None
    SwingUtilities.invokeAndWait(() => r = Some(f))
    r.get
  }

  private def waitFor[T](what: String, seconds: Int)(f: => Option[T]): T = {
    val deadline = System.currentTimeMillis + seconds * 1000L
    while (System.currentTimeMillis < deadline) {
      f match {
        case Some(v) => return v
        case None => Thread.sleep(200)
      }
    }
    throw new IllegalStateException(s"timed out waiting for $what")
  }

  private def waitUntil(what: String, seconds: Int)(ok: => Boolean): Unit = waitFor(what, seconds)(Some(()).filter(_ => ok))

  /** Paints the component at 2x (sharp on high-DPI screens) into <out>/<name>.png. */
  private def shot(out: java.nio.file.Path, name: String, c: JComponent): Unit = onEdt {
    val scale = 2
    val img = new BufferedImage(c.getWidth * scale, c.getHeight * scale, BufferedImage.TYPE_INT_RGB)
    val g = img.createGraphics()
    g.scale(scale, scale)
    c.paint(g)
    g.dispose()
    ImageIO.write(img, "png", out.resolve(s"$name.png").toFile)
    println(s"  $name.png ${c.getWidth}x${c.getHeight}")
  }
}
