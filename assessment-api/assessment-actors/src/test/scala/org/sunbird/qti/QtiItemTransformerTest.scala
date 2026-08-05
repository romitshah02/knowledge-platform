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

  "transform" should "map hottextInteraction to the Hot Text Question category with passthrough markup" in {
    val result = transformer.transform(passthroughItem(QtiConstants.HOTTEXT_INTERACTION))
    result shouldBe a[Right[_, _]]
    val metadata = result.right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_HOTTEXT
    metadata.qType shouldBe "HOTTEXT"
    metadata.interactionTypes shouldBe List("hottext")
    metadata.interactions("RESPONSE").asInstanceOf[Map[String, AnyRef]]("markup") shouldBe "<qti-markup/>"
  }

  it should "map gapMatchInteraction to gap-match" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.GAP_MATCH_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_GAP_MATCH
    metadata.qType shouldBe "GAP-MATCH"
    metadata.interactionTypes shouldBe List("gap-match")
  }

  it should "map inlineChoiceInteraction to inline-choice" in {
    val metadata = transformer.transform(passthroughItem(QtiConstants.INLINE_CHOICE_INTERACTION)).right.get
    metadata.primaryCategory shouldBe QtiConstants.QTI_INLINE_CHOICE
    metadata.qType shouldBe "INLINE-CHOICE"
    metadata.interactionTypes shouldBe List("inline-choice")
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

  it should "reject a passthrough interaction with no markup captured" in {
    val result = transformer.transform(passthroughItem(QtiConstants.HOTTEXT_INTERACTION, markup = ""))
    result shouldBe a[Left[_, _]]
  }

  it should "still reject unsupported interaction types outside the Phase 1 six and Phase 2 gap set" in {
    val result = transformer.transform(passthroughItem("qti-custom-interaction"))
    result shouldBe a[Left[_, _]]
  }
}