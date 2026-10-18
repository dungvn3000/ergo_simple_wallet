package vn.erg.wallet

import java.awt.geom.Path2D
import java.awt.{Dimension, Graphics, Graphics2D, RenderingHints}
import javax.swing.{JComponent, Timer, UIManager}

/**
 * The Ergo logo: an octagon that turns while `spinning` (counter-clockwise, one turn per 3 s) around a fixed sigma,
 * as in ergo-wallet-app. Path data from its ic_ergologo_bg / ic_ergologo_sigma drawables (Apache-2.0).
 */
final class ErgoLogo(size: Int) extends JComponent {
  import ErgoLogo._

  private var angle = 0.0
  private var started = 0L
  private val timer = new Timer(16, _ => {
    angle = -((System.currentTimeMillis - started) % TurnMs) * 360.0 / TurnMs
    repaint()
  })

  setPreferredSize(new Dimension(size, size))
  setOpaque(false)

  def spinning(on: Boolean): Unit = if (on != timer.isRunning) {
    if (on) {
      started = System.currentTimeMillis
      timer.start()
    } else {
      timer.stop()
      angle = 0
      repaint()
    }
  }

  override protected def paintComponent(g: Graphics): Unit = {
    val g2 = g.create().asInstanceOf[Graphics2D]
    try {
      g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
      val s = math.min(getWidth, getHeight) / ViewBox
      g2.translate((getWidth - ViewBox * s) / 2, (getHeight - ViewBox * s) / 2)
      g2.scale(s, s)
      g2.setColor(UIManager.getColor("Label.foreground"))
      g2.fill(Sigma)
      g2.rotate(math.toRadians(angle), ViewBox / 2, ViewBox / 2)
      g2.fill(Octagon)
    } finally g2.dispose()
  }
}

object ErgoLogo {
  private val TurnMs = 3000
  private val ViewBox = 1080.0

  val Octagon: Path2D = path("M545.5,1079.98c-2.1,0 -4.26,-0.21 -6.39,-0.63 -2.1,-0.4 -4.18,-1.01 -6.18,-1.81l-359.57,-144.35c-4.16,-1.67 " +
    "-7.91,-4.13 -11.04,-7.21 -3.13,-3.06 -5.66,-6.74 -7.39,-10.87l-152.21,-356.33c-0.87,-2.05 -1.54,-4.17 -1.99,-6.33 -0.45,-2.14 " +
    "-0.69,-4.35 -0.71,-6.58l-0.02,-0.39c0.02,-2.18 0.23,-4.33 0.64,-6.41 0.41,-2.09 1.02,-4.16 1.83,-6.17l144.34,-359.54c1.67,-4.17 " +
    "4.13,-7.92 7.21,-11.06 3.06,-3.12 6.75,-5.66 10.87,-7.39l356.33,-152.19c2.05,-0.88 4.17,-1.54 6.33,-1.99 2.15,-0.45 4.37,-0.69 " +
    "6.6,-0.71 0.05,-0.03 0.55,-0.03 0.6,0 2.14,0.02 4.24,0.23 6.27,0.63 2.05,0.41 4.08,1.01 6.07,1.8l359.56,144.34c4.16,1.67 7.91,4.13 " +
    "11.05,7.22 3.12,3.06 5.65,6.74 7.39,10.87l152.2,356.29c1.76,4.12 2.67,8.51 2.71,12.92 0.04,4.39 -0.77,8.8 -2.46,12.96l-144.34,359.54c" +
    "-1.66,4.15 -4.13,7.91 -7.21,11.05 -3.06,3.13 -6.74,5.66 -10.87,7.4l-356.29,152.19c-2.13,0.91 -4.31,1.6 -6.5,2.04 -2.3,0.48 -4.59,0.71 " +
    "-6.83,0.71zM231.67,855.21l313.15,125.69 310.32,-132.52 125.74,-313.15 -132.52,-310.32 -313.16,-125.74 -310.41,132.57 -125.7,313.14 " +
    "132.58,310.33z")

  val Sigma: Path2D = path("M505.72,678.52l209.01,0l0,97.12l-349.45,0l0,-88.28l137.51,-152.22l-136.85,-142.61l0,-88.52l348.79,0l0,97.11" +
    "l-204.56,0l126.83,132.82z")

  /** Minimal SVG path-data reader (M, L, H, V, C, Z, absolute and relative): just enough for the logo paths. */
  def path(d: String): Path2D = {
    val p = new Path2D.Double(Path2D.WIND_NON_ZERO)
    val tokens = "[MmLlHhVvCcZz]|-?(?:\\d+\\.?\\d*|\\.\\d+)(?:[eE]-?\\d+)?".r.findAllIn(d).toVector
    var i = 0
    var cmd = 'M'
    var (x, y, startX, startY) = (0.0, 0.0, 0.0, 0.0)
    def num(): Double = { i += 1; tokens(i - 1).toDouble }
    while (i < tokens.size) {
      if (tokens(i).head.isLetter) { cmd = tokens(i).head; i += 1 }
      val (bx, by) = if (cmd.isLower) (x, y) else (0.0, 0.0)
      cmd.toUpper match {
        case 'Z' =>
          p.closePath()
          x = startX
          y = startY
        case 'M' =>
          x = bx + num(); y = by + num()
          p.moveTo(x, y)
          startX = x; startY = y
          cmd = if (cmd.isLower) 'l' else 'L' // further coordinate pairs are line-tos
        case 'L' =>
          x = bx + num(); y = by + num()
          p.lineTo(x, y)
        case 'H' =>
          x = bx + num()
          p.lineTo(x, y)
        case 'V' =>
          y = by + num()
          p.lineTo(x, y)
        case 'C' =>
          val (x1, y1, x2, y2) = (bx + num(), by + num(), bx + num(), by + num())
          x = bx + num(); y = by + num()
          p.curveTo(x1, y1, x2, y2, x, y)
        case other => throw new IllegalArgumentException(s"Unsupported path command $other")
      }
    }
    p
  }
}
