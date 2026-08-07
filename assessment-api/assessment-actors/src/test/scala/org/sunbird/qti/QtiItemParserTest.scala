package org.sunbird.qti

import org.scalatest.{FlatSpec, Matchers}

import java.io.File
import java.nio.file.Files

class QtiItemParserTest extends FlatSpec with Matchers {

  private val parser = new QtiItemParser()

  private def writeItem(xml: String): File = {
    val file = Files.createTempFile("qti-item", ".xml").toFile
    Files.write(file.toPath, xml.getBytes("UTF-8"))
    file.deleteOnExit()
    file
  }

  "parse" should "capture the raw markup of a Phase 2 gap-type interaction for passthrough" in {
    val xml =
      """<qti-assessment-item identifier="item_hotspot">
        |  <qti-response-declaration identifier="RESPONSE" cardinality="single" base-type="identifier">
        |    <qti-correct-response><qti-value>A</qti-value></qti-correct-response>
        |  </qti-response-declaration>
        |  <qti-item-body>
        |    <p>Click the capital.</p>
        |    <qti-hotspot-interaction response-identifier="RESPONSE">
        |      <qti-hotspot-choice identifier="A" shape="circle" coords="10,10,5"/>
        |    </qti-hotspot-interaction>
        |  </qti-item-body>
        |</qti-assessment-item>""".stripMargin

    val result = parser.parse(writeItem(xml))
    result shouldBe a[Right[_, _]]
    val item = result.right.get
    item.interaction.interactionType shouldBe QtiConstants.HOTSPOT_INTERACTION
    item.interaction.rawMarkup shouldBe defined
    item.interaction.rawMarkup.get should include("qti-hotspot-choice")
  }

  "parseStimulus" should "extract the qti-stimulus-body HTML from a standalone stimulus document" in {
    val xml =
      """<qti-assessment-stimulus identifier="stim_night" title="The Unbelievable Night">
        |  <qti-stimulus-body>
        |    <p>It was a dark and <em>unbelievable</em> night.</p>
        |  </qti-stimulus-body>
        |</qti-assessment-stimulus>""".stripMargin

    val result = parser.parseStimulus(writeItem(xml))
    result shouldBe a[Right[_, _]]
    val (html, refs) = result.right.get
    html should include("It was a dark and <em>unbelievable</em> night.")
    refs shouldBe empty
  }

  it should "collect local media refs from a stimulus body alongside its HTML" in {
    val xml =
      """<qti-assessment-stimulus identifier="stim_night" title="The Unbelievable Night">
        |  <qti-stimulus-body>
        |    <h2>An Unbelievable Night</h2>
        |    <img src="images/title.png" alt="title" />
        |  </qti-stimulus-body>
        |</qti-assessment-stimulus>""".stripMargin

    val (_, refs) = parser.parseStimulus(writeItem(xml)).right.get
    refs shouldBe List("images/title.png")
  }

  it should "fail when the stimulus document has no qti-stimulus-body" in {
    val xml = """<qti-assessment-stimulus identifier="stim_empty" title="Empty"/>"""
    parser.parseStimulus(writeItem(xml)) shouldBe a[Left[_, _]]
  }

  "parse" should "resolve a qti-assessment-stimulus-ref against the package-level stimulusMap" in {
    val xml =
      """<qti-assessment-item identifier="item_with_stimulus">
        |  <qti-response-declaration identifier="RESPONSE" cardinality="single" base-type="identifier">
        |    <qti-correct-response><qti-value>A</qti-value></qti-correct-response>
        |  </qti-response-declaration>
        |  <qti-assessment-stimulus-ref identifier="stim_night" href="stim_night.xml"/>
        |  <qti-item-body>
        |    <p>Where did the crocodile come from?</p>
        |    <qti-choice-interaction response-identifier="RESPONSE">
        |      <qti-simple-choice identifier="A">The river</qti-simple-choice>
        |      <qti-simple-choice identifier="B">The zoo</qti-simple-choice>
        |    </qti-choice-interaction>
        |  </qti-item-body>
        |</qti-assessment-item>""".stripMargin

    val stimulusMap = Map("stim_night" -> ("<p>It was a dark and unbelievable night.</p>", List("images/title.png")))
    val item = parser.parse(writeItem(xml), stimulusMap).right.get
    item.stimulus shouldBe Some("<p>It was a dark and unbelievable night.</p>")
    item.mediaRefs should contain("images/title.png")
  }

  it should "leave stimulus empty when the ref's identifier is missing from the stimulusMap" in {
    val xml =
      """<qti-assessment-item identifier="item_missing_stimulus">
        |  <qti-response-declaration identifier="RESPONSE" cardinality="single" base-type="identifier">
        |    <qti-correct-response><qti-value>A</qti-value></qti-correct-response>
        |  </qti-response-declaration>
        |  <qti-assessment-stimulus-ref identifier="stim_unresolved" href="stim_unresolved.xml"/>
        |  <qti-item-body>
        |    <p>Question text.</p>
        |    <qti-choice-interaction response-identifier="RESPONSE">
        |      <qti-simple-choice identifier="A">Alpha</qti-simple-choice>
        |    </qti-choice-interaction>
        |  </qti-item-body>
        |</qti-assessment-item>""".stripMargin

    val item = parser.parse(writeItem(xml)).right.get
    item.stimulus shouldBe None
  }

  it should "not attach passthrough markup to a Phase 1 structured interaction" in {
    val xml =
      """<qti-assessment-item identifier="item_choice">
        |  <qti-response-declaration identifier="RESPONSE" cardinality="single" base-type="identifier">
        |    <qti-correct-response><qti-value>A</qti-value></qti-correct-response>
        |  </qti-response-declaration>
        |  <qti-item-body>
        |    <p>Pick one.</p>
        |    <qti-choice-interaction response-identifier="RESPONSE">
        |      <qti-simple-choice identifier="A">Alpha</qti-simple-choice>
        |      <qti-simple-choice identifier="B">Beta</qti-simple-choice>
        |    </qti-choice-interaction>
        |  </qti-item-body>
        |</qti-assessment-item>""".stripMargin

    val item = parser.parse(writeItem(xml)).right.get
    item.interaction.rawMarkup shouldBe None
  }

  it should "structure hottextInteraction into options + inline body tokens, not passthrough" in {
    val xml =
      """<qti-assessment-item identifier="item_hottext">
        |  <qti-response-declaration identifier="RESPONSE" cardinality="single" base-type="identifier">
        |    <qti-correct-response><qti-value>H1</qti-value></qti-correct-response>
        |  </qti-response-declaration>
        |  <qti-item-body>
        |    <qti-hottext-interaction response-identifier="RESPONSE" min-choices="1" max-choices="1">
        |      <qti-prompt>Select the error.</qti-prompt>
        |      <p>The cat <qti-hottext identifier="H1">sat</qti-hottext> on <qti-hottext identifier="H2">a</qti-hottext> mat.</p>
        |    </qti-hottext-interaction>
        |  </qti-item-body>
        |</qti-assessment-item>""".stripMargin

    val item = parser.parse(writeItem(xml)).right.get
    item.interaction.interactionType shouldBe QtiConstants.HOTTEXT_INTERACTION
    item.interaction.rawMarkup shouldBe None
    item.interaction.options should contain theSameElementsAs List(
      QtiChoiceOption("H1", "sat"),
      QtiChoiceOption("H2", "a")
    )
    item.interaction.minChoices shouldBe Some(1)
    item.interaction.maxChoices shouldBe Some(1)
    item.body should include("[[hottext:H1]]sat[[/hottext:H1]]")
    item.body should include("[[hottext:H2]]a[[/hottext:H2]]")
    item.body shouldNot include("<qti-hottext")
  }

  it should "structure gapMatchInteraction into a choice pool + gap-target body tokens" in {
    val xml =
      """<qti-assessment-item identifier="item_gapmatch">
        |  <qti-response-declaration identifier="RESPONSE" cardinality="multiple" base-type="directedPair">
        |  </qti-response-declaration>
        |  <qti-item-body>
        |    <qti-gap-match-interaction response-identifier="RESPONSE">
        |      <p>Complete: the <qti-gap identifier="G1"/> jumped over the <qti-gap identifier="G2"/>.</p>
        |      <qti-gap-text identifier="C1" match-max="1">cat</qti-gap-text>
        |      <qti-gap-text identifier="C2" match-max="1">moon</qti-gap-text>
        |    </qti-gap-match-interaction>
        |  </qti-item-body>
        |</qti-assessment-item>""".stripMargin

    val item = parser.parse(writeItem(xml)).right.get
    item.interaction.interactionType shouldBe QtiConstants.GAP_MATCH_INTERACTION
    item.interaction.rawMarkup shouldBe None
    item.interaction.options should contain theSameElementsAs List(
      QtiChoiceOption("C1", "cat"),
      QtiChoiceOption("C2", "moon")
    )
    item.body should include("[[gap:G1]]")
    item.body should include("[[gap:G2]]")
    item.body shouldNot include("qti-gap-text")
  }

  it should "structure inlineChoiceInteraction, forcing the body/response key to response1" in {
    val xml =
      """<qti-assessment-item identifier="item_inlinechoice">
        |  <qti-response-declaration identifier="MYRESP" cardinality="single" base-type="identifier">
        |    <qti-correct-response><qti-value>A</qti-value></qti-correct-response>
        |  </qti-response-declaration>
        |  <qti-item-body>
        |    <p>The cat <qti-inline-choice-interaction response-identifier="MYRESP">
        |      <qti-inline-choice identifier="A">sat</qti-inline-choice>
        |      <qti-inline-choice identifier="B">ran</qti-inline-choice>
        |    </qti-inline-choice-interaction> on the mat.</p>
        |  </qti-item-body>
        |</qti-assessment-item>""".stripMargin

    val item = parser.parse(writeItem(xml)).right.get
    item.interaction.interactionType shouldBe QtiConstants.INLINE_CHOICE_INTERACTION
    item.interaction.responseIdentifier shouldBe "response1"
    item.interaction.options should contain theSameElementsAs List(
      QtiChoiceOption("A", "sat"),
      QtiChoiceOption("B", "ran")
    )
    item.body should include("[[response1]]")
    item.body shouldNot include("qti-inline-choice")
    // The response declaration is still looked up by the SOURCE identifier (MYRESP),
    // not the renamed response1 — this must not silently become None.
    item.responseDeclaration shouldBe defined
  }

  it should "structure mediaInteraction attributes, preserving the inner <video> as body" in {
    val xml =
      """<qti-assessment-item identifier="item_media">
        |  <qti-item-body>
        |    <qti-media-interaction response-identifier="RESPONSE" autostart="false" min-plays="1" max-plays="0" loop="false">
        |      <video width="320" height="240"><source src="clip.mp4" type="video/mp4"/></video>
        |    </qti-media-interaction>
        |  </qti-item-body>
        |</qti-assessment-item>""".stripMargin

    val item = parser.parse(writeItem(xml)).right.get
    item.interaction.interactionType shouldBe QtiConstants.MEDIA_INTERACTION
    item.interaction.rawMarkup shouldBe None
    item.interaction.minPlays shouldBe Some(1)
    item.interaction.maxPlays shouldBe Some(0)
    item.interaction.autostart shouldBe Some(false)
    item.interaction.loop shouldBe Some(false)
    item.body should include("<video")
    item.body should include("clip.mp4")
  }
}
