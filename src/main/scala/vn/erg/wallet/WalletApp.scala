package vn.erg.wallet

import java.awt._
import java.nio.file.Files
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{Instant, LocalDate, LocalTime, ZoneId}
import java.util.Locale
import java.util.concurrent.{ConcurrentHashMap, ExecutorService, Executors, ThreadFactory}

import com.formdev.flatlaf.FlatIntelliJLaf
import vn.erg.wallet.Dialogs.{button, form, row}
import vn.erg.wallet.WalletApp._
import javax.swing._
import org.ergoplatform.ErgoBox
import org.ergoplatform.appkit.{Address, Parameters}

import scala.collection.JavaConverters._
import scala.collection.immutable.SortedMap
import scala.collection.mutable.ArrayBuffer
import scala.util.control.NonFatal

/** Ergo Simple Wallet: chain tip from NiPoPoW proofs, balances from AVL-proven UTXOs. Swing + FlatLaf IntelliJ. */
object WalletApp {
  val AppTitle: String = "Ergo Simple Wallet" + (if (Ergo.Devnet) " – DEVNET (test coins)" else "")
  val IdleLockMs: Long = 5 * 60000L
  val GapLimit = 20

  def main(args: Array[String]): Unit = {
    FlatIntelliJLaf.setup()
    // an exception in a button handler (e.g. disk full while saving) would otherwise only reach the terminal
    Thread.setDefaultUncaughtExceptionHandler((_, e) => {
      e.printStackTrace()
      SwingUtilities.invokeLater(() => Dialogs.error(null, s"Unexpected error: ${message(e)}"))
    })
    SwingUtilities.invokeLater(() => new WalletApp().show())
  }

  private def ui(f: => Unit): Unit = SwingUtilities.invokeLater(() => f)

  private def daemon: ThreadFactory = r => {
    val t = new Thread(r)
    t.setDaemon(true)
    t
  }

  private def message(t: Throwable): String = {
    var c = t
    while (c.getCause != null && c.getMessage == null) c = c.getCause
    Option(c.getMessage).map(_.split("\n").take(6).mkString("\n")).getOrElse(c.getClass.getSimpleName)
  }

  private def item(text: String)(action: => Unit): JMenuItem = {
    val i = new JMenuItem(text)
    i.addActionListener(_ => action)
    i
  }

  private def addressLabel(a: Store.Addr): String =
    a.label.getOrElse(if (a.index == 0) "Main address" else s"Derived address #${a.index}")

  private def addresses(w: Store.Wallet): Vector[String] = w.addresses.map(_.address).toVector

  private def indices(w: Store.Wallet): Vector[Int] = w.addresses.map(_.index).toVector

  private def esc(s: String): String = if (s == null) "" else s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private def boxed(xs: Any*): Array[AnyRef] = xs.map(_.asInstanceOf[AnyRef]).toArray
}

final class WalletApp {
  private var store: Store = _
  private val client = new LightClient
  private val worker: ExecutorService = Executors.newSingleThreadExecutor(daemon)
  private val misc: ExecutorService = Executors.newCachedThreadPool(daemon)
  private val boxes = new ConcurrentHashMap[String, Seq[ErgoBox]]
  @volatile private var fiatPrice: Option[Double] = None

  private var frame: JFrame = _
  private val walletModel = new DefaultListModel[Store.Wallet]
  private val walletList = new JList[Store.Wallet](walletModel)
  private val right = new JPanel(new BorderLayout)
  private val status = new JLabel("Not synced")
  private val statusLogo = new ErgoLogo(18) // turns while background work runs, like ergo-wallet-app
  private var busy = 0 // background tasks queued or running; touched on the EDT only
  private val logArea = new JTextArea(6, 80)
  private var view: Option[WalletView] = None

  private def show(): Unit = {
    store = openStore().getOrElse(sys.exit(0))
    splash()
  }

  /** Verifies the chain before the wallet opens: balances are only shown against a NiPoPoW-verified tip. */
  private def splash(): Unit = {
    val w = new JFrame(AppTitle)
    w.setUndecorated(true)
    val progress = new JProgressBar
    progress.setIndeterminate(true)
    val status = new JLabel("Verifying the Ergo chain with NiPoPoW proofs…")
    val lines = Dialogs.textArea("", 8, editable = false)
    lines.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11))
    lines.setLineWrap(false)
    val buttons = row()
    val logo = new ErgoLogo(96)
    def start(): Unit = {
      logo.spinning(true)
      buttons.setVisible(false)
      progress.setIndeterminate(true)
      status.setText("Verifying the Ergo chain with NiPoPoW proofs…")
      val nodes = store.data.settings.nodes
      worker.execute { () =>
        try {
          client.sync(nodes, line => {
            log(line)
            ui(lines.append(line + "\n"))
          })
          ui {
            logo.spinning(false)
            w.dispose()
            openMain()
          }
        } catch {
          case NonFatal(e) => ui {
            logo.spinning(false)
            progress.setIndeterminate(false)
            status.setText(s"<html>Chain verification failed: ${esc(message(e))}<br>Check the nodes in Settings, or retry.</html>")
            buttons.setVisible(true)
          }
        }
      }
    }
    buttons.add(button("Retry")(start()))
    buttons.add(button("Settings…")(if (Dialogs.settings(w, store)) start()))
    buttons.add(button("Open anyway") { logo.spinning(false); w.dispose(); openMain() })
    val p = new JPanel(new BorderLayout(0, 10))
    p.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createLineBorder(UIManager.getColor("Component.borderColor")),
      BorderFactory.createEmptyBorder(24, 24, 24, 24)))
    val title = new JLabel(AppTitle)
    title.putClientProperty("FlatLaf.styleClass", "h1")
    val head = new JPanel(new BorderLayout(18, 0))
    head.add(logo, BorderLayout.WEST)
    head.add(form(null, title, null, status, null, progress), BorderLayout.CENTER)
    p.add(head, BorderLayout.NORTH)
    val sp = new JScrollPane(lines)
    sp.setPreferredSize(new Dimension(560, 150))
    p.add(sp, BorderLayout.CENTER)
    p.add(buttons, BorderLayout.SOUTH)
    w.setContentPane(p)
    w.pack()
    w.setLocationRelativeTo(null)
    w.setVisible(true)
    start()
  }

  private def openMain(): Unit = {
    frame = new JFrame(AppTitle)
    frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE)
    frame.setJMenuBar(menu())

    store.data.wallets.foreach(walletModel.addElement)
    walletList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
    // delegate through the ListCellRenderer interface: subclassing DefaultListCellRenderer hits its raw-type overloads
    val base: ListCellRenderer[AnyRef] = new DefaultListCellRenderer
    walletList.setCellRenderer(new ListCellRenderer[Store.Wallet] {
      override def getListCellRendererComponent(l: JList[_ <: Store.Wallet], w: Store.Wallet, i: Int, sel: Boolean, focus: Boolean): Component = {
        val bal = if (hasBalance(w)) Ergo.erg(balance(addresses(w))) + " ERG" else "…"
        val c = base.getListCellRendererComponent(l.asInstanceOf[JList[AnyRef]], s"<html><b>${esc(w.name)}</b><br>$bal</html>", i, sel, focus)
          .asInstanceOf[JLabel]
        c.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10))
        c
      }
    })
    walletList.addListSelectionListener(e => if (!e.getValueIsAdjusting) showWallet(Option(walletList.getSelectedValue)))

    val left = new JPanel(new BorderLayout(0, 6))
    left.add(new JScrollPane(walletList), BorderLayout.CENTER)
    left.add(row(button("+ New")(newWallet()), button("Restore")(restoreWallet())), BorderLayout.SOUTH)
    left.setPreferredSize(new Dimension(260, 400))

    logArea.setEditable(false)
    logArea.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 11))
    val bottom = new JPanel(new BorderLayout)
    val statusBar = new JPanel(new BorderLayout(6, 0))
    statusBar.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 0))
    statusBar.add(statusLogo, BorderLayout.WEST)
    statusBar.add(status, BorderLayout.CENTER)
    bottom.add(statusBar, BorderLayout.NORTH)
    bottom.add(new JScrollPane(logArea), BorderLayout.CENTER)
    status.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 8))

    val all = new JSplitPane(JSplitPane.VERTICAL_SPLIT, new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, left, right), bottom)
    all.setResizeWeight(0.85)
    frame.setContentPane(all)
    frame.setSize(1150, 780)
    frame.setLocationRelativeTo(null)
    frame.setVisible(true)
    startAutoLock()

    if (!walletModel.isEmpty) walletList.setSelectedIndex(0) else showWallet(None)
    updateStatus()
    afterSync()
  }

  // ---------------------------------------------------------------- encrypted wallet file

  /** Startup: create or unlock the encrypted wallet file. None if the user cancels. */
  private def openStore(): Option[Store] = {
    val dir = Store.defaultDir
    if (!Store.exists(dir))
      Dialogs.newPassword(null, "Create password", "Choose a password to encrypt your wallets, address book and settings " +
        "on this computer. You will enter it each time the app starts.").map { pw =>
        try Store.create(dir, pw) finally java.util.Arrays.fill(pw, '\u0000')
      }
    else {
      var result: Option[Store] = None
      var done = false
      while (!done) Dialogs.password(null, "Password", "Unlock Ergo Simple Wallet") match {
        case None => done = true
        case Some(pw) =>
          try {
            result = Some(Store.unlock(dir, pw))
            done = true
          } catch {
            case e: IllegalArgumentException => Dialogs.error(null, e.getMessage) // wrong password: ask again
            case e: IllegalStateException => // unreadable file: nothing to retry
              Dialogs.error(null, e.getMessage)
              done = true
          } finally java.util.Arrays.fill(pw, '\u0000')
      }
      result
    }
  }

  // ---------------------------------------------------------------- auto-lock

  @volatile private var lastActivity = System.currentTimeMillis
  private var mainContent: Container = _
  private var mainMenu: JMenuBar = _

  private def startAutoLock(): Unit = {
    Toolkit.getDefaultToolkit.addAWTEventListener(_ => lastActivity = System.currentTimeMillis,
      AWTEvent.KEY_EVENT_MASK | AWTEvent.MOUSE_EVENT_MASK | AWTEvent.MOUSE_MOTION_EVENT_MASK | AWTEvent.MOUSE_WHEEL_EVENT_MASK)
    new Timer(15000, _ => if (!store.locked && System.currentTimeMillis - lastActivity > IdleLockMs) lock()).start()
  }

  /** Zeroes the key, closes open dialogs and hides all wallet data behind a password screen. */
  private def lock(): Unit = if (!store.locked) {
    store.lock()
    // a dialog left open (e.g. "Confirm transaction") would continue with a locked store: cancel it
    Window.getWindows.foreach(win => if (win.isInstanceOf[Dialog] && win.isShowing) win.dispose())
    mainContent = frame.getContentPane
    mainMenu = frame.getJMenuBar
    val pf = new JPasswordField(22)
    val error = new JLabel(" ")
    error.setForeground(new Color(0xc62828))
    def unlock(): Unit = {
      val pw = pf.getPassword
      Dialogs.wipe(pf)
      try {
        store.reunlock(pw)
        frame.setJMenuBar(mainMenu)
        frame.setContentPane(mainContent)
        frame.revalidate()
        frame.repaint()
        lastActivity = System.currentTimeMillis
        log("Unlocked")
      } catch {
        case e@(_: IllegalArgumentException | _: IllegalStateException) => error.setText(e.getMessage)
      } finally java.util.Arrays.fill(pw, '\u0000')
    }
    pf.addActionListener(_ => unlock())
    val screen = new JPanel(new GridBagLayout)
    screen.add(form(null, Dialogs.title("Wallet locked"), null,
      new JLabel(s"Locked after ${IdleLockMs / 60000} minutes without activity, or by Tools → Lock now."),
      "Password", pf, null, row(button("Unlock")(unlock())), null, error))
    frame.setJMenuBar(null)
    frame.setContentPane(screen)
    frame.revalidate()
    frame.repaint()
    pf.requestFocusInWindow()
    log("Locked")
  }

  /** Re-asks the shared password before signing, showing or changing secrets. */
  private def confirmPassword(title: String): Boolean = Dialogs.password(frame, "Password", title).exists { pw =>
    try {
      val ok = store.checkPassword(pw)
      if (!ok) Dialogs.error(frame, "Wrong password")
      ok
    } finally java.util.Arrays.fill(pw, '\u0000')
  }

  private def changePassword(): Unit = if (confirmPassword("Change password"))
    Dialogs.newPassword(frame, "Change password", "New password for the wallet file.").foreach { pw =>
      try store.changePassword(pw) finally java.util.Arrays.fill(pw, '\u0000')
      Dialogs.info(frame, "Password changed.", "Change password")
    }

  private def backup(): Unit = {
    val fc = new JFileChooser
    fc.setSelectedFile(new java.io.File(s"ergo-wallet-backup-${LocalDate.now}.json"))
    if (fc.showSaveDialog(frame) == JFileChooser.APPROVE_OPTION) {
      val target = fc.getSelectedFile.toPath
      if (!Files.exists(target) || Dialogs.confirm(frame, s"${target.getFileName} exists. Overwrite it?", "Backup")) {
        store.backup(target)
        log(s"Backup written to $target")
        Dialogs.info(frame, s"<html><div style='width:380px'>Backup saved to<br><b>${esc(target.toString)}</b><br><br>" +
          "It holds all wallets, the address book and settings, encrypted with your current password. " +
          "Keep it somewhere safe; you need that password to restore it.</div></html>", "Backup")
      }
    }
  }

  /** Replaces the wallet file with a backup after showing what it contains. */
  private def restoreBackup(): Unit = {
    val fc = new JFileChooser
    if (fc.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) return
    val source = fc.getSelectedFile.toPath
    var backup: Option[Store] = None
    var done = false
    while (!done) Dialogs.password(frame, "Password of this backup", "Restore from backup") match {
      case None => done = true
      case Some(pw) =>
        try {
          backup = Some(Store.openBackup(source, pw))
          done = true
        } catch {
          case e: IllegalArgumentException => Dialogs.error(frame, e.getMessage) // wrong password: ask again
          case e: IllegalStateException =>
            Dialogs.error(frame, e.getMessage)
            done = true
        } finally java.util.Arrays.fill(pw, '\u0000')
    }
    backup.foreach { b =>
      val names = if (b.data.wallets.isEmpty) "(no wallets)" else b.data.wallets.map(w => "• " + esc(w.name)).mkString("<br>")
      if (Dialogs.confirm(frame, s"<html><div style='width:400px'>The backup contains ${b.data.wallets.size} wallet(s):<br>$names" +
        "<br><br><b>All current wallets, the address book and settings will be replaced.</b> " +
        "The current file is kept as <code>data.json.before-restore</code>.<br><br>" +
        "From now on the app opens with the backup's password.</div></html>", "Restore from backup")) {
        val old = store
        store = b.restoreInto(Store.defaultDir)
        old.lock() // replaced: its key must not linger in memory
        log(s"Restored ${store.data.wallets.size} wallet(s) from $source")
        walletModel.clear()
        store.data.wallets.foreach(walletModel.addElement)
        if (walletModel.isEmpty) showWallet(None) else walletList.setSelectedIndex(0)
        sync() // the backup may carry other nodes
      } else b.lock() // preview only: drop its key
    }
  }

  private def menu(): JMenuBar = {
    val bar = new JMenuBar
    val wallet = new JMenu("Wallet")
    wallet.add(item("Create new wallet…")(newWallet()))
    wallet.add(item("Restore wallet…")(restoreWallet()))
    wallet.addSeparator()
    wallet.add(item("Address book…")(Dialogs.addressBook(frame, store, choose = false, Nil)))
    wallet.add(item("Settings…")(if (Dialogs.settings(frame, store)) sync()))
    wallet.add(item("Change password…")(changePassword()))
    wallet.addSeparator()
    wallet.add(item("Exit")(sys.exit(0)))
    val tools = new JMenu("Tools")
    tools.add(item("Open link / scan QR…")(promptLink("Open link or QR code", "Paste a payment request, ergopay: or ergoauth:// link, or load a QR code.")))
    tools.add(item("ErgoPay…")(promptLink("ErgoPay", "Paste an ergopay: link or load its QR code.")))
    tools.add(item("ErgoAuth…")(promptLink("ErgoAuth", "Paste an ergoauth:// link or load its QR code.")))
    tools.addSeparator()
    tools.add(item("Rescan wallet")(selected().foreach(rescan)))
    tools.add(item("Lock now")(lock()))
    tools.add(item("Backup wallets…")(backup()))
    tools.add(item("Restore from backup…")(restoreBackup()))
    tools.addSeparator()
    tools.add(item("Sync NiPoPoW now")(sync()))
    tools.add(item("Refresh balances")(refreshAll()))
    bar.add(wallet)
    bar.add(tools)
    bar
  }

  // ---------------------------------------------------------------- background work

  /** Runs `task` on the single worker thread, then `done` on the EDT; failures are logged and (unless `quiet`) shown. */
  private def bg[T](what: String, quiet: Boolean = false)(task: => T)(done: T => Unit): Unit = {
    status.setText(what + "…")
    busy += 1
    statusLogo.spinning(true)
    worker.execute { () =>
      try {
        val r = task
        // locked meanwhile: drop the result, it would show wallet data (e.g. a transaction to confirm) over the lock screen
        ui(if (!store.locked) done(r) else log(s"$what finished while locked; result discarded"))
      } catch {
        case t: Throwable =>
          if (!t.isInstanceOf[IllegalArgumentException] && !t.isInstanceOf[IllegalStateException]) t.printStackTrace()
          val msg = message(t)
          log(s"ERROR $what: $msg")
          ui(if (!store.locked && !quiet) Dialogs.error(frame, s"$what failed:\n$msg"))
      } finally ui {
        busy -= 1
        if (busy == 0) statusLogo.spinning(false)
        updateStatus()
      }
    }
  }

  private def updateStatus(): Unit = {
    val chain = client.best.fold("Chain not verified")(p => s"✔ Verified tip #${p.tip.height} (${p.tip.idHex.take(12)}…) via ${p.source}" +
      staleNote(p).fold("")("   ⚠ " + _))
    val price = fiatPrice.fold("")(p => s"   |   1 ERG = $p ${store.data.settings.fiat.toUpperCase(Locale.ROOT)}")
    status.setText(chain + price)
  }

  private def staleNote(p: Nipopow.Proof): Option[String] = LightClient.staleWarning(p.tip.timestamp, System.currentTimeMillis)

  private def log(s: String): Unit = ui {
    logArea.append(s"${LocalTime.now.truncatedTo(ChronoUnit.SECONDS)}  $s\n")
    logArea.setCaretPosition(logArea.getDocument.getLength)
  }

  // ---------------------------------------------------------------- chain & balances

  /** Wallets restored before the first NiPoPoW sync finished; rescanned when it does. */
  private val pendingRescan = ArrayBuffer.empty[Store.Wallet]

  private def sync(): Unit = bg("NiPoPoW sync")(client.sync(store.data.settings.nodes, log))(_ => afterSync())

  private def afterSync(): Unit = {
    refreshAll()
    // restored before the chain was verified: discovery needs a verified best node
    val pending = pendingRescan.toVector
    pendingRescan.clear()
    pending.filter(store.data.wallets.contains).foreach(rescan)
  }

  private def refreshAll(): Unit = if (client.best.isDefined) {
    // snapshot on the EDT: the worker must never iterate lists the UI mutates (add/remove address)
    val wallets = store.data.wallets.map(addresses).toVector
    bg("Verifying balances") {
      wallets.foreach(loadBoxes)
      fiatPrice = Ergo.fiatPrice(store.data.settings.fiat)
    } { _ =>
      walletList.repaint()
      view.foreach(_.update())
    }
  }

  private def chainReady: Boolean = client.best.isDefined || {
    Dialogs.error(frame, "The chain is not verified yet. Sync NiPoPoW first (Tools menu).")
    false
  }

  /** Signing needs every box of the wallet proven against a verified tip (selection and the confirmation use them). */
  private def balanceReady(w: Store.Wallet): Boolean = (client.best.isDefined && hasBalance(w)) || {
    Dialogs.error(frame, "Balance is not verified yet. Wait for the NiPoPoW sync and refresh.")
    false
  }

  private def refresh(w: Store.Wallet): Unit = if (chainReady) {
    val addrs = addresses(w) // immutable snapshot, see refreshAll
    bg(s"Verifying balance of ${w.name}")(loadBoxes(addrs)) { _ =>
      walletList.repaint()
      view.foreach(_.update())
    }
  }

  /**
   * Address discovery (BIP44 gap limit): derives index 0, 1, 2, ... and adds every address that was ever used,
   * stopping after 20 unused ones in a row. Known addresses are kept; balances are then re-verified.
   */
  private def rescan(w: Store.Wallet): Unit = if (chainReady) {
    val xpub = w.xpub
    val known = indices(w).toSet // snapshot on the EDT
    val nodes = store.data.settings.nodes
    bg(s"Rescanning ${w.name}") {
      val found = Ergo.discoverUsed(i => client.addressUsed(nodes, Ergo.derive(xpub, i), log), GapLimit)
        .filterNot(known).map(i => new Store.Addr(i, Ergo.derive(xpub, i)))
      log(s"Rescan of ${w.name}: ${found.size} new used address(es)")
      found
    } { found =>
      // locked or deleted meanwhile: rescan again later
      if (!store.locked && store.data.wallets.contains(w)) {
        // "Add derived address" may have run meanwhile: never add an index twice (its boxes would count double)
        val fresh = Ergo.newAddresses(w.addresses, found)
        val sorted = (w.addresses ++ fresh).sortBy(_.index)
        w.addresses.clear()
        w.addresses ++= sorted
        if (fresh.nonEmpty) store.save()
        view.filter(_.w eq w).foreach(_.update())
        Dialogs.info(frame, if (fresh.isEmpty) "No new used addresses found."
          else s"Added ${fresh.size} used address(es): " + fresh.map(a => s"#${a.index}").mkString(", "), "Rescan wallet")
        refresh(w)
      }
    }
  }

  /** Runs on the worker thread: AVL-proven unspent boxes per address, plus token metadata for display. */
  private def loadBoxes(addrs: Seq[String]): Unit = {
    addrs.foreach(a => boxes.put(a, client.verifiedUnspent(store.data.settings.nodes, a, Address.create(a).toPropositionBytes, log)))
    tokens(addrs).keys.foreach(token)
  }

  private def hasBalance(w: Store.Wallet): Boolean = w.addresses.forall(a => boxes.containsKey(a.address))

  private def boxesOf(addrs: Seq[String]): Seq[ErgoBox] = addrs.flatMap(a => Option(boxes.get(a)).getOrElse(Nil))

  private def balance(addrs: Seq[String]): Long = boxesOf(addrs).map(_.value).sum

  private def tokens(addrs: Seq[String]): SortedMap[String, Long] =
    SortedMap(boxesOf(addrs).flatMap(Ergo.tokensOf).groupBy(_._1).map { case (id, vs) => id -> vs.map(_._2).sum }.toSeq: _*)

  /** On the Swing thread only the cache is read (the explorer may hang); background threads fetch. */
  private def token(id: String): Ergo.TokenInfo =
    if (SwingUtilities.isEventDispatchThread) Ergo.cachedTokenInfo(id) else Ergo.tokenInfo(store.data.settings.explorerApi, store.data.settings.nodes.head, id)

  private def amountText(raw: Long, t: Ergo.TokenInfo): String =
    Ergo.tokenAmount(raw, t.decimals) + (if (t.resolved) "" else " (raw units: token info unavailable)")

  private def fiat(nano: Long): String =
    fiatPrice.fold("")(p => "≈ %,.2f %s".formatLocal(Locale.ROOT, nano / 1e9 * p, store.data.settings.fiat.toUpperCase(Locale.ROOT)))

  // ---------------------------------------------------------------- wallet management

  private def newWallet(): Unit =
    Dialogs.createMnemonic(frame).flatMap(words => saveWallet(new Store.Secret(words, false)))
      .foreach(w => if (client.best.isDefined) refresh(w))

  /** A restored wallet may have used derived addresses: discover them right away (rescan also verifies balances). */
  private def restoreWallet(): Unit =
    Dialogs.restoreMnemonic(frame).flatMap(r => saveWallet(new Store.Secret(r.mnemonic, r.deprecatedDerivation))).foreach { w =>
      if (client.best.isDefined) rescan(w)
      else {
        pendingRescan += w
        log(s"Rescan of ${w.name} will start after the NiPoPoW sync")
      }
    }

  /** Seals and stores a new wallet; None if cancelled or a duplicate. The secret is wiped whatever happens. */
  private def saveWallet(secret: Store.Secret): Option[Store.Wallet] = secret.use { s =>
    val xpub = Ergo.xpub(s)
    val first = Ergo.derive(xpub, 0)
    store.data.wallets.find(_.firstAddress == first) match {
      case Some(dup) =>
        Dialogs.error(frame, s"""This wallet already exists as "${dup.name}"""")
        None
      case None =>
        Option(JOptionPane.showInputDialog(frame, "Wallet name", s"Wallet ${walletModel.size + 1}")).map(_.trim).filter(_.nonEmpty).map { name =>
          val w = new Store.Wallet(name, first, store.sealSecret(Store.secretBytes(s.mnemonic, s.deprecatedDerivation)), xpub,
            ArrayBuffer(new Store.Addr(0, first)))
          store.data.wallets += w
          try store.save() catch {
            case e: IllegalStateException =>
              store.data.wallets -= w // not on disk: do not show it as saved
              throw e
          }
          walletModel.addElement(w)
          walletList.setSelectedValue(w, true)
          log(s"Added wallet ${w.name} (${w.firstAddress})")
          w
        }
    }
  }

  private def selected(): Option[Store.Wallet] = {
    val w = Option(walletList.getSelectedValue)
    if (w.isEmpty) Dialogs.error(frame, "Select or add a wallet first.")
    w
  }

  private def showWallet(w: Option[Store.Wallet]): Unit = {
    right.removeAll()
    view = w.map(new WalletView(_))
    view match {
      case Some(v) =>
        right.add(v.tabs)
        v.update()
      case None =>
        val empty = new JPanel(new GridBagLayout)
        empty.add(form(null, Dialogs.title("Welcome"), null,
          new JLabel("<html><div style='width:380px'>Ergo Simple Wallet is a light wallet: it verifies the Ergo chain with NiPoPoW " +
            "proofs and proves every balance against the verified UTXO state root. Create or restore a wallet to begin.</div></html>"),
          null, row(button("Create new wallet")(newWallet()), button("Restore wallet")(restoreWallet()))))
        right.add(empty)
    }
    right.revalidate()
    right.repaint()
  }

  // ---------------------------------------------------------------- transaction description

  /** Verified boxes of a wallet by box id; snapshot on the EDT for [[describe]]. */
  private def boxesById(w: Store.Wallet): Map[String, ErgoBox] =
    boxesOf(addresses(w)).map(b => org.bouncycastle.util.encoders.Hex.toHexString(b.id) -> b).toMap

  /** Runs off the EDT (token names come from the explorer). */
  private def describe(s: Ergo.TxSummary, myBoxes: Map[String, ErgoBox], mine: Set[String]): String = {
    val sb = new StringBuilder("Outputs:\n")
    for (o <- s.outputs) {
      val who = if (o.address == Ergo.FeeAddress) "miner fee" else if (mine(o.address)) s"your wallet: ${o.address}" else o.address
      sb ++= s"  ${Ergo.erg(o.value)} ERG  →  $who\n"
      o.tokens.foreach { case (id, v) =>
        val t = token(id)
        sb ++= s"        + ${amountText(v, t)} ${t.label}\n"
      }
      if (!mine(o.address) && o.address != Ergo.FeeAddress && !Address.create(o.address).isP2PK)
        sb ++= "        ⚠ this output goes to a smart contract\n"
    }
    val fee = s.outputs.filter(_.address == Ergo.FeeAddress).map(_.value).sum
    val toOthers = s.outputs.filter(o => o.address != Ergo.FeeAddress && !mine(o.address)).map(_.value).sum
    sb ++= s"\nSent to others: ${Ergo.erg(toOthers)} ERG,  fee: ${Ergo.erg(fee)} ERG\n"
    sb ++= s"Inputs: ${s.inputIds.size} boxes\n"
    // what actually leaves this wallet: a dApp's transaction can spend your boxes in ways the outputs alone hide
    val e = Ergo.walletEffect(s, myBoxes, mine)
    sb ++= s"\nThis wallet ${if (e.erg >= 0) "gives" else "receives"}: ${Ergo.erg(math.abs(e.erg))} ERG (fee included)\n"
    e.tokens.foreach { case (id, v) =>
      val t = token(id)
      sb ++= s"        ${if (v > 0) "−" else "+"}${amountText(math.abs(v), t)} ${t.label}\n"
    }
    e.burned.foreach { case (id, v) =>
      val t = token(id)
      sb ++= s"⚠ BURNED (destroyed, in no output): ${amountText(v, t)} ${t.label}\n"
    }
    if (e.unknownInputs > 0)
      sb ++= s"⚠ ${e.unknownInputs} input(s) are not verified boxes of this wallet; their value is not counted above\n"
    sb.toString
  }

  private def txText(text: String): JComponent = {
    val a = Dialogs.textArea(text, 14, editable = false)
    a.setFont(new Font(Font.MONOSPACED, Font.PLAIN, 12))
    a.setLineWrap(false)
    val sp = new JScrollPane(a)
    sp.setPreferredSize(new Dimension(760, 300))
    sp
  }

  /** Asks the password again, signs a reduced tx, submits it and reports the tx id. */
  private def signAndSubmit(w: Store.Wallet, reducedTx: Array[Byte], afterSubmit: String => Unit): Unit = if (confirmPassword("Sign transaction")) {
    val secret = store.openSecret(w)
    val idx = indices(w)
    bg("Signing and submitting") {
      val signed = secret.use(s => Ergo.signReduced(reducedTx, s, idx))
      val txId = Ergo.submit(client.bestNode, signed)
      afterSubmit(txId)
      (txId, Ergo.summarize(reducedTx).inputIds.toSet)
    } { case (txId, inputs) =>
      submitted(txId)
      refreshUntilMined(w, inputs)
    }
  }

  /**
   * After a send the balance changes only once the transaction is in a block: re-verifies the wallet every few seconds
   * until none of its spent boxes is proven unspent any more (mined), for at most 30 minutes.
   */
  private def refreshUntilMined(w: Store.Wallet, inputs: Set[String]): Unit = {
    val started = System.currentTimeMillis
    val timer = new Timer(if (Ergo.Devnet) 3000 else 20000, null)
    var checking = false // one check at a time: a slow node must not pile up ticks on the worker
    timer.addActionListener { _ =>
      if (!store.data.wallets.contains(w) || System.currentTimeMillis - started > 30 * 60000L) timer.stop()
      else if (!checking && !store.locked && client.best.isDefined) {
        checking = true
        val addrs = addresses(w)
        // a failed check (node briefly down) is only logged: the next tick tries again
        bg(s"Waiting for the transaction of ${w.name} to be mined", quiet = true)(try loadBoxes(addrs) finally ui(checking = false)) { _ =>
          walletList.repaint()
          view.foreach(_.update())
          if (!boxesById(w).keySet.exists(inputs) && timer.isRunning) {
            timer.stop()
            log(s"Transaction of ${w.name} mined: balance updated")
          }
        }
      }
    }
    timer.start()
  }

  private def submitted(txId: String): Unit = {
    log(s"Submitted transaction $txId")
    Dialogs.info(frame, form(null, new JLabel("Transaction submitted. The balance refreshes by itself once it is mined."),
      "Tx ID", Dialogs.readOnlyField(txId), null,
      row(button("Copy")(Dialogs.copy(txId)), button("Open in explorer")(Dialogs.browse(Ergo.ExplorerWeb + "en/transactions/" + txId)))),
      "Transaction sent")
  }

  // ---------------------------------------------------------------- links: payment requests, ErgoPay, ErgoAuth

  private def promptLink(title: String, hint: String): Unit = Dialogs.scan(frame, title, hint).foreach(dispatch)

  private def dispatch(s: String): Unit =
    if (Ergo.isErgoPay(s)) ergoPay(s)
    else if (Ergo.isErgoAuth(s)) ergoAuth(s)
    else if (Ergo.isPaymentRequest(s)) {
      for (pr <- Ergo.parsePaymentRequest(s); _ <- selected(); v <- view) {
        v.tabs.setSelectedIndex(1)
        v.send.fill(pr)
      }
    } else Dialogs.error(frame, "Unrecognized link or QR code.")

  // the confirmation counts what leaves the wallet from its verified boxes: without them it would say "gives 0 ERG"
  private def ergoPay(uri: String): Unit = selected().filter(balanceReady).foreach { w =>
    val address = if (Ergo.ergoPayNeedsAddress(uri)) chooseAddress(w, "The dApp asks which address to use.") else None
    if (!Ergo.ergoPayNeedsAddress(uri) || address.isDefined) {
      val mine = addresses(w).toSet
      val myBoxes = boxesById(w)
      bg("Fetching ErgoPay request") {
        val r = Ergo.fetchErgoPay(uri, address)
        (r, r.reducedTx.map(tx => describe(Ergo.summarize(tx), myBoxes, mine)))
      } { case (r, desc) =>
        val icon = r.severity match {
          case "ERROR" => "⛔ "
          case "WARNING" => "⚠ "
          case _ => "ℹ "
        }
        val msg = r.message.fold("")(icon + _)
        r.reducedTx match {
          case None => Dialogs.info(frame, msg, "ErgoPay")
          case Some(tx) =>
            if (Dialogs.confirm(frame, form(null, new JLabel(s"<html><div style='width:700px'>${esc(msg)}</div></html>"), null,
              txText(desc.getOrElse(""))), s"""ErgoPay – sign transaction with "${w.name}"?"""))
              signAndSubmit(w, tx, txId =>
                try Ergo.ergoPayReply(r, txId) catch { case NonFatal(e) => log(s"ErgoPay reply to dApp failed: ${e.getMessage}") })
        }
      }
    }
  }

  private def ergoAuth(uri: String): Unit = selected().foreach { w =>
    bg("Fetching ErgoAuth request")(Ergo.fetchErgoAuth(uri)) { r =>
      val reason = Some(r.signingMessage.indexOf('\u0000')).filter(_ > 0).map(r.signingMessage.substring(0, _))
      val text = s"<html><div style='width:460px'><b>${esc(r.host)}</b> asks you to prove ownership of an address of " +
        s""""${esc(w.name)}".<br><br>""" + r.userMessage.fold("")(m => esc(m) + "<br><br>") +
        reason.fold("")(m => s"Reason: ${esc(m)}<br><br>") + "No funds are moved.</div></html>"
      if (Dialogs.confirm(frame, text, "ErgoAuth") && confirmPassword("ErgoAuth")) {
        val secret = store.openSecret(w)
        val idx = indices(w)
        bg("Sending ErgoAuth response")(secret.use(s => Ergo.respondErgoAuth(r, s, idx))) { _ =>
          Dialogs.info(frame, s"Authenticated with ${r.host}.", "ErgoAuth")
        }
      }
    }
  }

  private def chooseAddress(w: Store.Wallet, text: String): Option[String] = {
    val box = new JComboBox[String](w.addresses.map(a => s"${addressLabel(a)}  –  ${a.address}").toArray)
    if (Dialogs.confirm(frame, form(null, new JLabel(text), "Address", box), "Choose address")) Some(w.addresses(box.getSelectedIndex).address)
    else None
  }

  // ---------------------------------------------------------------- wallet view

  private final class WalletView(val w: Store.Wallet) {
    val tabs = new JTabbedPane
    private val balanceLabel = new JLabel
    private val fiatLabel = new JLabel
    private val verified = new JLabel
    private val tokenModel = Dialogs.table("Token", "Amount", "Token ID")
    private var tokenIds = Vector.empty[String]
    private val addrModel = Dialogs.table("#", "Label", "Address", "ERG")
    val send = new SendPanel(this)
    private val receive = new ReceivePanel(this)

    tabs.addTab("Overview", overview())
    tabs.addTab("Send", send.panel)
    tabs.addTab("Receive", receive.panel)
    tabs.addTab("Transactions", new HistoryPanel(this).panel)
    tabs.addTab("Addresses", addressesPanel())
    tabs.addTab("Wallet settings", settingsPanel())

    def update(): Unit = {
      val has = hasBalance(w)
      val nano = balance(addresses(w))
      balanceLabel.setText(if (has) s"${Ergo.erg(nano)} ERG" else "Balance not verified yet")
      fiatLabel.setText(if (has) fiat(nano) else "")
      verified.setText(client.best.fold("Waiting for NiPoPoW verification…")(p =>
        if (!has) "Not loaded"
        else s"✔ Every box proven unspent against verified block #${p.tip.height}" + staleNote(p).fold("")(n => s"   ⚠ $n (Tools → Sync NiPoPoW now)")))
      tokenModel.setRowCount(0)
      val toks = tokens(addresses(w))
      tokenIds = toks.keys.toVector
      toks.foreach { case (id, v) =>
        val t = token(id)
        tokenModel.addRow(boxed(t.label + (if (t.nftType.isDefined) "  (NFT)" else ""), amountText(v, t), id))
      }
      addrModel.setRowCount(0)
      w.addresses.foreach(a => addrModel.addRow(boxed(a.index, addressLabel(a), a.address,
        if (boxes.containsKey(a.address)) Ergo.erg(balance(Seq(a.address))) else "…")))
      send.update()
      receive.update()
    }

    private def overview(): JComponent = {
      balanceLabel.putClientProperty("FlatLaf.styleClass", "h1")
      val t = new JTable(tokenModel)
      t.getColumnModel.getColumn(2).setPreferredWidth(420)
      Dialogs.onDoubleClick(t) { r =>
        val id = tokenIds(r)
        val owned = tokens(addresses(w)).getOrElse(id, 0L) // on the EDT, which owns w.addresses
        misc.execute { () =>
          val info = token(id)
          ui(Dialogs.tokenInfo(frame, info, owned, f => misc.execute(() => f())))
        }
      }
      val top = form(null, Dialogs.title(w.name), null, balanceLabel, null, fiatLabel, null, verified,
        null, row(button("Refresh")(refresh(w)), button("Send")(tabs.setSelectedIndex(1)), button("Receive")(tabs.setSelectedIndex(2))))
      val p = new JPanel(new BorderLayout(0, 10))
      p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14))
      p.add(top, BorderLayout.NORTH)
      val tok = new JPanel(new BorderLayout(0, 4))
      tok.add(new JLabel("Tokens & NFTs (double-click for details)"), BorderLayout.NORTH)
      tok.add(new JScrollPane(t), BorderLayout.CENTER)
      p.add(tok, BorderLayout.CENTER)
      p
    }

    private def addressesPanel(): JComponent = {
      val t = new JTable(addrModel)
      t.getColumnModel.getColumn(0).setMaxWidth(40)
      t.getColumnModel.getColumn(2).setPreferredWidth(420)
      def selectedRow: Option[Int] = Option(t.getSelectedRow).filter(_ >= 0)
      val add = button("Add derived address") {
        val next = w.addresses.map(_.index).max + 1
        w.addresses += new Store.Addr(next, Ergo.derive(w.xpub, next))
        store.save()
        update()
        if (client.best.isDefined) refresh(w)
      }
      val label = button("Edit label") {
        selectedRow.foreach { r =>
          val a = w.addresses(r)
          Option(JOptionPane.showInputDialog(frame, s"Label for ${a.address}", a.label.getOrElse(""))).foreach { s =>
            a.label = Some(s.trim).filter(_.nonEmpty)
            store.save()
            update()
          }
        }
      }
      val remove = button("Remove") {
        // the main address stays
        selectedRow.filter(_ > 0).filter(r => Dialogs.confirm(frame, s"Remove ${w.addresses(r).address} from this wallet?\n" +
          "Funds stay on chain; you can derive it again.", "Remove address")).foreach { r =>
          w.addresses.remove(r)
          store.save()
          update()
        }
      }
      val copy = button("Copy address")(selectedRow.foreach(r => Dialogs.copy(w.addresses(r).address)))
      val p = new JPanel(new BorderLayout(0, 8))
      p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14))
      p.add(new JScrollPane(t), BorderLayout.CENTER)
      p.add(row(add, button("Rescan wallet")(rescan(w)), label, copy, remove), BorderLayout.SOUTH)
      p
    }

    private def settingsPanel(): JComponent = {
      val rename = button("Rename…") {
        Option(JOptionPane.showInputDialog(frame, "Wallet name", w.name)).map(_.trim).filter(_.nonEmpty).foreach { s =>
          w.name = s
          store.save()
          walletList.repaint()
          showWallet(Some(w))
        }
      }
      val mnemonic = button("Show mnemonic…") {
        if (confirmPassword("Show mnemonic")) store.openSecret(w).use { s =>
          Dialogs.info(frame, form(null, new JLabel("Never share these words."), null, Dialogs.mnemonicView(s.mnemonic),
            null, new JLabel(if (s.deprecatedDerivation) "Uses pre-1627 key derivation" else "")), s"Mnemonic of ${w.name}")
        }
      }
      val xpub = button("Show extended public key…")(Dialogs.showQr(frame, "Extended public key",
        "Lets watch-only wallets see all your addresses. It cannot spend, but it reveals your whole balance and history.", w.xpub))
      val delete = button("Delete wallet…") {
        val warn = s"""Delete "${w.name}"?""" + "\nWithout your mnemonic written down, the funds are LOST forever.\nType the wallet name to confirm."
        if (JOptionPane.showInputDialog(frame, warn, "Delete wallet", JOptionPane.WARNING_MESSAGE) == w.name) {
          store.data.wallets -= w
          store.save()
          walletModel.removeElement(w)
          if (walletModel.isEmpty) showWallet(None) else walletList.setSelectedIndex(0)
        }
      }
      val p = new JPanel(new BorderLayout)
      p.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14))
      p.add(form("First address", Dialogs.readOnlyField(w.firstAddress), "Mnemonic", new JLabel("Sealed inside the encrypted wallet file"),
        null, row(rename, mnemonic, xpub), null, row(delete)), BorderLayout.NORTH)
      p
    }
  }

  // ---------------------------------------------------------------- send

  private final class SendPanel(v: WalletView) {
    private val from = new JComboBox[String]
    private val to = new JTextField
    private val amount = new JTextField(14)
    private val fee = new JComboBox[String](Array(Ergo.erg(Parameters.MinFee)))
    private val tokenModel = Dialogs.table("Token", "Amount", "Token ID")
    private val available = new JLabel
    val panel = new JPanel(new BorderLayout)

    fee.setEditable(true)
    from.addActionListener(_ => showAvailable())
    private val tokensTable = new JTable(tokenModel) {
      override def isCellEditable(r: Int, c: Int): Boolean = c == 1
    }
    // an amount typed without Enter must still count (clicking a button leaves the cell in edit mode)
    tokensTable.putClientProperty("terminateEditOnFocusLost", java.lang.Boolean.TRUE)
    private val tsp = new JScrollPane(tokensTable)
    tsp.setPreferredSize(new Dimension(500, 110))
    private val book = button("Address book…") {
      val own = store.data.wallets.flatMap(w => w.addresses.map(a => (s"${w.name} – ${addressLabel(a)}", a.address)))
      Dialogs.addressBook(frame, store, choose = true, own).foreach(to.setText)
    }
    private val scan = button("Payment request / QR…") {
      Dialogs.scan(frame, "Payment request", "Paste a payment link or address, or load a QR code.").foreach { s =>
        Ergo.parsePaymentRequest(s) match {
          case Some(pr) => fill(pr)
          case None => Dialogs.error(frame, "Not a payment request")
        }
      }
    }
    private val max = button("Max") {
      try {
        // tokens that stay need a change box, and a box must hold at least MinChangeValue
        val keep = feeNano() + (if (tokens(senders()).nonEmpty) Parameters.MinChangeValue else 0L)
        val bal = balance(senders())
        amount.setText(if (bal > keep) Ergo.erg(bal - keep) else "0")
      } catch { case e: IllegalArgumentException => Dialogs.error(frame, e.getMessage) }
    }
    private val sendBtn = button("Send…")(send())
    sendBtn.putClientProperty("JButton.buttonType", "default")
    panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14))
    panel.add(form("From", from, "", available, "To", to, "", row(book, scan),
      "Amount (ERG)", row(amount, max), "Tokens", tsp,
      "", row(button("Add token…")(addToken()), button("Remove token")(Option(tokensTable.getSelectedRow).filter(_ >= 0).foreach(tokenModel.removeRow))),
      "Fee (ERG)", row(fee, button("Suggest fee")(suggestFee())), "", row(sendBtn)), BorderLayout.NORTH)

    def update(): Unit = {
      val sel = math.max(0, from.getSelectedIndex)
      from.removeAllItems()
      from.addItem("All addresses")
      v.w.addresses.foreach(a => from.addItem(s"${addressLabel(a)}  –  ${a.address}"))
      from.setSelectedIndex(math.min(sel, from.getItemCount - 1))
      showAvailable()
    }

    private def senders(): Vector[String] = from.getSelectedIndex match {
      case i if i <= 0 => addresses(v.w)
      case i => Vector(v.w.addresses(i - 1).address)
    }

    private def showAvailable(): Unit = if (from.getItemCount > 0)
      available.setText(s"Available: ${Ergo.erg(balance(senders()))} ERG, ${tokens(senders()).size} tokens")

    private def feeNano(): Long = {
      val f = "[0-9]+(\\.[0-9]+)?".r.findFirstIn(String.valueOf(fee.getSelectedItem))
        .map(Ergo.nanoErg).getOrElse(throw new IllegalArgumentException("Invalid fee"))
      if (f < Parameters.MinFee) throw new IllegalArgumentException(s"Fee must be at least ${Ergo.erg(Parameters.MinFee)} ERG")
      f
    }

    private def suggestFee(): Unit = if (client.best.isDefined)
      bg("Fetching fee suggestions") {
        s"${Ergo.erg(Parameters.MinFee)}  (minimum)" +: Seq(5, 30, 240).map(m => s"${Ergo.erg(Ergo.suggestedFee(client.bestNode, m))}  (≈$m min)")
      } { items =>
        fee.removeAllItems()
        items.foreach(fee.addItem)
        fee.showPopup()
      }

    private def chosenTokenIds: Set[String] = (0 until tokenModel.getRowCount).map(r => tokenModel.getValueAt(r, 2).asInstanceOf[String]).toSet

    private def addToken(): Unit = {
      val have = tokens(senders())
      val ids = have.keys.filterNot(chosenTokenIds).toVector
      if (ids.isEmpty) Dialogs.info(frame, "No (more) tokens on the selected addresses.", "Tokens")
      else {
        val box = new JComboBox[String](ids.map(id => s"${token(id).label}  (${Ergo.tokenAmount(have(id), token(id).decimals)})").toArray)
        if (Dialogs.confirm(frame, form("Token", box), "Add token")) {
          val id = ids(box.getSelectedIndex)
          tokenModel.addRow(boxed(token(id).label, Ergo.tokenAmount(have(id), token(id).decimals), id))
        }
      }
    }

    // token metadata may need the explorer: fetch it off the EDT first
    def fill(pr: Ergo.PaymentRequest): Unit = bg("Reading payment request")(pr.tokens.keys.foreach(token))(_ => fillNow(pr))

    private def fillNow(pr: Ergo.PaymentRequest): Unit = {
      to.setText(pr.address)
      amount.setText(if (pr.nanoErg > 0) Ergo.erg(pr.nanoErg) else "")
      tokenModel.setRowCount(0)
      val have = tokens(senders())
      pr.tokens.foreach { case (id, amt) => tokenModel.addRow(boxed(token(id).label, amt, id)) }
      val missing = pr.tokens.keys.filterNot(have.contains)
      val msg = (if (pr.description.trim.isEmpty) "" else s"Description: ${pr.description}\n") +
        (if (missing.isEmpty) "" else s"You don't hold requested token(s): ${missing.mkString(", ")}")
      if (msg.trim.nonEmpty) Dialogs.info(frame, msg, "Payment request")
    }

    private def send(): Unit = {
      // commit a token amount still being edited, or the prefilled (full) amount would be sent
      Option(tokensTable.getCellEditor).foreach(_.stopCellEditing())
      if (!balanceReady(v.w)) return
      val recipient = to.getText.trim
      val parsed = try {
        if (!Ergo.isValidAddress(recipient)) throw new IllegalArgumentException("Invalid mainnet recipient address")
        val toks = (0 until tokenModel.getRowCount).map { r =>
          val id = tokenModel.getValueAt(r, 2).asInstanceOf[String]
          val t = token(id)
          if (!t.resolved) throw new IllegalArgumentException(s"Token ${t.label}: its details (decimals) could not be loaded from the " +
            "explorer, so the amount cannot be read safely. Refresh the balance and try again.")
          val raw = Ergo.rawTokenAmount(String.valueOf(tokenModel.getValueAt(r, 1)), t.decimals)
          if (raw <= 0) throw new IllegalArgumentException("Token amounts must be positive")
          id -> raw
        }.groupBy(_._1).map { case (id, vs) => id -> vs.map(_._2).sum }
        val typed = if (amount.getText.trim.isEmpty) 0L else Ergo.nanoErg(amount.getText)
        // a box must hold some ERG: token-only sends carry the minimum, like the reference wallet
        val nano = if (typed < Parameters.MinChangeValue && toks.nonEmpty) Parameters.MinChangeValue else typed
        if (nano < Parameters.MinChangeValue) throw new IllegalArgumentException(s"Amount must be at least ${Ergo.erg(Parameters.MinChangeValue)} ERG")
        Right((nano, feeNano(), toks))
      } catch {
        case _: ArithmeticException => Left("Too many decimal places")
        case e: RuntimeException => Left(e.getMessage)
      }
      parsed match {
        case Left(err) => Dialogs.error(frame, err)
        case Right((nano, feeValue, toks)) =>
          val fromAddrs = senders()
          val avail = boxesOf(fromAddrs)
          val mine = addresses(v.w).toSet
          val myBoxes = boxesById(v.w)
          bg("Preparing transaction") {
            val reduced = Ergo.prepareTx(client.bestNode, avail, fromAddrs.head, recipient, nano, toks, feeValue)
            (reduced, describe(Ergo.summarize(reduced), myBoxes, mine))
          } { case (reduced, desc) =>
            if (Dialogs.confirm(frame, txText(desc), "Confirm transaction"))
              signAndSubmit(v.w, reduced, _ => ui {
                amount.setText("")
                tokenModel.setRowCount(0)
              })
          }
      }
    }
  }

  // ---------------------------------------------------------------- receive

  private final class ReceivePanel(v: WalletView) {
    private val address = new JComboBox[String]
    private val amount = new JTextField(14)
    private val description = new JTextField(30)
    private val link = Dialogs.readOnlyField("")
    private val qr = new JLabel
    val panel = new JPanel(new BorderLayout)

    Dialogs.onChange(amount.getDocument)(render())
    Dialogs.onChange(description.getDocument)(render())
    address.addActionListener(_ => render())
    panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14))
    panel.add(form("Address", address, "Amount (ERG, optional)", amount, "Description", description, "Payment link", link,
      "", row(button("Copy address")(Dialogs.copy(current)), button("Copy link")(Dialogs.copy(link.getText))), null, qr), BorderLayout.NORTH)

    private def current: String = v.w.addresses(math.min(math.max(0, address.getSelectedIndex), v.w.addresses.size - 1)).address

    def update(): Unit = {
      val sel = math.max(0, address.getSelectedIndex)
      address.removeAllItems()
      v.w.addresses.foreach(a => address.addItem(s"${addressLabel(a)}  –  ${a.address}"))
      address.setSelectedIndex(math.min(sel, address.getItemCount - 1))
      render()
    }

    private def render(): Unit = if (address.getItemCount > 0) {
      val nano = try Some(if (amount.getText.trim.isEmpty) 0L else Ergo.nanoErg(amount.getText)) catch { case _: RuntimeException => None }
      nano match {
        case None =>
          link.setText("Invalid amount")
          showQr(None)
        case Some(n) =>
          val url = if (n == 0 && description.getText.trim.isEmpty) current else Ergo.paymentRequestUrl(current, n, description.getText)
          link.setText(url)
          showQr(try Some(Qr.encode(url, 300)) catch { case _: IllegalArgumentException => None })
          if (qr.getIcon == null) qr.setText("Too much data for a QR code: shorten the description (the link still works)")
      }
    }

    private def showQr(img: Option[java.awt.image.BufferedImage]): Unit = {
      qr.setIcon(img.map(new ImageIcon(_)).orNull)
      qr.setText(null)
    }
  }

  // ---------------------------------------------------------------- history

  private final class HistoryPanel(v: WalletView) {
    val panel = new JPanel(new BorderLayout(0, 8))
    private val model = Dialogs.table("Date", "ERG", "Tokens", "Confirmations", "Tx ID")
    private var rows = Vector.empty[Ergo.TxRow]
    private val fmt = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault)

    private val t = new JTable(model)
    t.getColumnModel.getColumn(4).setPreferredWidth(380)
    Dialogs.onDoubleClick(t)(r => details(rows(r), addresses(v.w).toSet))
    private val load = button("Load history") {
      val addrs = addresses(v.w)
      bg("Loading transactions") {
        val list = Ergo.history(store.data.settings.explorerApi, store.data.settings.nodes.head, addrs, 50)
        list.foreach(_.tokenDelta.keys.foreach(token))
        list
      } { list =>
        rows = list
        model.setRowCount(0)
        list.foreach { x =>
          val tk = x.tokenDelta.map { case (id, d) => s"${if (d > 0) "+" else ""}${Ergo.tokenAmount(d, token(id).decimals)} ${token(id).label}" }.mkString(", ")
          model.addRow(boxed(fmt.format(Instant.ofEpochMilli(x.timestamp)), (if (x.ergDelta > 0) "+" else "") + Ergo.erg(x.ergDelta),
            tk, x.confirmations, x.id))
        }
      }
    }
    panel.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14))
    panel.add(new JLabel(s"<html>History comes from the ${if (Ergo.Devnet) "node's address index" else "explorer"} " +
      "(display only, not NiPoPoW-verified). Double-click a row for details.</html>"), BorderLayout.NORTH)
    panel.add(new JScrollPane(t), BorderLayout.CENTER)
    panel.add(row(load), BorderLayout.SOUTH)

    private def details(x: Ergo.TxRow, mine: Set[String]): Unit = bg("Loading transaction") {
      val sb = new StringBuilder(s"Tx ${x.id}\nBlock height ${x.height}, ${x.confirmations} confirmations\n")
      for (side <- Seq("inputs", "outputs")) {
        sb ++= s"\n${side.toUpperCase(Locale.ROOT)}\n"
        for (b <- x.raw.getAsJsonArray(side).asScala.map(_.getAsJsonObject)) {
          val addr = if (b.has("address")) b.get("address").getAsString else "?"
          val who = if (addr == Ergo.FeeAddress) "miner fee" else (if (mine(addr)) "(you) " else "") + addr
          sb ++= s"  ${Ergo.erg(b.get("value").getAsLong)} ERG  $who\n"
          if (b.has("assets")) for (a <- b.getAsJsonArray("assets").asScala.map(_.getAsJsonObject)) {
            val t = token(a.get("tokenId").getAsString)
            sb ++= s"        + ${Ergo.tokenAmount(a.get("amount").getAsLong, t.decimals)} ${t.label}\n"
          }
        }
      }
      sb.toString
    } { text =>
      Dialogs.info(frame, form(null, txText(text), null,
        row(button("Open in explorer")(Dialogs.browse(Ergo.ExplorerWeb + "en/transactions/" + x.id)))), "Transaction")
    }
  }
}
