package li.cil.oc.client.renderer.markdown

import li.cil.oc.api
import li.cil.oc.client.renderer.markdown.segment.InteractiveSegment
import li.cil.oc.client.renderer.markdown.segment.Segment
import li.cil.oc.util.RenderState
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.FontRenderer
import org.lwjgl.opengl.GL11

import scala.util.matching.Regex

/**
 * Primitive Markdown parser, only supports a very small subset. Used for
 * parsing documentation into segments, to be displayed in a GUI somewhere.
 */
object Document {
  /**
   * Parses a plain text document into a list of segments.
   */
  def parse(document: Iterable[String]): Iterable[Segment] = {
    var segments = document.flatMap(line => Iterable(new segment.TextSegment(null, Option(line).getOrElse("")), new segment.NewLineSegment())).toArray
    for ((pattern, factory) <- segmentTypes) {
      segments = segments.flatMap(_.refine(pattern, factory))
    }
    for (Array(s1, s2) <- segments.sliding(2)) {
      s2.previous = s1
    }
    segments
  }

  /**
   * Renders a list of segments and tooltips if a segment with a tooltip is hovered.
   * Returns a link address if a link is hovered.
   */
  def render(document: Iterable[Segment], x: Int, y: Int, maxWidth: Int, maxHeight: Int, yOffset: Int, renderer: FontRenderer, mouseX: Int, mouseY: Int): Option[InteractiveSegment] = {
    val mc = Minecraft.getMinecraft

    RenderState.pushAttrib()

    // On some systems/drivers/graphics cards the next calls won't update the
    // depth buffer correctly if alpha test is enabled. Guess how we found out?
    // By noticing that on those systems it only worked while chat messages
    // were visible. Yeah. I know.
    GL11.glDisable(GL11.GL_ALPHA_TEST)

    // Clear depth mask, then create masks in foreground above and below scroll area.
    RenderState.color(1, 1, 1, 1)
    GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT)
    RenderState.enableDepth()
    RenderState.depthFunc(GL11.GL_LEQUAL)
    RenderState.enableDepthMask()
    RenderState.disableColorMask()

    RenderState.pushMatrix()
    GL11.glTranslatef(0, 0, 500)
    GL11.glBegin(GL11.GL_QUADS)
    GL11.glVertex2f(0, y)
    GL11.glVertex2f(mc.displayWidth, y)
    GL11.glVertex2f(mc.displayWidth, 0)
    GL11.glVertex2f(0, 0)
    GL11.glVertex2f(0, mc.displayHeight)
    GL11.glVertex2f(mc.displayWidth, mc.displayHeight)
    GL11.glVertex2f(mc.displayWidth, y + maxHeight)
    GL11.glVertex2f(0, y + maxHeight)
    GL11.glEnd()
    RenderState.popMatrix()
    RenderState.enableColorMask()

    // Actual rendering.
    var hovered: Option[InteractiveSegment] = None
    var currentX = 0
    var currentY = 0
    for (segment <- document) {
      val result = segment.render(x, y + currentY - yOffset, currentX, maxWidth, y, maxHeight - (currentY - yOffset), renderer, mouseX, mouseY)
      hovered = hovered.orElse(result)
      currentY += segment.height(currentX, maxWidth, renderer)
      currentX = segment.width(currentX, maxWidth, renderer)
    }
    if (mouseX < x || mouseX > x + maxWidth || mouseY < y || mouseY > y + maxHeight) hovered = None
    hovered.foreach(_.notifyHover())

    RenderState.popAttrib()
    RenderState.bindTexture(0)

    hovered
  }

  /**
   * Compute the overall height of a document, for computation of scroll offsets.
   */
  def height(document: Iterable[Segment], maxWidth: Int, renderer: FontRenderer): Int = {
    var currentX = 0
    var currentY = 0
    for (segment <- document) {
      currentY += segment.height(currentX, maxWidth, renderer)
      currentX = segment.width(currentX, maxWidth, renderer)
    }
    currentY
  }

  /**
   * Line height for a normal line of text.
   */
  def lineHeight(renderer: FontRenderer): Int = renderer.FONT_HEIGHT + 1

  // ----------------------------------------------------------------------- //

  private def HeaderSegment(s: Segment, m: Regex.Match) = new segment.HeaderSegment(s, m.group(2), m.group(1).length)

  private def CodeSegment(s: Segment, m: Regex.Match) = new segment.CodeSegment(s, m.group(2))

  private def LinkSegment(s: Segment, m: Regex.Match) = new segment.LinkSegment(s, m.group(1), m.group(2))

  private def BoldSegment(s: Segment, m: Regex.Match) = new segment.BoldSegment(s, m.group(2))

  private def ItalicSegment(s: Segment, m: Regex.Match) = new segment.ItalicSegment(s, m.group(2))

  private def StrikethroughSegment(s: Segment, m: Regex.Match) = new segment.StrikethroughSegment(s, m.group(1))

  private def ImageSegment(s: Segment, m: Regex.Match) = {
    try Option(api.Manual.imageFor(m.group(2))) match {
      case Some(renderer) => new segment.RenderSegment(s, m.group(1), renderer)
      case _ => new segment.TextSegment(s, "No renderer found for: " + m.group(2))
    } catch {
      case t: Throwable => new segment.TextSegment(s, Option(t.toString).getOrElse("Unknown error."))
    }
  }

  // ----------------------------------------------------------------------- //

  private val segmentTypes = Array(
    """^(#+)\s(.*)""".r -> HeaderSegment _, // headers: # ...
    """(`)(.*?)\1""".r -> CodeSegment _, // code: `...`
    """!\[([^\[]*)\]\(([^\)]+)\)""".r -> ImageSegment _, // images: ![...](...)
    """\[([^\[]+)\]\(([^\)]+)\)""".r -> LinkSegment _, // links: [...](...)
    """(\*\*|__)(\S.*?\S|$)\1""".r -> BoldSegment _, // bold: **...** | __...__
    """(\*|_)(\S.*?\S|$)\1""".r -> ItalicSegment _, // italic: *...* | _..._
    """~~(\S.*?\S|$)~~""".r -> StrikethroughSegment _ // strikethrough: ~~...~~
  )
}