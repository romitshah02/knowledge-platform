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
  minChoices: Option[Int] = None, 
  minPlays: Option[Int] = None, 
  maxPlays: Option[Int] = None, 
  autostart: Option[Boolean] = None, 
  loop: Option[Boolean] = None,
  prompt: String = "",
  rawMarkup: Option[String] = None
)

case class QtiMappingEntry(value: String, score: Double, caseSensitive: Boolean = false, key: String = "")

case class QtiResponseDeclaration(
  cardinality: String,
  baseType: String,
  correctValue: Option[AnyRef],
  mapping: List[QtiMappingEntry] = List(),
  mappingUpperBound: Option[Double] = None
)

case class QtiItem(
  identifier: String,
  body: String,
  stimulus: Option[String] = None,
  interaction: QtiInteraction,
  responseDeclaration: Option[QtiResponseDeclaration],
  mediaRefs: List[String] = List(),
  responseProcessingTemplate: Option[String] = None,
  outcomeMaxScore: Option[Double] = None
)

class QtiItemParser {

  def parse(itemFile: File, stimulusMap: Map[String, (String, List[String])] = Map()): Either[String, QtiItem] = {
    try {
      if (!itemFile.exists()) {
        return Left(s"Item file not found: ${itemFile.getAbsolutePath}")
      }

      val xml = XML.loadFile(itemFile)
      parseAssessmentItem(xml, stimulusMap)
    } catch {
      case e: Exception =>
        TelemetryManager.error(s"Error parsing item ${itemFile.getName}: ${e.getMessage}")
        Left(e.getMessage)
    }
  }

  def parseStimulus(stimulusFile: File): Either[String, (String, List[String])] = {
    try {
      if (!stimulusFile.exists()) {
        return Left(s"Stimulus file not found: ${stimulusFile.getAbsolutePath}")
      }
      val xml = XML.loadFile(stimulusFile)
      (xml \ "qti-stimulus-body").headOption.map(_.asInstanceOf[Elem])
        .map(b => (serializeInner(b).trim, extractLocalMediaRefs(b)))
        .filter(_._1.nonEmpty)
        .toRight(s"Stimulus ${stimulusFile.getName} has no qti-stimulus-body")
    } catch {
      case e: Exception =>
        TelemetryManager.error(s"Error parsing stimulus ${stimulusFile.getName}: ${e.getMessage}")
        Left(e.getMessage)
    }
  }

  private def parseAssessmentItem(xml: Elem, stimulusMap: Map[String, (String, List[String])]): Either[String, QtiItem] = {
    val identifier = (xml \@ "identifier").trim
    if (identifier.isEmpty) {
      return Left("assessmentItem must have an identifier attribute")
    }

    val itemBodySeq = xml \ "qti-item-body"
    if (itemBodySeq.isEmpty) {
      return Left(s"Item $identifier has no qti-item-body")
    }
    val itemBody = itemBodySeq.head.asInstanceOf[Elem]

    // Find interaction first — stimulus/body serialization below needs to skip its
    // element specifically wherever it's nested, or the raw QTI tag leaks into the
    // rendered HTML verbatim (invisible if self-closing, duplicated content otherwise).
    val interaction = findInteraction(itemBody, identifier)
    if (interaction.isEmpty) {
      return Left(s"Item $identifier has no supported interaction")
    }

    val interactionNode = interaction.get

    // textEntry gets a body-token (matching FTB's [[responseN]] convention, same as
    // inline-choice below) so the blank renders inline where the interaction was;
    // everything else just drops its own interaction element from stimulus/body.
    val blankToken = interactionNode.label match {
      case QtiConstants.TEXT_ENTRY_INTERACTION => "[[response1]]"
      case _ => ""
    }

    val (stimulus, stimulusMediaRefs) = extractStimulus(xml, itemBody, stimulusMap, interactionNode, blankToken) match {
      case Some((html, refs)) => (Some(html), refs)
      case None => (None, List())
    }

    val bodyText = interactionNode.label match {
      case QtiConstants.HOTTEXT_INTERACTION | QtiConstants.GAP_MATCH_INTERACTION | QtiConstants.MEDIA_INTERACTION =>
        extractInteractionBody(interactionNode)
      case QtiConstants.INLINE_CHOICE_INTERACTION =>
        (itemBody \ "p").map(p => tokenizeInlineChoice(p)).mkString(" ").trim
      case _ =>
        (itemBody \ "p").map(p => serializeInnerSkipping(p, interactionNode, blankToken)).mkString(" ").trim
    }

    // Local (non-http/data-URI) image/audio/video references — resolved and uploaded
    // by QtiImportManager before transform; not rejected here. Includes the resolved
    // stimulus's own refs (uploaded relative to the stimulus file, not this item file).
    val mediaRefs = extractLocalMediaRefs(itemBody) ++ stimulusMediaRefs

    val responseId = interactionNode.label match {
      case QtiConstants.INLINE_CHOICE_INTERACTION | QtiConstants.TEXT_ENTRY_INTERACTION => "response1"
      case _ => (interactionNode \@ "response-identifier").trim
    }
    val responseDeclaration = extractResponseDeclarationData(xml, (interactionNode \@ "response-identifier").trim)
    val responseProcessingTemplate = extractResponseProcessingTemplate(xml)
    val outcomeMaxScore = extractOutcomeMaxScore(xml)

    val qtiInteraction = QtiInteraction(
      interactionType = interactionNode.label,
      responseIdentifier = responseId,
      options = extractOptions(interactionNode),
      matchSets = extractMatchSets(interactionNode),
      shuffle = (interactionNode \@ "shuffle").toLowerCase == "true",
      maxChoices = extractMaxChoices(interactionNode),
      minChoices = extractMinChoices(interactionNode),
      minPlays = extractMinPlays(interactionNode),
      maxPlays = extractMaxPlays(interactionNode),
      autostart = extractAutostart(interactionNode),
      loop = extractLoop(interactionNode),
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
      mediaRefs = mediaRefs,
      responseProcessingTemplate = responseProcessingTemplate,
      outcomeMaxScore = outcomeMaxScore
    ))
  }

  private def extractResponseProcessingTemplate(xml: Elem): Option[String] = {
    (xml \ "qti-response-processing").headOption
      .map(rp => (rp \@ "template").trim)
      .filter(_.toLowerCase.contains("map_response"))
      .map(_ => "MAP_RESPONSE")
  }

  private def extractOutcomeMaxScore(xml: Elem): Option[Double] = {
    (xml \ "qti-outcome-declaration")
      .find(d => (d \@ "identifier").trim.equalsIgnoreCase("SCORE"))
      .flatMap(d => (d \ "qti-default-value" \ "qti-value").headOption.map(_.text.trim))
      .flatMap(_.toDoubleOption)
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

  private def serializeInnerSkipping(node: scala.xml.Node, skip: Elem, replacement: String): String =
    node.child.map(c => serializeNodeSkipping(c, skip, replacement)).mkString

  private def serializeNodeSkipping(node: scala.xml.Node, skip: Elem, replacement: String): String = node match {
    case e: Elem if e eq skip => replacement
    case e: Elem =>
      val attrs = e.attributes.asAttrMap.map { case (k, v) => s"""$k="$v"""" }.mkString(" ")
      val openTag = if (attrs.nonEmpty) s"<${e.label} $attrs>" else s"<${e.label}>"
      s"$openTag${e.child.map(c => serializeNodeSkipping(c, skip, replacement)).mkString}</${e.label}>"
    case t: scala.xml.Text => t.text
    case other => other.text
  }

  private def extractStimulus(xml: Elem, itemBody: Elem, stimulusMap: Map[String, (String, List[String])], interactionNode: Elem, blankToken: String): Option[(String, List[String])] = {
    val fromRef = (xml \\ "qti-assessment-stimulus-ref").headOption
      .flatMap(ref => stimulusMap.get((ref \@ "identifier").trim))
    fromRef.orElse {
      val div = (itemBody \ "div").headOption
      div.map(d => serializeInnerSkipping(d, interactionNode, blankToken).trim).filter(_.nonEmpty).map(html => (html, List()))
    }
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

    val hottext = (itemBody \\ QtiConstants.HOTTEXT_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (hottext.isDefined) return hottext

    val gapMatch = (itemBody \\ QtiConstants.GAP_MATCH_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (gapMatch.isDefined) return gapMatch

    val inlineChoice = (itemBody \\ QtiConstants.INLINE_CHOICE_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (inlineChoice.isDefined) return inlineChoice

    val media = (itemBody \\ QtiConstants.MEDIA_INTERACTION).headOption.map(_.asInstanceOf[Elem])
    if (media.isDefined) return media

    // Remaining gap types with no structured mapping — passthrough only
    QtiConstants.PASSTHROUGH_INTERACTIONS.iterator
      .flatMap(name => (itemBody \\ name).headOption)
      .map(_.asInstanceOf[Elem])
      .nextOption()
  }

  private def extractOptions(interaction: Elem): List[QtiChoiceOption] = {
    interaction.label match {
      case QtiConstants.CHOICE_INTERACTION | QtiConstants.ORDER_INTERACTION =>
        (interaction \ "qti-simple-choice").map { c =>
          QtiChoiceOption((c \@ "identifier").trim, serializeInner(c).trim)
        }.toList
      case QtiConstants.HOTTEXT_INTERACTION =>
        (interaction \\ "qti-hottext").map { c =>
          QtiChoiceOption((c \@ "identifier").trim, serializeInner(c).trim)
        }.toList
      case QtiConstants.GAP_MATCH_INTERACTION =>
        ((interaction \ "qti-gap-text") ++ (interaction \ "qti-gap-img")).map { c =>
          QtiChoiceOption((c \@ "identifier").trim, serializeInner(c).trim)
        }.toList
      case QtiConstants.INLINE_CHOICE_INTERACTION =>
        (interaction \ "qti-inline-choice").map { c =>
          QtiChoiceOption((c \@ "identifier").trim, serializeInner(c).trim)
        }.toList
      case _ => List()
    }
  }

  private def extractMatchSets(interaction: Elem): Option[(List[QtiChoiceOption], List[QtiChoiceOption])] = {
    if (interaction.label == QtiConstants.MATCH_INTERACTION || interaction.label == QtiConstants.ASSOCIATE_INTERACTION) {
      val matchSets = (interaction \ "qti-simple-match-set").map { set =>
        (set \ "qti-simple-associable-choice").map { c =>
          QtiChoiceOption((c \@ "identifier").trim, serializeInner(c).trim)
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
    if (interaction.label == QtiConstants.CHOICE_INTERACTION || interaction.label == QtiConstants.HOTTEXT_INTERACTION) {
      val maxChoices = (interaction \@ "max-choices").trim
      if (maxChoices.nonEmpty) Some(maxChoices.toInt) else None
    } else {
      None
    }
  }

  private def extractMinChoices(interaction: Elem): Option[Int] = {
    if (interaction.label == QtiConstants.HOTTEXT_INTERACTION) {
      val minChoices = (interaction \@ "min-choices").trim
      if (minChoices.nonEmpty) Some(minChoices.toInt) else None
    } else {
      None
    }
  }

  private def extractMinPlays(interaction: Elem): Option[Int] = {
    if (interaction.label == QtiConstants.MEDIA_INTERACTION) {
      val minPlays = (interaction \@ "min-plays").trim
      if (minPlays.nonEmpty) Some(minPlays.toInt) else None
    } else {
      None
    }
  }

  private def extractMaxPlays(interaction: Elem): Option[Int] = {
    if (interaction.label == QtiConstants.MEDIA_INTERACTION) {
      val maxPlays = (interaction \@ "max-plays").trim
      if (maxPlays.nonEmpty) Some(maxPlays.toInt) else None
    } else {
      None
    }
  }

  private def extractAutostart(interaction: Elem): Option[Boolean] = {
    if (interaction.label == QtiConstants.MEDIA_INTERACTION) {
      val autostart = (interaction \@ "autostart").trim
      if (autostart.nonEmpty) Some(autostart.toLowerCase == "true") else None
    } else {
      None
    }
  }

  private def extractLoop(interaction: Elem): Option[Boolean] = {
    if (interaction.label == QtiConstants.MEDIA_INTERACTION) {
      val loop = (interaction \@ "loop").trim
      if (loop.nonEmpty) Some(loop.toLowerCase == "true") else None
    } else {
      None
    }
  }

  private def extractPrompt(interaction: Elem): String = {
    ((interaction \ "qti-prompt").headOption.map(p => serializeInner(p)) getOrElse "").trim
  }

  /**
   * Body content that lives INSIDE the interaction node itself (Hottext/Gap-Match's
   * prose wraps its own selectable/droppable elements; Media's <video>/<audio> is a
   * direct child) — as opposed to choice/order/match/textEntry/extendedText, whose
   * body comes from the outer item-body `<p>` scan. Excludes `qti-prompt` (handled
   * separately by extractPrompt) and, for Gap-Match, the choice-pool elements
   * (`qti-gap-text`/`qti-gap-img` — those become `options`, not body).
   * Substitutes inline tokens the player recognizes:
   *   - `qti-hottext`      -> [[hottext:ID]]...[[/hottext:ID]]
   *   - `qti-gap`          -> [[gap:ID]] (self-closing target)
   */
  private def extractInteractionBody(interaction: Elem): String = {
    def serialize(node: scala.xml.Node): String = node match {
      case e: Elem if e.label == "qti-prompt" || e.label == "qti-gap-text" || e.label == "qti-gap-img" => ""
      case e: Elem if e.label == "qti-hottext" =>
        s"[[hottext:${(e \@ "identifier").trim}]]${serializeInner(e)}[[/hottext:${(e \@ "identifier").trim}]]"
      case e: Elem if e.label == "qti-gap" =>
        s"[[gap:${(e \@ "identifier").trim}]]"
      case e: Elem =>
        val attrs = e.attributes.asAttrMap.map { case (k, v) => s"""$k="$v"""" }.mkString(" ")
        val openTag = if (attrs.nonEmpty) s"<${e.label} $attrs>" else s"<${e.label}>"
        s"$openTag${e.child.map(serialize).mkString}</${e.label}>"
      case t: scala.xml.Text => t.text
      case other => other.text
    }
    interaction.child.map(serialize).mkString.trim
  }

 private def tokenizeInlineChoice(node: scala.xml.Node): String = node match {
    case e: Elem if e.label == QtiConstants.INLINE_CHOICE_INTERACTION => "[[response1]]"
    case e: Elem =>
      val attrs = e.attributes.asAttrMap.map { case (k, v) => s"""$k="$v"""" }.mkString(" ")
      val openTag = if (attrs.nonEmpty) s"<${e.label} $attrs>" else s"<${e.label}>"
      s"$openTag${e.child.map(tokenizeInlineChoice).mkString}</${e.label}>"
    case t: scala.xml.Text => t.text
    case other => other.text
  }

  private def extractResponseDeclarationData(xml: Elem, responseId: String): Option[QtiResponseDeclaration] = {
    (xml \ "qti-response-declaration").find(r => (r \@ "identifier") == responseId).map { decl =>
      val cardinality = (decl \@ "cardinality").trim
      val baseType = (decl \@ "base-type").trim

      val isPairType = baseType == "directedPair" || baseType == "pair"

      val correctValues = (decl \ "qti-correct-response" \ "qti-value").map(_.text.trim).toList
      val correctValue: Option[AnyRef] = correctValues match {
        case Nil => None
        case values if isPairType =>
          val pairs = values.flatMap(_.split("\\s+").toList match {
            case left :: right :: Nil => Some(left -> right)
            case _ => None
          })
          if (pairs.isEmpty) None else Some(pairs.toMap.asInstanceOf[AnyRef])
        case single :: Nil if cardinality != "multiple" && cardinality != "ordered" =>
          Some(single.asInstanceOf[AnyRef])
        case multiple =>
          Some(multiple.asInstanceOf[AnyRef])
      }

      // For pair types, map-key is itself a "left right" pair (the correct pairing);
      // split it so the player can look up a match by left value ({key, value, score}).
      // For everything else (FTB), map-key is the plain accepted answer string.
      val mapping = (decl \ "qti-mapping" \ "qti-map-entry").map { entry =>
        val caseSensitiveAttr = (entry \@ "case-sensitive").trim.toLowerCase
        val mapKey = (entry \@ "map-key").trim
        val score = (entry \@ "mapped-value").trim.toDoubleOption.getOrElse(0.0)
        val caseSensitive = caseSensitiveAttr == "true"
        if (isPairType) {
          mapKey.split("\\s+").toList match {
            case left :: right :: Nil => QtiMappingEntry(value = right, score = score, caseSensitive = caseSensitive, key = left)
            case _ => QtiMappingEntry(value = mapKey, score = score, caseSensitive = caseSensitive)
          }
        } else {
          QtiMappingEntry(value = mapKey, score = score, caseSensitive = caseSensitive)
        }
      }.toList

      val mappingUpperBound = (decl \ "qti-mapping").headOption.flatMap(m => (m \@ "upper-bound").trim.toDoubleOption)

      QtiResponseDeclaration(
        cardinality = cardinality,
        baseType = baseType,
        correctValue = correctValue,
        mapping = mapping,
        mappingUpperBound = mappingUpperBound
      )
    }
  }

}
