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
  responseDeclaration: Map[String, AnyRef]
)

class QtiItemTransformer {

  def transform(item: QtiItem): Either[TransformError, QuestionMetadata] = {
    val categoryResult = mapInteractionToCategory(item.interaction)
    if (categoryResult.isEmpty) {
      return Left(TransformError(s"Unsupported interaction type: ${item.interaction.interactionType}"))
    }

    val (primaryCategory, qType) = categoryResult.get
    val interactionTypes = List(canonicalInteractionType(primaryCategory))

    // Build question body from stimulus + body
    val bodyText = (item.stimulus, item.body) match {
      case (Some(stim), body) if body.nonEmpty => s"$stim\n\n$body"
      case (Some(stim), _) => stim
      case (_, body) if body.nonEmpty => body
      case _ => ""
    }

    if (bodyText.isEmpty) {
      return Left(TransformError(s"Item ${item.identifier} has no body or stimulus content"))
    }

    // Validate interaction-specific constraints
    val validationError = validateInteraction(item.interaction)
    if (validationError.isDefined) {
      return Left(TransformError(validationError.get))
    }

    val interactions = buildInteractions(item.interaction, interactionTypes.head)
    val responseDeclaration = buildResponseDeclaration(item.interaction, item.responseDeclaration)

    Right(QuestionMetadata(
      identifier = item.identifier,
      primaryCategory = primaryCategory,
      qType = qType,
      interactionTypes = interactionTypes,
      body = bodyText,
      interactions = interactions,
      responseDeclaration = responseDeclaration
    ))
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
