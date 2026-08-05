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
}
