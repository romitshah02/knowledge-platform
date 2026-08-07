package org.sunbird.qti

import org.scalatest.{FlatSpec, Matchers}

class QtiItemTransformerTest extends FlatSpec with Matchers {

  private val transformer = new QtiItemTransformer()

  private def passthroughItem(interactionType: String, markup: String = "<qti-markup/>"): QtiItem = {
    QtiItem(
      identifier = "item_1",
      body = "Some question body",
      stimulus = None,
      interaction = QtiInteraction(
        interactionType = interactionType,
        responseIdentifier = "RESPONSE",
        rawMarkup = Some(markup)
      ),
      responseDeclaration = None
    )
  }

  /** Hottext/Gap-Match/Inline-Choice/Media are structured (options/attributes), not passthrough. */
  private def structuredItem(interactionType: String, interaction: QtiInteraction): QtiItem = {
    QtiItem(
      identifier = "item_1",
      body = "Some question body",
      stimulus = None,
      interaction = interaction.copy(interactionType = interactionType, responseIdentifier = "RESPONSE"),
      responseDeclaration = None
    )
  }

  "transform" should "structure hottextInteraction into options + min/maxChoices, not passthrough markup" in {
    val item = structuredItem(
      QtiConstants.HOTTEXT_INTERACTION,
      QtiInteraction(
        interactionType = QtiConstants.HOTTEXT_INTERACTION,
        options = List(QtiChoiceOption("H1", "sat"), QtiChoiceOption("H2", "a")),
        minChoices = Some(1),
        maxChoices = Some(1)
      )
    )
    val result = transformer.transform(item)
    result shouldBe a[Right[_, _]]
    val metadata = result.right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_HOTTEXT
    metadata.qType shouldBe "HOTTEXT"
    metadata.interactionTypes shouldBe List("hottext")
    val interaction = metadata.interactions("RESPONSE").asInstanceOf[Map[String, AnyRef]]
    interaction("options") shouldBe List(Map("value" -> "H1", "label" -> "sat"), Map("value" -> "H2", "label" -> "a"))
    interaction("minChoices") shouldBe 1
    interaction("maxChoices") shouldBe 1
    interaction.contains("markup") shouldBe false
  }

  it should "structure gapMatchInteraction into a choice-pool options list, not passthrough markup" in {
    val item = structuredItem(
      QtiConstants.GAP_MATCH_INTERACTION,
      QtiInteraction(
        interactionType = QtiConstants.GAP_MATCH_INTERACTION,
        options = List(QtiChoiceOption("C1", "cat"), QtiChoiceOption("C2", "moon"))
      )
    )
    val metadata = transformer.transform(item).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_GAP_MATCH
    metadata.qType shouldBe "GAP-MATCH"
    metadata.interactionTypes shouldBe List("gap-match")
    val interaction = metadata.interactions("RESPONSE").asInstanceOf[Map[String, AnyRef]]
    interaction("options") shouldBe List(Map("value" -> "C1", "label" -> "cat"), Map("value" -> "C2", "label" -> "moon"))
    interaction.contains("markup") shouldBe false
  }

  it should "structure inlineChoiceInteraction as a MCQ-shaped 'choice' interaction, not passthrough markup" in {
    val item = structuredItem(
      QtiConstants.INLINE_CHOICE_INTERACTION,
      QtiInteraction(
        interactionType = QtiConstants.INLINE_CHOICE_INTERACTION,
        options = List(QtiChoiceOption("A", "sat"), QtiChoiceOption("B", "ran"))
      )
    )
    val metadata = transformer.transform(item).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_INLINE_CHOICE
    metadata.qType shouldBe "INLINE-CHOICE"
    metadata.interactionTypes shouldBe List("inline-choice")
    val interaction = metadata.interactions("RESPONSE").asInstanceOf[Map[String, AnyRef]]
    // Same "choice" type tag as MCQ — InlineChoiceQuestion reuses McqQuestion's contract player-side.
    interaction("type") shouldBe "choice"
    interaction("options") shouldBe List(Map("value" -> "A", "label" -> "sat"), Map("value" -> "B", "label" -> "ran"))
  }

  it should "map hotspotInteraction to the canvas interactionType" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.HOTSPOT_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_HOTSPOT
    metadata.qType shouldBe "HOTSPOT"
    metadata.interactionTypes shouldBe List("canvas")
  }

  it should "map sliderInteraction onto its own Slider Question category" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.SLIDER_INTERACTION)).right.get
    metadata.primaryCategory shouldBe "Slider Question"
    metadata.qType shouldBe "SLIDER"
    metadata.interactionTypes shouldBe List("slider")
  }

  it should "map uploadInteraction onto the file-upload interactionType" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.UPLOAD_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_UPLOAD
    metadata.qType shouldBe "UPLOAD"
    metadata.interactionTypes shouldBe List("file-upload")
  }

  it should "map selectPointInteraction to the canvas interactionType" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.SELECT_POINT_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_SELECT_POINT
    metadata.qType shouldBe "SELECT-POINT"
    metadata.interactionTypes shouldBe List("canvas")
  }

  it should "map positionObjectInteraction to the canvas interactionType" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.POSITION_OBJECT_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_POSITION_OBJECT
    metadata.qType shouldBe "POSITION-OBJECT"
    metadata.interactionTypes shouldBe List("canvas")
  }

  it should "map graphicGapMatchInteraction to the canvas interactionType" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.GRAPHIC_GAP_MATCH_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_GRAPHIC_GAP_MATCH
    metadata.qType shouldBe "GRAPHIC-GAP-MATCH"
    metadata.interactionTypes shouldBe List("canvas")
  }

  it should "map graphicOrderInteraction to the canvas interactionType" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.GRAPHIC_ORDER_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_GRAPHIC_ORDER
    metadata.qType shouldBe "GRAPHIC-ORDER"
    metadata.interactionTypes shouldBe List("canvas")
  }

  it should "map graphicAssociateInteraction to the canvas interactionType" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.GRAPHIC_ASSOCIATE_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_GRAPHIC_ASSOCIATE
    metadata.qType shouldBe "GRAPHIC-ASSOCIATE"
    metadata.interactionTypes shouldBe List("canvas")
  }

  it should "structure mediaInteraction's min/maxPlays, autostart, loop; body carries the <video> markup" in {
    val item = structuredItem(
      QtiConstants.MEDIA_INTERACTION,
      QtiInteraction(
        interactionType = QtiConstants.MEDIA_INTERACTION,
        minPlays = Some(1),
        maxPlays = Some(0),
        autostart = Some(false),
        loop = Some(false)
      )
    )
    val metadata = transformer.transform(item).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_MEDIA
    metadata.qType shouldBe "MEDIA"
    metadata.interactionTypes shouldBe List("media")
    val interaction = metadata.interactions("RESPONSE").asInstanceOf[Map[String, AnyRef]]
    interaction("minPlays") shouldBe 1
    interaction("maxPlays") shouldBe 0
    interaction("autostart") shouldBe false
    interaction("loop") shouldBe false
    interaction.contains("markup") shouldBe false
  }

  it should "map drawingInteraction to the canvas interactionType (no Citolab component, amp-up-io fallback)" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.DRAWING_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_DRAWING
    metadata.qType shouldBe "DRAWING"
    metadata.interactionTypes shouldBe List("canvas")
  }

  it should "reject a passthrough interaction with no markup captured" in {
    val result = transformer.transform(passthroughItem(QtiConstants.HOTSPOT_INTERACTION, markup = ""))
    result shouldBe a[Left[_, _]]
  }

  it should "reject hottext/gap-match/inline-choice interactions with no options extracted" in {
    transformer.transform(structuredItem(QtiConstants.HOTTEXT_INTERACTION, QtiInteraction(interactionType = QtiConstants.HOTTEXT_INTERACTION))) shouldBe a[Left[_, _]]
    transformer.transform(structuredItem(QtiConstants.GAP_MATCH_INTERACTION, QtiInteraction(interactionType = QtiConstants.GAP_MATCH_INTERACTION))) shouldBe a[Left[_, _]]
    transformer.transform(structuredItem(QtiConstants.INLINE_CHOICE_INTERACTION, QtiInteraction(interactionType = QtiConstants.INLINE_CHOICE_INTERACTION))) shouldBe a[Left[_, _]]
  }

  it should "still reject unsupported interaction types outside the Phase 1 six and Phase 2 gap set" in {
    val result = transformer.transform(passthroughItem("qti-custom-interaction"))
    result shouldBe a[Left[_, _]]
  }
}