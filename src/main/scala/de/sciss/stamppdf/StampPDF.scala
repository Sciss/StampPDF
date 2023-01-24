/*
 *  StampPDF.scala
 *  (Rogues)
 *
 *  Copyright (c) 2023 Hanns Holger Rutz. All rights reserved.
 *
 *  This software is published under the GNU Affero General Public License v3+
 *
 *
 *  For further information, please contact Hanns Holger Rutz at
 *  contact@sciss.de
 */

package de.sciss.stamppdf

import com.itextpdf.awt.PdfGraphics2D
import com.itextpdf.text.pdf.{PdfContentByte, PdfDocument, PdfReader, PdfWriter}
import com.itextpdf.text.{Document, Image}
import de.sciss.file.*
import org.rogach.scallop.{ScallopConf, ScallopOption as Opt}

import java.awt.event.{InputEvent, KeyEvent}
import java.awt.geom.AffineTransform
import java.awt.{GraphicsEnvironment, RenderingHints}
import java.io.FileOutputStream
import javax.imageio.ImageIO
import javax.imageio.metadata.IIOMetadataNode
import javax.swing.{ImageIcon, KeyStroke}
import scala.swing.event.{MouseDragged, MouseEvent, MouseMoved, MousePressed, MouseReleased, ValueChanged}
import scala.swing.{Action, Alignment, BorderPanel, Button, Component, Dimension, FileChooser, FlowPanel, Graphics2D, Label, MainFrame, Menu, MenuBar, MenuItem, Separator, Slider, Swing}

object StampPDF {
  case class Config(
                   input    : File          = file("input.pdf"),
                   stamp    : File          = file("stamp.jpg"),
                   stampDPI : Double        = 0.0,
                   page     : Int           = 1,
                   ui       : Boolean       = false,
                   x        : Double        = 0.0,
                   y        : Double        = 0.0,
                   scale    : Double        = 1.0,
                   output   : Option[File]  = None,
                   ) {

    def print: Seq[String] = {
      val b = Seq.newBuilder[String]
      b += "--input"
      b += input.path
      b += "--stamp"
      b += stamp.path
      if stampDPI != 0.0 then {
        b += "--stamp-dpi"
        b += f"$stampDPI%1.1f"
      }
      if page != 1 then {
        b += "--page"
        b += page.toString
      }
      if ui then {
        b += "--ui"
      }
      if x != 0.0 then {
        b += "--x"
        b += f"$x%1.1f"
      }
      if y != 0.0 then {
        b += "--y"
        b += f"$y%1.1f"
      }
      if scale != 1.0 then {
        b += "--scale"
        b += f"$scale%1.3f"
      }
      output.foreach { f =>
        b += "--output"
        b += f.path
      }
      b.result()
    }
  }

  def main(args: Array[String]): Unit = {

    object p extends ScallopConf(args) {
      import org.rogach.scallop.*

      printedName = "StampPDF"
      private val default = Config()

      val input: Opt[File] = opt(required = true,
        descr = "PDF input file",
      )
      val stamp: Opt[File] = opt(required = true,
        descr = "image stamp file",
      )
      val stampDPI: Opt[Double] = opt(default = Some(default.stampDPI), name = "stamp-dpi",
        descr = f"image stamp DPI or zero (default: ${default.stampDPI}%1.1f).",
        validate = _ >= 0.0
      )
      val page: Opt[Int] = opt(default = Some(default.page),
        descr = s"page number to stamp, negative to count from end (default: ${default.page}).",
        validate = _ != 0
      )
      val ui: Opt[Boolean] = toggle(default = Some(default.ui),
        descrYes = "Open user interface",
      )
      val x: Opt[Double] = opt(default = Some(default.x),
        descr = f"stamp X position, left to right, in mm (default: ${default.x}%1.1f).",
      )
      val y: Opt[Double] = opt(default = Some(default.y),
        descr = f"stamp Y position, top to bottom, in mm (default: ${default.y}%1.1f).",
      )
      val scale: Opt[Double] = opt(default = Some(default.scale),
        descr = f"scale factor for stamp (default: ${default.scale}%1.1f).",
      )

      verify()
      implicit val config: Config = Config(
        input     = input(),
        stamp     = stamp(),
        stampDPI  = stampDPI(),
        page      = page(),
        ui        = ui(),
        x         = x(),
        y         = y(),
        scale     = scale(),
      )
    }
    import p.config
    run()
  }

  private val mmPerUnit = 1.0/72 * 25.4

  def run()(implicit config: Config): Unit = {
    val r = new PdfReader(config.input.path)
    val numPages = r.getNumberOfPages
    if numPages < 1 then {
      Console.err.println("PDF is empty")
      sys.exit(1)
    }
    val pageNum = if config.page > 0 then config.page else numPages - config.page
    val sz = r.getPageSize(pageNum)
    r.close()
    println(sz.getLeft)
    println(sz.getTop - sz.getHeight)
//    println(sz.getWidth)
//    println(sz.getHeight)
    val wMM = sz.getWidth  * mmPerUnit
    val hMM = sz.getHeight * mmPerUnit
    println(f"PDF page size is W $wMM%1.1f mm, H $hMM%1.1f mm")

    val fPagePDFTmp = File.createTemp(suffix = ".pdf")
    val fPagePNGTmp = File.createTemp(suffix = ".png")

    var stampXMM = config.x
    var stampYMM = config.y
    var dragDxMM = 0.0
    var dragDyMM = 0.0

    val dpiStamp = if config.stampDPI > 0.0 then config.stampDPI else {
      val iis = ImageIO.createImageInputStream(config.stamp)
      try {
        val ir = ImageIO.getImageReaders(iis).next()
        ir.setInput(iis)
        val meta = ir.getImageMetadata(0)
        val root = meta.getAsTree("javax_imageio_1.0").asInstanceOf[IIOMetadataNode]
        val nodes = root.getElementsByTagName("HorizontalPixelSize")
        if nodes.getLength > 0 then {
          val dpcWidth = nodes.item(0).asInstanceOf[IIOMetadataNode]
          val nnm = dpcWidth.getAttributes
          val item = nnm.item(0)
          val res = 25.4 / java.lang.Float.parseFloat(item.getNodeValue)
          println(f"Stamp has DPI $res%1.1f")
          res
        } else {
          val res = 72.0
          println(s"Warning - no DPI found for stamp. Using $res")
          res
        }
      } finally {
        iis.close()
      }
    }

    val imgStamp        = ImageIO.read(config.stamp)
    val stampBaseScale  = 1.0 / dpiStamp
    var scaleStamp      = config.scale

    def renderStamp(g: Graphics2D, scale: Double, dx: Double, dy: Double): Unit = {
      val atOrig = g.getTransform
      val stampX = (stampXMM + dragDxMM) / 25.4
      val stampY = (stampYMM + dragDyMM) / 25.4
      g.translate(stampX * scale + dx, stampY * scale + dy)
      val scale2 = scaleStamp * stampBaseScale * scale
      g.drawImage(imgStamp, AffineTransform.getScaleInstance(scale2, scale2), null)
      g.setTransform(atOrig)
    }

    {
      import sys.process.*
      Seq("pdftk", config.input.path, "cat", pageNum.toString, "output", fPagePDFTmp.path).!
    }

    def write(fOutput: File): Unit = {
      val doc       = new Document(sz, 0f, 0f, 0f, 0f)
      val fStampPos = File.createTemp(suffix = ".pdf")
      val stream    = new FileOutputStream(fStampPos)
      val writer    = PdfWriter.getInstance(doc, stream)
      doc.open()
      try {
        val cb = writer.getDirectContent
        val tp = cb.createTemplate(sz.getWidth, sz.getHeight)
        val g  = new PdfGraphics2D(tp, sz.getWidth, sz.getHeight, true /*, fontMapper */)
//        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
        try {
          renderStamp(g, scale = 72.0, dx = sz.getLeft, dy = sz.getHeight - sz.getTop)  // XXX TODO where does this value come from?
        } finally {
          g.dispose()
        }
        cb.addTemplate(tp, 0, 0)
      } finally {
        doc.close()
      }

      {
        import sys.process._
        val fStamped = if numPages == 1 then fOutput else File.createTemp(suffix = ".pdf")
        Seq("pdftk", config.input.path, "stamp", fStampPos.path, "output", fStamped.path).!
        if numPages > 1 then {
          val fPre = File.createTemp(suffix = ".pdf")
          val hasPre = pageNum > 1
          if hasPre then {
            Seq("pdftk", config.input.path, "cat", s"1-${pageNum - 1}", "output", fPre.path).!
          }
          val fPost = File.createTemp(suffix = ".pdf")
          val hasPost = pageNum < numPages
          if hasPost then {
            Seq("pdftk", config.input.path, "cat", s"${pageNum + 1}-$numPages", "output", fPost.path).!
          }
          var seqIn = List.empty[String]
          if hasPost then seqIn = fPost.path :: seqIn
          seqIn = fStamped.path :: seqIn
          if hasPre then seqIn = fPre.path :: seqIn
          val cmd = "pdftk" :: seqIn ::: List("cat", "output", fOutput.path)
          cmd.!
        }
      }
    }

    def autoOutputFile: File =
      config.input.replaceName(s"${config.input.name}_sig.pdf")

    if !config.ui then {
      config.output match {
        case Some(fOutput) => write(fOutput)
        case None =>
          val fOutput = autoOutputFile
          if fOutput.exists() then {
            Console.err.println(s"No output given. Not overriding $fOutput")
            sys.exit(1)
          } else {
            write(fOutput)
          }
      }
    } else Swing.onEDT {
      val gEnv          = GraphicsEnvironment.getLocalGraphicsEnvironment
      val gDev          = gEnv.getDefaultScreenDevice
      val gConv         = gDev.getDefaultConfiguration
      val gBounds       = gConv.getBounds
      val bestWidthPx   = gBounds.width  * 0.8
      val bestHeightPx  = gBounds.height * 0.8
      val dpiWidth      = bestWidthPx  / (wMM / 25.4)
      val dpiHeight     = bestHeightPx / (hMM / 25.4)
      val density       = math.max(1, math.min(dpiWidth, dpiHeight).toInt)
      // println(s"DPI $density")

      {
        import sys.process.*
        Seq("convert", "-density", density.toString, fPagePDFTmp.path, fPagePNGTmp.path).!
      }

      val imgBase     = ImageIO.read(fPagePNGTmp)
      var lastOutput  = config.output

      object View extends Component {
        preferredSize = new Dimension(imgBase.getWidth, imgBase.getHeight)

        private var dragging    = false
        private var dragStartX  = 0
        private var dragStartY  = 0
        private var dragEndX    = 0
        private var dragEndY    = 0

        override protected def paintComponent(g: Graphics2D): Unit = {
          super.paintComponent(g)
          g.drawImage(imgBase, 0, 0, null)
          renderStamp(g, density, dx = 0.0, dy = 0.0)
        }

        private def checkDrag(m: MouseEvent): Unit = if dragging then {
          dragEndX = m.point.x
          dragEndY = m.point.y
          dragDxMM = (dragEndX - dragStartX) * 25.4 / density
          dragDyMM = (dragEndY - dragStartY) * 25.4 / density
          repaint()
        }

        reactions += {
          case m: MousePressed =>
            dragStartX = m.point.x
            dragStartY = m.point.y
            dragging = true
          case _: MouseReleased if dragging =>
            dragging = false
            stampXMM += dragDxMM
            stampYMM += dragDyMM
            dragDxMM  = 0.0
            dragDyMM  = 0.0
            repaint()
        }
        reactions += {
          case m: MouseMoved    => checkDrag(m)
          case m: MouseDragged  => checkDrag(m)
        }
        listenTo(mouse.clicks)
        listenTo(mouse.moves)
      }

      val ggScale = new Slider {
        min = 1
        max = 200
        value = math.round(config.scale * 100).toInt
        reactions += {
          case ValueChanged(_) =>
            scaleStamp = value / 100.0
            View.repaint()
        }
      }

      object actionSave extends Action("Save") {
        accelerator = Some(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK))

        override def apply(): Unit = lastOutput.foreach { fOutput =>
          write(fOutput)
        }
      }

      def checkActionSave(): Unit =
        actionSave.enabled = lastOutput.isDefined

      object actionSaveAs extends Action("Save As...") {
        accelerator = Some(KeyStroke.getKeyStroke(KeyEvent.VK_S, InputEvent.CTRL_DOWN_MASK | InputEvent.SHIFT_DOWN_MASK))

        override def apply(): Unit = {
          val fCh = new FileChooser
          fCh.title         = title
          fCh.selectedFile  = lastOutput.getOrElse(autoOutputFile)
          val res = fCh.showSaveDialog(null)
          if res == FileChooser.Result.Approve then {
            lastOutput = Option(fCh.selectedFile)
            write(fCh.selectedFile)
            checkActionSave()
          }
        }
      }
      object actionPrint extends Action("Print Command Line") {
        accelerator = Some(KeyStroke.getKeyStroke(KeyEvent.VK_P, InputEvent.CTRL_DOWN_MASK))

        override def apply(): Unit = {
          val configNew = config.copy(
            ui      = false,
            x       = stampXMM,
            y       = stampYMM,
            scale   = scaleStamp,
            output  = lastOutput,
          )
          val seq   = configNew.print
          val seqM  = seq.map { x =>
            if x.contains(" ") then s"'$x'" else x
          }
          println(seqM.mkString(" "))
        }
      }
      object actionQuit extends Action("Quit") {
        accelerator = Some(KeyStroke.getKeyStroke(KeyEvent.VK_Q, InputEvent.CTRL_DOWN_MASK))

        override def apply(): Unit = sys.exit()
      }

      checkActionSave()

      val pTop = new FlowPanel(new Label("Scale [%]:"), ggScale)

      new MainFrame {
        title = "Test"
        contents = new BorderPanel {
          add(pTop, BorderPanel.Position.North)
          add(View, BorderPanel.Position.Center)
        }
        pack().centerOnScreen()
        menuBar = new MenuBar {
          contents += new Menu("File") {
            contents += new MenuItem(actionSave)
            contents += new MenuItem(actionSaveAs)
            contents += new MenuItem(actionPrint)
            contents += new Separator
            contents += new MenuItem(actionQuit)
          }
        }
        open()
      }
    }
  }
}
