package org.sunbird.qti

import org.sunbird.telemetry.logger.TelemetryManager

case class TransformError(reason: String)
case class QuestionMetadata(
  identifier: String,
  primaryCategory: String,
  qType: String,
  interactionTypes: List[String],
  body: String,
  interactions: Map[String, AnyRef],
  responseDeclaration: Map[String, AnyRef],
  media: List[Map[String, AnyRef]] = List()
)

class QtiItemTransformer {

  def transform(item: QtiItem, mediaMap: Map[String, (String, String)] = Map()): Either[TransformError, QuestionMetadata] = {
    val categoryResult = mapInteractionToCategory(item.interaction)
    if (categoryResult.isEmpty) {
      return Left(TransformError(s"Unsupported interaction type: ${item.interaction.interactionType}"))
    }

    val (primaryCategory, qType) = categoryResult.get
    val interactionTypes = List(canonicalInteractionType(primaryCategory))

    // Build question body from stimulus + item-body text + the interaction's own prompt.
    val bodyText = List(item.stimulus.getOrElse(""), item.body, item.interaction.prompt)
      .filter(_.nonEmpty)
      .mkString("\n\n")

    if (bodyText.isEmpty) {
      return Left(TransformError(s"Item ${item.identifier} has no body, stimulus, or prompt content"))
    }

    // Validate interaction-specific constraints
    val validationError = validateInteraction(item.interaction)
    if (validationError.isDefined) {
      return Left(TransformError(validationError.get))
    }

    // Every local media ref this item has must have uploaded successfully — a dead
    val unresolvedRefs = item.mediaRefs.filterNot(mediaMap.contains)
    if (unresolvedRefs.nonEmpty) {
      return Left(TransformError(s"Item ${item.identifier} has unresolved media references: ${unresolvedRefs.mkString(", ")}"))
    }

    val bodyWithMedia = item.mediaRefs.foldLeft(bodyText) { (acc, ref) =>
      val (_, url) = mediaMap(ref)
      acc.replace(s""""$ref"""", s""""$url"""")
    }
    val media = item.mediaRefs.map { ref =>
      val (id, url) = mediaMap(ref)
      Map[String, AnyRef]("id" -> id, "type" -> mediaTypeFor(ref), "src" -> url)
    }

    val interactions = buildInteractions(item.interaction, interactionTypes.head)
    val responseDeclaration = buildResponseDeclaration(item.interaction, item.responseDeclaration)

    Right(QuestionMetadata(
      identifier = item.identifier,
      primaryCategory = primaryCategory,
      qType = qType,
      interactionTypes = interactionTypes,
      body = bodyWithMedia,
      interactions = interactions,
      responseDeclaration = responseDeclaration,
      media = media
    ))
  }

  private def mediaTypeFor(ref: String): String = {
    val lower = ref.toLowerCase
    if (lower.endsWith(".mp3") || lower.endsWith(".wav") || lower.endsWith(".ogg")) "audio"
    else if (lower.endsWith(".mp4") || lower.endsWith(".webm")) "video"
    else "image"
  }

  // Category-schema-enforced canonical interactionTypes vocabulary — keyed by primaryCategory,
  // not the raw QTI element name (scripts/definition-scripts/*.sh define these enums per category).
  private def canonicalInteractionType(primaryCategory: String): String = {
    primaryCategory match {
      case QtiConstants.MCQ => "choice"
      case QtiConstants.SA => "text"
      case QtiConstants.FTB => "text"
      case QtiConstants.MTF => "match"
      case QtiConstants.SEQ => "order"
      case QtiConstants.REO => "order"
      case QtiConstants.QTI_HOTTEXT => "hottext"
      case QtiConstants.QTI_GAP_MATCH => "gap-match"
      case QtiConstants.QTI_INLINE_CHOICE => "inline-choice"
      case QtiConstants.QTI_HOTSPOT => "canvas"
      case QtiConstants.QTI_SLIDER => "slider"
      case QtiConstants.QTI_UPLOAD => "file-upload"
      case _ => "choice"
    }
  }

  private def mapInteractionToCategory(interaction: QtiInteraction): Option[(String, String)] = {
    interaction.interactionType match {
      case QtiConstants.CHOICE_INTERACTION =>
        if (interaction.options.length == 2) Some((QtiConstants.BOOLEAN, "BOOL"))
        else Some((QtiConstants.MCQ, "MCQ"))
      case QtiConstants.TEXT_ENTRY_INTERACTION => Some((QtiConstants.SA, "SA"))
      case QtiConstants.EXTENDED_TEXT_INTERACTION => Some((QtiConstants.SA, "SA"))
      case QtiConstants.ORDER_INTERACTION => Some((QtiConstants.SEQ, "SEQ"))
      case QtiConstants.MATCH_INTERACTION => Some((QtiConstants.MTF, "MTF"))
      case QtiConstants.ASSOCIATE_INTERACTION => Some((QtiConstants.REO, "REO"))
      case QtiConstants.HOTTEXT_INTERACTION => Some((QtiConstants.QTI_HOTTEXT, "HOTTEXT"))
      case QtiConstants.GAP_MATCH_INTERACTION => Some((QtiConstants.QTI_GAP_MATCH, "GAP-MATCH"))
      case QtiConstants.INLINE_CHOICE_INTERACTION => Some((QtiConstants.QTI_INLINE_CHOICE, "INLINE-CHOICE"))
      case QtiConstants.HOTSPOT_INTERACTION => Some((QtiConstants.QTI_HOTSPOT, "HOTSPOT"))
      case QtiConstants.SLIDER_INTERACTION => Some((QtiConstants.QTI_SLIDER, "SLIDER"))
      case QtiConstants.UPLOAD_INTERACTION => Some((QtiConstants.QTI_UPLOAD, "UPLOAD"))
      case _ => None
    }
  }

  private def validateInteraction(interaction: QtiInteraction): Option[String] = {
    interaction.interactionType match {
      case QtiConstants.CHOICE_INTERACTION =>
        // Phase 1: single choice (maxChoices=1 or implicit) or 2-option (Boolean)
        if (interaction.options.isEmpty) {
          return Some(s"choiceInteraction has no simpleChoice elements")
        }
        if (interaction.options.length > 1 && interaction.maxChoices.exists(_ > 2)) {
          return Some(s"choiceInteraction with >2 choices not supported in Phase 1")
        }
        None

      case QtiConstants.MATCH_INTERACTION =>
        // Phase 1: 1:1 match only, no many-to-many
        None

      case QtiConstants.ASSOCIATE_INTERACTION =>
        // Phase 1: 1:1 associate only
        None

      case QtiConstants.ORDER_INTERACTION =>
        // Phase 1: any ordering allowed
        None

      case QtiConstants.TEXT_ENTRY_INTERACTION =>
        // Phase 1: any text entry
        None

      case QtiConstants.EXTENDED_TEXT_INTERACTION =>
        // Phase 1: any extended text
        None

      case t if QtiConstants.PASSTHROUGH_INTERACTIONS.contains(t) =>
        if (interaction.rawMarkup.forall(_.isEmpty)) Some(s"$t has no markup to pass through") else None

      case _ =>
        Some(s"Unsupported interaction type: ${interaction.interactionType}")
    }
  }

  private def buildInteractions(interaction: QtiInteraction, canonicalType: String): Map[String, AnyRef] = {
    val responseId = interaction.responseIdentifier

    val body: Map[String, AnyRef] = canonicalType match {
      case "choice" | "order" =>
        Map(
          "type" -> canonicalType,
          "options" -> interaction.options.map(o => Map("value" -> o.identifier, "label" -> o.label))
        )
      case "match" =>
        val (left, right) = interaction.matchSets.getOrElse((List(), List()))
        Map(
          "type" -> canonicalType,
          "options" -> Map(
            "left" -> left.map(o => Map("value" -> o.identifier, "label" -> o.label)),
            "right" -> right.map(o => Map("value" -> o.identifier, "label" -> o.label))
          )
        )
      case "hottext" | "gap-match" | "inline-choice" | "canvas" | "slider" | "file-upload" =>
        Map("type" -> canonicalType, "markup" -> interaction.rawMarkup.getOrElse(""))
      case _ =>
        Map("type" -> canonicalType)
    }

    if (responseId.nonEmpty) Map(responseId -> body) else Map()
  }

  private def buildResponseDeclaration(
    interaction: QtiInteraction,
    responseDeclaration: Option[QtiResponseDeclaration]
  ): Map[String, AnyRef] = {
    val responseId = interaction.responseIdentifier
    if (responseId.isEmpty || responseDeclaration.isEmpty) return Map()

    val decl = responseDeclaration.get
    val mappedType = mapBaseType(decl.baseType)

    var body: Map[String, AnyRef] = Map(
      "cardinality" -> decl.cardinality,
      "type" -> mappedType
    )

    decl.correctValue.foreach { v =>
      body = body + ("correctResponse" -> Map("value" -> v))
    }

    if (decl.mapping.nonEmpty) {
      body = body + ("mapping" -> decl.mapping.map(m =>
        Map("value" -> m.value, "score" -> m.score.asInstanceOf[AnyRef], "caseSensitive" -> m.caseSensitive.asInstanceOf[AnyRef])
      ))
    }

    Map(responseId -> body)
  }

  private def mapBaseType(baseType: String): String = {
    baseType match {
      case "integer" | "float" => "number"
      case "boolean" => "boolean"
      case _ => "string" // identifier, string, directedPair, pair, duration, file, uri
    }
  }
}
