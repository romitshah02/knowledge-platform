package org.sunbird.qti

import scala.xml.{Elem, XML}
import java.io.File
import org.sunbird.common.exception.ClientException
import org.sunbird.telemetry.logger.TelemetryManager

case class QtiItemRef(itemIdentifier: String, position: Int)
case class QtiSection(sectionIdentifier: String, title: String, itemRefs: List[QtiItemRef])
case class QtiTestPart(testPartIdentifier: String, sections: List[QtiSection])
case class QtiTest(identifier: String, title: String, testParts: List[QtiTestPart])

class QtiTestParser {

  def parse(testFile: File): Either[String, QtiTest] = {
    try {
      if (!testFile.exists()) {
        return Left(s"Test file not found: ${testFile.getAbsolutePath}")
      }

      val xml = XML.loadFile(testFile)
      parseAssessmentTest(xml)
    } catch {
      case e: Exception =>
        TelemetryManager.error(s"Error parsing test ${testFile.getName}: ${e.getMessage}")
        Left(e.getMessage)
    }
  }

  private def parseAssessmentTest(xml: Elem): Either[String, QtiTest] = {
    val identifier = (xml \@ "identifier").trim
    if (identifier.isEmpty) {
      return Left("assessmentTest must have an identifier attribute")
    }

    val title = (xml \@ "title").trim

    // Parse testParts (ordered)
    val testParts = (xml \ "qti-test-part").zipWithIndex.flatMap { case (tp, idx) =>
      parseTestPart(tp.asInstanceOf[Elem], idx + 1)
    }.toList

    if (testParts.isEmpty) {
      return Left(s"Test $identifier has no testParts with sections/items")
    }

    Right(QtiTest(
      identifier = identifier,
      title = title,
      testParts = testParts
    ))
  }

  private def parseTestPart(testPart: Elem, position: Int): Option[QtiTestPart] = {
    val testPartId = (testPart \@ "identifier").trim
    if (testPartId.isEmpty) {
      TelemetryManager.warn("Test part has no identifier, skipping")
      return None
    }

    // Parse sections (ordered)
    val sections = (testPart \ "qti-assessment-section").zipWithIndex.flatMap { case (section, idx) =>
      parseSection(section.asInstanceOf[Elem], idx + 1)
    }.toList

    if (sections.isEmpty) {
      TelemetryManager.warn(s"Test part $testPartId has no sections, skipping")
      return None
    }

    Some(QtiTestPart(testPartId, sections))
  }

  private def parseSection(section: Elem, position: Int): Option[QtiSection] = {
    val sectionId = (section \@ "identifier").trim
    val title = (section \@ "title").trim

    // Recursively find all assessmentItemRef elements (handle nested sections)
    val itemRefs = findItemRefs(section)

    if (itemRefs.isEmpty) {
      TelemetryManager.warn(s"Section $sectionId has no item references, skipping")
      return None
    }

    Some(QtiSection(sectionId, title, itemRefs))
  }

  private def findItemRefs(elem: Elem): List[QtiItemRef] = {
    // Direct child itemRefs — identifier attribute matches the manifest resource identifier
    val direct = (elem \ "qti-assessment-item-ref").zipWithIndex.map { case (ref, idx) =>
      val itemId = (ref \@ "identifier").trim
      QtiItemRef(itemId, idx + 1)
    }.toList

    // Nested sections
    val nested = (elem \ "qti-assessment-section").zipWithIndex.flatMap { case (section, idx) =>
      findItemRefs(section.asInstanceOf[Elem]).zipWithIndex.map { case (ref, nestedIdx) =>
        QtiItemRef(ref.itemIdentifier, idx * 1000 + nestedIdx + 1)
      }
    }.toList

    direct ++ nested
  }
}
