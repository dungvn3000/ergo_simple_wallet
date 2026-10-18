package vn.erg.wallet

import java.awt._
import java.awt.datatransfer.StringSelection
import java.awt.event.{MouseAdapter, MouseEvent, WindowAdapter, WindowEvent}
import java.io.{ByteArrayInputStream, File}
import java.net.URI
import java.util.{Arrays, Locale}

import javax.imageio.ImageIO
import javax.swing._
import javax.swing.event.{DocumentEvent, DocumentListener}
import javax.swing.table.DefaultTableModel
import javax.swing.text.Document

import scala.util.control.NonFatal

/** Reusable Swing dialogs and form helpers. */
object Dialogs {

  // ---------------------------------------------------------------- layout helpers

  /** Two-column form: label, component, label, component, ... (a null label lets the component span both columns). */
  def form(labelsAndFields: Any*): JPanel = {
    val p = new JPanel(new GridBagLayout)
    val in = new Insets(4, 0, 4, 8)
    labelsAndFields.grouped(2).zipWithIndex.foreach { case (pair, row) =>
      val label = pair.head
      val field = pair(1).asInstanceOf[Component]
      label match {
        case null =>
        case c: Component => p.add(c, new GridBagConstraints(0, row, 1, 1, 0, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE, in, 0, 0))
        case s => p.add(new JLabel(s.toString), new GridBagConstraints(0, row, 1, 1, 0, 0, GridBagConstraints.NORTHWEST, GridBagConstraints.NONE, in, 0, 0))
      }
      val (x, w) = if (label == null) (0, 2) else (1, 1)
      p.add(field, new GridBagConstraints(x, row, w, 1, 1, 0, GridBagConstraints.WEST, GridBagConstraints.HORIZONTAL, in, 0, 0))
    }
    p
  }

  def row(cs: Component*): JPanel = {
    val p = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0))
    cs.foreach(c => p.add(c))
    p
  }

  def button(text: String)(action: => Unit): JButton = {
    val b = new JButton(text)
    b.addActionListener(_ => action)
    b
  }

  def title(text: String): JLabel = {
    val l = new JLabel(text)
    l.putClientProperty("FlatLaf.styleClass", "h2")
    l
  }

  def textArea(text: String, rows: Int, editable: Boolean): JTextArea = {
    val t = new JTextArea(text, rows, 50)
    t.setLineWrap(true)
    t.setWrapStyleWord(true)
    t.setEditable(editable)
    t
  }

  def readOnlyField(text: String): JTextField = {
    val f = new JTextField(text)
    f.setEditable(false)
    f
  }

  def confirm(owner: Component, message: Any, title: String): Boolean =
    JOptionPane.showConfirmDialog(owner, message, title, JOptionPane.OK_CANCEL_OPTION, JOptionPane.PLAIN_MESSAGE) == JOptionPane.OK_OPTION

  def error(owner: Component, msg: String): Unit = JOptionPane.showMessageDialog(owner, msg, "Error", JOptionPane.ERROR_MESSAGE)

  def info(owner: Component, msg: Any, title: String): Unit = JOptionPane.showMessageDialog(owner, msg, title, JOptionPane.PLAIN_MESSAGE)

  def copy(text: String): Unit = Toolkit.getDefaultToolkit.getSystemClipboard.setContents(new StringSelection(text), null)

  def browse(url: String): Unit = try Desktop.getDesktop.browse(URI.create(url)) catch { case NonFatal(_) => copy(url) }

  /**
   * Window that owns a dialog. getWindowAncestor(frame) is null for a top-level window: an unowned modal dialog can
   * slip behind the main window (Windows/Linux) and the blocked app looks frozen.
   */
  private[wallet] def windowOf(c: Component): Window = c match {
    case w: Window => w
    case null => null
    case other => SwingUtilities.getWindowAncestor(other)
  }

  def onChange(doc: Document)(f: => Unit): Unit = doc.addDocumentListener(new DocumentListener {
    def insertUpdate(e: DocumentEvent): Unit = f
    def removeUpdate(e: DocumentEvent): Unit = f
    def changedUpdate(e: DocumentEvent): Unit = f
  })

  def onDoubleClick(t: JTable)(action: Int => Unit): Unit = t.addMouseListener(new MouseAdapter {
    override def mouseClicked(e: MouseEvent): Unit = {
      val r = t.getSelectedRow
      if (e.getClickCount == 2 && r >= 0) action(t.convertRowIndexToModel(r))
    }
  })

  /** A table model whose cells are read-only. */
  def table(cols: String*): DefaultTableModel = new DefaultTableModel(cols.toArray[AnyRef], 0) {
    override def isCellEditable(r: Int, c: Int): Boolean = false
  }

  // ---------------------------------------------------------------- passwords

  /** The password, or None if cancelled. The field is wiped before returning. */
  def password(owner: Component, label: String, title: String): Option[Array[Char]] = {
    val pf = new JPasswordField(24)
    val pane = new JOptionPane(form(label, pf), JOptionPane.PLAIN_MESSAGE, JOptionPane.OK_CANCEL_OPTION)
    val d = pane.createDialog(owner, title)
    d.addWindowFocusListener(new WindowAdapter {
      override def windowGainedFocus(e: WindowEvent): Unit = pf.requestFocusInWindow()
    })
    d.setVisible(true)
    val pw = if (pane.getValue == Int.box(JOptionPane.OK_OPTION)) Some(pf.getPassword) else None
    wipe(pf)
    pw
  }

  /** New password entered twice; must pass [[Store.strength]]. None if cancelled. Fields are wiped. */
  def newPassword(owner: Component, title: String, message: String): Option[Array[Char]] = {
    val p1 = new JPasswordField(24)
    val p2 = new JPasswordField(24)
    val meter = new JProgressBar(0, 4)
    val hint = new JLabel(" ")
    onChange(p1.getDocument) {
      val pw = p1.getPassword
      val st = Store.strength(pw)
      Arrays.fill(pw, '\u0000')
      meter.setValue(st.score)
      meter.setForeground(if (st.problem.isEmpty) new Color(0x2e7d32) else if (st.score >= 2) new Color(0xef6c00) else new Color(0xc62828))
      hint.setText(st.problem.getOrElse("Strong enough"))
    }
    val content = form(null, new JLabel(s"<html><div style='width:380px'>$message<br><br>At least ${Store.MinPassword} characters " +
      "and hard to guess. A few random words work well.</div></html>"),
      "Password", p1, "Strength", meter, "", hint, "Repeat password", p2)
    try {
      var result: Option[Array[Char]] = None
      while (result.isEmpty && confirm(owner, content, title)) {
        val a = p1.getPassword
        val b = p2.getPassword
        Store.strength(a).problem match {
          case Some(problem) => error(owner, problem)
          case None if !Arrays.equals(a, b) => error(owner, "Passwords do not match")
          case None => result = Some(a.clone())
        }
        Arrays.fill(a, '\u0000')
        Arrays.fill(b, '\u0000')
      }
      result
    } finally {
      wipe(p1)
      wipe(p2)
    }
  }

  // ---------------------------------------------------------------- QR

  /** Shows a QR code with copy and save. */
  def showQr(owner: Component, title: String, caption: String, data: String): Unit = {
    val save = button("Save PNG…") {
      val fc = new JFileChooser
      fc.setSelectedFile(new File("qr.png"))
      if (fc.showSaveDialog(owner) == JFileChooser.APPROVE_OPTION)
        try ImageIO.write(Qr.encode(data, 600), "png", fc.getSelectedFile) catch { case NonFatal(e) => error(owner, e.getMessage) }
    }
    val p = new JPanel(new BorderLayout(0, 8))
    p.add(new JLabel(s"<html><div style='width:420px'>$caption</div></html>"), BorderLayout.NORTH)
    p.add(new JLabel(new ImageIcon(Qr.encode(data, 420))), BorderLayout.CENTER)
    p.add(row(button("Copy as text")(copy(data)), save), BorderLayout.SOUTH)
    info(owner, p, title)
  }

  /** Reads a link from pasted text, a QR image file or the clipboard. None if cancelled. */
  def scan(owner: Component, title: String, hint: String): Option[String] = {
    val text = textArea("", 6, editable = true)
    val status = new JLabel(" ")
    var result: Option[String] = None
    val d = new JDialog(windowOf(owner), title, Dialog.ModalityType.APPLICATION_MODAL)
    def accept(s: String): Unit = if (s != null && s.trim.nonEmpty) {
      result = Some(s.trim)
      d.dispose()
    }
    val file = button("Load QR image…") {
      val fc = new JFileChooser
      if (fc.showOpenDialog(d) == JFileChooser.APPROVE_OPTION)
        try {
          val img = ImageIO.read(fc.getSelectedFile)
          if (img == null) throw new IllegalArgumentException(s"${fc.getSelectedFile.getName} is not an image")
          accept(Qr.decode(img))
        } catch { case NonFatal(e) => status.setText(e.getMessage) }
    }
    val clip = button("From clipboard") {
      try Qr.fromClipboard match {
        case Some(s) => accept(s)
        case None => status.setText("Clipboard has no text or image")
      } catch { case NonFatal(e) => status.setText(e.getMessage) }
    }
    val p = new JPanel(new BorderLayout(0, 8))
    p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))
    p.add(new JLabel(s"<html><div style='width:460px'>$hint</div></html>"), BorderLayout.NORTH)
    p.add(new JScrollPane(text), BorderLayout.CENTER)
    val south = new JPanel(new BorderLayout)
    south.add(status, BorderLayout.NORTH)
    south.add(row(file, clip, button("OK")(accept(text.getText)), button("Cancel")(d.dispose())), BorderLayout.SOUTH)
    p.add(south, BorderLayout.SOUTH)
    d.setContentPane(p)
    d.pack()
    d.setLocationRelativeTo(owner)
    d.setVisible(true)
    result
  }

  // ---------------------------------------------------------------- wallet creation

  // Mnemonics never pass through java.lang.String: they are painted from Array[Char] and typed into password
  // fields (whose getPassword returns Array[Char]). Swing's document buffer is overwritten before disposal.

  /** Paints a numbered word grid straight from the Array[Char] phrase. */
  def mnemonicView(phrase: Array[Char]): JComponent = {
    val words = Bip39.wordRanges(phrase)
    val c = new JComponent {
      override protected def paintComponent(g: Graphics): Unit = {
        val g2 = g.asInstanceOf[Graphics2D]
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setColor(UIManager.getColor("Label.foreground"))
        g2.setFont(getFont)
        val fm = g2.getFontMetrics
        for (((start, end), i) <- words.zipWithIndex) {
          val x = (i % 3) * 160 + 8
          val y = (i / 3) * (fm.getHeight + 8) + fm.getAscent + 8
          g2.drawString(s"${i + 1}.", x, y)
          g2.drawChars(phrase, start, end - start, x + 32, y)
        }
      }
    }
    c.setFont(UIManager.getFont("Label.font").deriveFont(16f))
    val fm = c.getFontMetrics(c.getFont)
    c.setPreferredSize(new Dimension(3 * 160 + 16, ((words.size + 2) / 3) * (fm.getHeight + 8) + 16))
    c
  }

  /** A visible (non-masked) field whose content is read as Array[Char]. */
  def secretField(columns: Int): JPasswordField = {
    val f = new JPasswordField(columns)
    f.setEchoChar(0.toChar)
    f
  }

  /** Best effort: overwrite the document buffer in place, then clear it. */
  def wipe(f: JPasswordField): Unit = {
    val n = f.getDocument.getLength
    if (n > 0) f.setText("\u0000" * n)
    f.setText("")
  }

  /** Shows a fresh 15-word mnemonic. None (and wiped) if cancelled. */
  def createMnemonic(owner: Component): Option[Array[Char]] = {
    val phrase = Bip39.generate(15)
    if (confirm(owner, form(null, new JLabel("<html><div style='width:460px'>Write down these 15 words in order. " +
      "They are the only way to restore your wallet. Anyone who knows them can spend your funds.</div></html>"),
      null, mnemonicView(phrase)), "New wallet – your mnemonic")) Some(phrase)
    else {
      Arrays.fill(phrase, '\u0000')
      None
    }
  }

  final case class Restore(mnemonic: Array[Char], deprecatedDerivation: Boolean)

  def restoreMnemonic(owner: Component): Option[Restore] = {
    val field = secretField(60)
    val deprecated = new JCheckBox("Pre-1627 key derivation (wallets restored with old Yoroi / node versions)")
    val content = form(null, new JLabel("Enter your mnemonic (12–24 words, separated by spaces)"), null, field, null, deprecated)
    try {
      var result: Option[Restore] = None
      while (result.isEmpty && confirm(owner, content, "Restore wallet")) {
        val typed = field.getPassword
        try result = Some(Restore(Bip39.normalize(typed), deprecated.isSelected))
        catch { case e: IllegalArgumentException => error(owner, e.getMessage) }
        finally Arrays.fill(typed, '\u0000')
      }
      result
    } finally wipe(field)
  }

  // ---------------------------------------------------------------- address book

  /** Manage contacts; in choose mode returns the picked address (own wallet addresses included). */
  def addressBook(owner: Component, store: Store, choose: Boolean, ownAddresses: Seq[(String, String)]): Option[String] = {
    val model = table("Label", "Address")
    def reload(): Unit = {
      model.setRowCount(0)
      store.data.contacts.foreach(c => model.addRow(Array[AnyRef](c.label, c.address)))
      if (choose) ownAddresses.foreach { case (label, address) => model.addRow(Array[AnyRef](label, address)) }
    }
    reload()
    val t = new JTable(model)
    t.getColumnModel.getColumn(0).setPreferredWidth(140)
    t.getColumnModel.getColumn(1).setPreferredWidth(460)
    var picked: Option[String] = None
    val d = new JDialog(windowOf(owner), "Address book", Dialog.ModalityType.APPLICATION_MODAL)
    def pick(): Unit = {
      val r = t.getSelectedRow
      if (choose && r >= 0) {
        picked = Some(model.getValueAt(r, 1).asInstanceOf[String])
        d.dispose()
      }
    }
    def selectedContact: Option[Int] = Option(t.getSelectedRow).filter(r => r >= 0 && r < store.data.contacts.size)
    onDoubleClick(t)(_ => pick())
    val add = button("Add…") {
      editContact(d, Store.Contact("", "")).foreach { c =>
        store.data.contacts += c
        store.save()
        reload()
      }
    }
    val edit = button("Edit…") {
      selectedContact.foreach(r => editContact(d, store.data.contacts(r)).foreach { c =>
        store.data.contacts(r) = c
        store.save()
        reload()
      })
    }
    val del = button("Delete") {
      selectedContact.filter(r => confirm(d, s"Delete ${store.data.contacts(r).label}?", "Delete contact")).foreach { r =>
        store.data.contacts.remove(r)
        store.save()
        reload()
      }
    }
    val p = new JPanel(new BorderLayout(0, 8))
    p.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12))
    val sp = new JScrollPane(t)
    sp.setPreferredSize(new Dimension(640, 300))
    p.add(sp, BorderLayout.CENTER)
    p.add(if (choose) row(add, edit, del, button("Choose")(pick()), button("Cancel")(d.dispose()))
          else row(add, edit, del, button("Close")(d.dispose())), BorderLayout.SOUTH)
    d.setContentPane(p)
    d.pack()
    d.setLocationRelativeTo(owner)
    d.setVisible(true)
    picked
  }

  private def editContact(owner: Component, c: Store.Contact): Option[Store.Contact] = {
    val label = new JTextField(c.label, 20)
    val address = new JTextField(c.address, 40)
    var result: Option[Store.Contact] = None
    while (result.isEmpty && confirm(owner, form("Label", label, "Address", address), "Contact")) {
      if (label.getText.trim.isEmpty) error(owner, "Enter a label")
      else if (!Ergo.isValidAddress(address.getText)) error(owner, "Invalid mainnet address")
      else result = Some(Store.Contact(label.getText.trim, address.getText.trim))
    }
    result
  }

  // ---------------------------------------------------------------- settings

  def settings(owner: Component, store: Store): Boolean = {
    val s = store.data.settings
    val nodes = textArea(s.nodes.mkString("\n"), 5, editable = true)
    val explorer = new JTextField(s.explorerApi, 30)
    val fiat = new JComboBox[String](Array("usd", "eur", "gbp", "jpy", "cny", "krw", "vnd", "btc", "eth"))
    fiat.setEditable(true)
    fiat.setSelectedItem(s.fiat)
    val content = form(null, new JLabel("<html><div style='width:420px'>Nodes are queried for NiPoPoW proofs; the best " +
      "valid chain wins. At least one should have the extra address index enabled.</div></html>"),
      "Nodes (one per line)", new JScrollPane(nodes), "Explorer API", explorer, "Fiat currency", fiat)
    var saved = false
    var done = false
    while (!done && confirm(owner, content, "Settings")) {
      val list = nodes.getText.split("\\s+").map(_.trim).filter(_.nonEmpty).map(_.replaceAll("/+$", "")).toVector
      val explorerUrl = explorer.getText.trim.replaceAll("/+$", "")
      val bad = (list :+ explorerUrl).filterNot(Http.isHttpUrl)
      if (list.isEmpty) error(owner, "Add at least one node")
      else if (bad.nonEmpty) error(owner, "Not a valid URL (must start with http:// or https://):\n" + bad.mkString("\n"))
      else {
        s.nodes = list
        s.explorerApi = explorerUrl
        s.fiat = String.valueOf(fiat.getSelectedItem).trim.toLowerCase(Locale.ROOT)
        store.save()
        saved = true
        done = true
      }
    }
    saved
  }

  // ---------------------------------------------------------------- token info

  def tokenInfo(owner: Component, t: Ergo.TokenInfo, ownedRaw: Long, background: (() => Unit) => Unit): Unit = {
    val image = new JLabel
    val p = form("Name", readOnlyField(t.name.getOrElse("(unknown)")),
      "Token ID", readOnlyField(t.id),
      "You own", readOnlyField(Ergo.tokenAmount(ownedRaw, t.decimals)),
      "Total supply", readOnlyField(Ergo.tokenAmount(t.emission, t.decimals)),
      "Decimals", readOnlyField(t.decimals.toString),
      "Description", new JScrollPane(textArea(t.description.getOrElse(""), 4, editable = false)),
      "Type", readOnlyField(t.nftType.getOrElse("Token")),
      null, image)
    t.contentLink.foreach { link =>
      val url = if (link.startsWith("ipfs://")) "https://ipfs.io/ipfs/" + link.substring(7) else link
      image.setText(s"Loading content… $url")
      background { () =>
        try {
          val img = if (t.nftType.contains("NFT_PICTURE")) Option(ImageIO.read(new ByteArrayInputStream(Http.getBytes(url)))) else None
          SwingUtilities.invokeLater { () =>
            img match {
              case Some(i) =>
                val scale = math.min(1.0, 360.0 / math.max(i.getWidth, i.getHeight))
                image.setIcon(new ImageIcon(i.getScaledInstance((i.getWidth * scale).toInt, (i.getHeight * scale).toInt, Image.SCALE_SMOOTH)))
                image.setText(null)
              case None => image.setText(s"Content: $url") // plain text: the link comes from the token's creator
            }
            Option(SwingUtilities.getWindowAncestor(image)).foreach(_.pack())
          }
        } catch {
          case NonFatal(_) => SwingUtilities.invokeLater(() => image.setText(s"Could not load $url"))
        }
      }
    }
    val outer = new JPanel(new BorderLayout(0, 8))
    outer.add(p, BorderLayout.CENTER)
    outer.add(row(button("Open in explorer")(browse(Ergo.ExplorerWeb + "en/token/" + t.id))), BorderLayout.SOUTH)
    info(owner, outer, "Token")
  }
}
