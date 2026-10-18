package vn.erg.wallet

import java.awt.datatransfer.DataFlavor
import java.awt.image.BufferedImage
import java.awt.{Image, Toolkit}

import com.google.zxing._
import com.google.zxing.client.j2se.{BufferedImageLuminanceSource, MatrixToImageWriter}
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel

import scala.collection.JavaConverters._

/** QR encode/decode. Scanning is from image files or a clipboard screenshot instead of a camera. */
object Qr {
  def encode(text: String, size: Int): BufferedImage =
    try {
      val hints = Map[EncodeHintType, AnyRef](EncodeHintType.ERROR_CORRECTION -> ErrorCorrectionLevel.L, EncodeHintType.MARGIN -> Int.box(2))
      MatrixToImageWriter.toBufferedImage(new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, size, size, hints.asJava))
    } catch {
      case e: WriterException => throw new IllegalArgumentException("Data too large for a QR code", e)
    }

  def decode(img: BufferedImage): String =
    try {
      val hints = Map[DecodeHintType, AnyRef](DecodeHintType.TRY_HARDER -> java.lang.Boolean.TRUE,
        DecodeHintType.POSSIBLE_FORMATS -> java.util.Collections.singletonList(BarcodeFormat.QR_CODE))
      new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(img))), hints.asJava).getText
    } catch {
      case _: NotFoundException => throw new IllegalArgumentException("No QR code found in image")
    }

  /** Text or a QR image from the clipboard, decoded to text; None if neither. */
  def fromClipboard: Option[String] = {
    val cb = Toolkit.getDefaultToolkit.getSystemClipboard
    try {
      if (cb.isDataFlavorAvailable(DataFlavor.imageFlavor)) {
        val img = cb.getData(DataFlavor.imageFlavor).asInstanceOf[Image]
        val bi = new BufferedImage(img.getWidth(null), img.getHeight(null), BufferedImage.TYPE_INT_RGB)
        bi.getGraphics.drawImage(img, 0, 0, null)
        Some(decode(bi))
      } else if (cb.isDataFlavorAvailable(DataFlavor.stringFlavor)) Some(cb.getData(DataFlavor.stringFlavor).asInstanceOf[String].trim)
      else None
    } catch {
      case e: IllegalArgumentException => throw e
      case _: Exception => None
    }
  }
}
