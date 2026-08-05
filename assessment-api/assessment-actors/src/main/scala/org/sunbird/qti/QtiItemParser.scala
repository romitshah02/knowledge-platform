package org.sunbird.qti

import scala.xml.{Elem, XML}
import java.io.File
import org.sunbird.common.exception.ClientException
import org.sunbird.telemetry.logger.TelemetryManager

case class QtiChoiceOption(identifier: String, label: String)

case class QtiInteraction(
  interactionType: String,
  responseIdentifier: String = "",
  options: List[QtiChoiceOption] = List(),
  matchSets: Option[(List[QtiChoiceOption], List[QtiChoiceOption])] = None,
  shuffle: Boolean = false,
  maxChoices: Option[Int] = None,
  prompt: String = "",
  rawMarkup: Option[String] = None
)

case class QtiMappingEntry(value: String, score: Double, caseSensitive: Boolean = false)

case class QtiResponseDeclaration(
  cardinality: String,
  baseType: String,
  correctValue: Option[AnyRef],
  mapping: List[QtiMappingEntry] = List()
)

case class QtiItem(
  identifier: String,
  body: String,
  stimulus: Option[String] = None,
  interaction: QtiInteraction,
  responseDeclaration: Option[QtiResponseDeclaration],
  mediaRefs: List[String] = List()
)

class QtiItemParser {

  def parse(itemFile: File): Either[String, QtiItem] = {
    try {
      if (!itemFile.exists()) {
        return Left(s"Item file not found: ${itemFile.getAbsolutePath}")
      }

      val xml = XML.loadFile(itemFile)
      parseAssessmentItem(xml)
    } catch {
      case e: Exception =>
        TelemetryManager.error(s"Error parsing item ${itemFile.getName}: ${e.getMessage}")
        Left(e.getMessage)
    }
  }

  private def parseAssessmentItem(xml: Elem): Either[String, QtiItem] = {
    val identifier = (xml \@ "identifier").trim
    if (identifier.isEmpty) {
      return Left("assessmentItem must have an identifier attribute")
    }

    val itemBodySeq = xml \ "qti-item-body"
    if (itemBodySeq.isEmpty) {
      return Left(s"Item $identifier has no qti-item-body")
    }
    val itemBody = itemBodySeq.head.asInstanceOf[Elem]

    // Extract body content (markup preserved — img/b/i/div/MathML etc. survive as HTML,
    // not flattened to plain text, so an uploaded asset's URL has somewhere to land).
    val bodyText = (itemBody \ "p").map(p => serializeInner(p)).mkString(" ").trim
    val stimulus = extractStimulus(itemBody)

    // Find interaction
    val interaction = findInteraction(itemBody, identifier)
    if (interaction.isEmpty) {
      return Left(s"Item $identifier has no supported interaction")
    }

    val interactionNode = interaction.get

    // Local (non-http/data-URI) image/audio/video references — resolved and uploaded
    // by QtiImportManager before transform; not rejected here.
    val mediaRefs = extractLocalMediaRefs(itemBody)

    val responseId = (interactionNode \@ "response-identifier").trim
    val responseDeclaration = extractResponseDeclarationData(xml, responseId)

    val qtiInteraction = QtiInteraction(
      interactionType = interactionNode.label,
      responseIdentifier = responseId,
      options = extractOptions(interactionNode),
      matchSets = extractMatchSets(interactionNode),
      shuffle = (interactionNode \@ "shuffle").toLowerCase == "true",
      maxChoices = extractMaxChoices(interactionNode),
      prompt = extractPrompt(interactionNode),
      rawMarkup = if (QtiConstants.PASSTHROUGH_INTERACTIONS.contains(interactionNode.label))
        Some(interactionNode.toString.trim) else None
    )

    Right(QtiItem(
      identifier = identifier,
      body = bodyText,
      stimulus = stimulus,
      interaction = qtiInteraction,
      responseDeclaration = responseDeclaration,
      mediaRefs = mediaRefs
    ))
  }

  // Serializes a node's children back to an HTML-ish string — keeps img/b/i/div/MathML
  // structure intact (unlike `.text`, which flattens everything to plain text and drops
  // tags entirely). Strips XML namespace declarations/prefixes; keeps tag name + attributes.
  private def serializeInner(node: scala.xml.Node): String = node.child.map(serializeNode).mkString

  private def serializeNode(node: scala.xml.Node): String = node match {
    case e: Elem =>
      val attrs = e.attributes.asAttrMap.map { case (k, v) => s"""$k="$v"""" }.mkString(" ")
      val openTag = if (attrs.nonEmpty) s"<${e.label} $attrs>" else s"<${e.label}>"
      s"$openTag${e.child.map(serializeNode).mkString}</${e.label}>"
    case t: scala.xml.Text => t.text
    case other => other.text
  }

  private def extractStimulus(itemBody: Elem): Option[String] = {
    val div = (itemBody \ "div").headOption
    div.map(d => serializeInner(d).trim).filter(_.nonEmpty)
  }

  private def extractLocalMediaRefs(itemBody: Elem): List[String] = {
    val srcs = (itemBody \\ "img").map(n => (n \@ "src").trim) ++
      (itemBody \\ "audio").map(n => (n \@ "src").trim) ++
      (itemBody \\ "video").map(n => (n \@ "src").trim)
    srcs.filter(_.nonEmpty)
      .filterNot(src => src.startsWith("http://") || src.startsWith("https://") || src.startsWith("data:"))
      .distinct
      .toList
  }

  private def findInteraction(itemBody: Elem, itemId: String): Option[Elem] = {
    val choiceInteraction = (itemBody \\ QtiConstants.CHOICE_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (choiceInteraction.isDefined) return choiceInteraction

    val matchInteraction = (itemBody \\ QtiConstants.MATCH_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (matchInteraction.isDefined) return matchInteraction

    val associateInteraction = (itemBody \\ QtiConstants.ASSOCIATE_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (associateInteraction.isDefined) return associateInteraction

    val orderInteraction = (itemBody \\ QtiConstants.ORDER_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (orderInteraction.isDefined) return orderInteraction

    val textEntry = (itemBody \\ QtiConstants.TEXT_ENTRY_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (textEntry.isDefined) return textEntry

    val extendedText = (itemBody \\ QtiConstants.EXTENDED_TEXT_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (extendedText.isDefined) return extendedText

    // Gap types with no structured mapping — passthrough only
    QtiConstants.PASSTHROUGH_INTERACTIONS.iterator
      .flatMap(name => (itemBody \\ name).headOption)
      .map(_.asInstanceOf[Elem])
      .nextOption()
  }

  private def extractOptions(interaction: Elem): List[QtiChoiceOption] = {
    if (interaction.label == QtiConstants.CHOICE_INTERACTION || interaction.label == QtiConstants.ORDER_INTERACTION) {
      (interaction \ "qti-simple-choice").map { c =>
        QtiChoiceOption((c \@ "identifier").trim, c.text.trim)
      }.toList
    } else {
      List()
    }
  }

  private def extractMatchSets(interaction: Elem): Option[(List[QtiChoiceOption], List[QtiChoiceOption])] = {
    if (interaction.label == QtiConstants.MATCH_INTERACTION || interaction.label == QtiConstants.ASSOCIATE_INTERACTION) {
      val matchSets = (interaction \ "qti-simple-match-set").map { set =>
        (set \ "qti-simple-associable-choice").map { c =>
          QtiChoiceOption((c \@ "identifier").trim, c.text.trim)
        }.toList
      }
      matchSets.toList match {
        case left :: right :: _ => Some((left, right))
        case left :: Nil => Some((left, List()))
        case _ => None
      }
    } else {
      None
    }
  }

  private def extractMaxChoices(interaction: Elem): Option[Int] = {
    if (interaction.label == QtiConstants.CHOICE_INTERACTION) {
      val maxChoices = (interaction \@ "max-choices").trim
      if (maxChoices.nonEmpty) Some(maxChoices.toInt) else None
    } else {
      None
    }
  }

  private def extractPrompt(interaction: Elem): String = {
    ((interaction \ "qti-prompt").headOption.map(p => serializeInner(p)) getOrElse "").trim
  }

  private def extractResponseDeclarationData(xml: Elem, responseId: String): Option[QtiResponseDeclaration] = {
    (xml \ "qti-response-declaration").find(r => (r \@ "identifier") == responseId).map { decl =>
      val cardinality = (decl \@ "cardinality").trim
      val baseType = (decl \@ "base-type").trim

      val correctValues = (decl \ "qti-correct-response" \ "qti-value").map(_.text.trim).toList
      val correctValue: Option[AnyRef] = correctValues match {
        case Nil => None
        case single :: Nil if cardinality != "multiple" && cardinality != "ordered" =>
          if (baseType == "directedPair" || baseType == "pair") {
            Some(single.split("\\s+").toList.asInstanceOf[AnyRef])
          } else {
            Some(single.asInstanceOf[AnyRef])
          }
        case multiple =>
          if (baseType == "directedPair" || baseType == "pair") {
            Some(multiple.map(v => v.split("\\s+").toList).asInstanceOf[AnyRef])
          } else {
            Some(multiple.asInstanceOf[AnyRef])
          }
      }

      val mapping = (decl \ "qti-mapping" \ "qti-map-entry").map { entry =>
        val caseSensitiveAttr = (entry \@ "case-sensitive").trim.toLowerCase
        QtiMappingEntry(
          value = (entry \@ "map-key").trim,
          score = (entry \@ "mapped-value").trim.toDoubleOption.getOrElse(0.0),
          caseSensitive = caseSensitiveAttr == "true"
        )
      }.toList

      QtiResponseDeclaration(
        cardinality = cardinality,
        baseType = baseType,
        correctValue = correctValue,
        mapping = mapping
      )
    }
  }

}
