package org.sunbird.qti

import scala.collection.mutable
import scala.collection.JavaConverters._
import org.sunbird.telemetry.logger.TelemetryManager

case class HierarchyNode(
  identifier: String,
  name: String,
  nodeType: String,
  metadata: Map[String, AnyRef],
  children: Option[List[String]] = None
)

case class QuestionSetPayload(
  questionSetIdentifier: String,
  questionSetMetadata: Map[String, AnyRef],
  nodesModified: Map[String, HierarchyNode],
  hierarchy: Map[String, AnyRef]
)

class QtiTestTransformer {

  def deepAsJava(v: Any): AnyRef = v match {
    case m: Map[_, _] =>
      val out = new java.util.HashMap[String, AnyRef]()
      m.asInstanceOf[Map[String, Any]].foreach { case (k, value) => out.put(k, deepAsJava(value)) }
      out
    case l: List[_] =>
      val out = new java.util.ArrayList[AnyRef]()
      l.foreach(value => out.add(deepAsJava(value)))
      out
    case other => other.asInstanceOf[AnyRef]
  }

  def transform(
    qtiTest: QtiTest,
    transformedItems: Map[String, QuestionMetadata],
    itemFailures: Map[String, String]
  ): Either[String, QuestionSetPayload] = {
    if (transformedItems.isEmpty) {
      return Left(s"No items were successfully transformed from test ${qtiTest.identifier}")
    }

    TelemetryManager.info(s"Transforming test ${qtiTest.identifier}: " +
      s"${transformedItems.size} items successful, ${itemFailures.size} failed")

    val nodesModified = mutable.Map[String, HierarchyNode]()

    // Create QuestionSet node
    val qsName = if (qtiTest.title.trim.length >= 5) qtiTest.title.trim else s"QTI Import: ${qtiTest.identifier}"
    val qsMetadata = Map[String, AnyRef](
      "name" -> qsName,
      "code" -> qtiTest.identifier,
      "mimeType" -> "application/vnd.sunbird.questionset",
      "primaryCategory" -> "Practice Question Set",
      "description" -> s"Imported from QTI 3.0 test: ${qtiTest.identifier}",
      "status" -> "Draft",
      "origin" -> "qti",
      "originData" -> deepAsJava(Map("source" -> qtiTest.identifier))
    )

    val qsNode = HierarchyNode(
      identifier = qtiTest.identifier,
      name = qtiTest.title,
      nodeType = "QuestionSet",
      metadata = qsMetadata
    )
    nodesModified(qtiTest.identifier) = qsNode

    // Collect all item identifiers in order (from test structure)
    val allItemIds = collectItemIds(qtiTest)
    val availableItemIds = allItemIds.filter(transformedItems.contains)

    if (availableItemIds.isEmpty) {
      return Left(s"No imported items reference items that were successfully transformed")
    }

    // Create Question nodes for each successfully-transformed item
    availableItemIds.foreach { itemId =>
      val qMetadata = transformedItems(itemId)
      val rawName = qMetadata.body.replaceAll("<[^>]*>", " ").replaceAll("\\s+", " ").trim.take(50).trim
      val qName = if (rawName.length >= 5) rawName else s"QTI Question: $itemId"
      val questionMetadata = Map[String, AnyRef](
        "name" -> qName,
        "code" -> itemId,
        "mimeType" -> "application/vnd.sunbird.question",
        "qumlVersion" -> 1.1.asInstanceOf[AnyRef],
        "schemaVersion" -> "1.1",
        "body" -> qMetadata.body,
        "primaryCategory" -> qMetadata.primaryCategory,
        "qType" -> qMetadata.qType,
        "interactionTypes" -> deepAsJava(qMetadata.interactionTypes),
        "interactions" -> deepAsJava(qMetadata.interactions),
        "responseDeclaration" -> deepAsJava(qMetadata.responseDeclaration),
        "status" -> "Draft",
        "origin" -> "qti",
        "originData" -> deepAsJava(Map("source" -> itemId))
      ).filter {
        case (_, v: String) => v.nonEmpty
        case (_, m: java.util.Map[_, _]) => !m.isEmpty
        case _ => true
      }

      val questionNode = HierarchyNode(
        identifier = itemId,
        name = qMetadata.body.take(50),
        nodeType = "Question",
        metadata = questionMetadata
      )
      nodesModified(itemId) = questionNode
    }

    // Build hierarchy: QuestionSet -> Questions
    val hierarchy = Map[String, AnyRef](
      qtiTest.identifier -> Map[String, AnyRef](
        "name" -> qtiTest.title,
        "children" -> availableItemIds
      )
    )

    Right(QuestionSetPayload(
      questionSetIdentifier = qtiTest.identifier,
      questionSetMetadata = qsMetadata,
      nodesModified = nodesModified.toMap,
      hierarchy = hierarchy
    ))
  }

  def collectItemIds(qtiTest: QtiTest): List[String] = {
    qtiTest.testParts.flatMap { tp =>
      tp.sections.flatMap { section =>
        section.itemRefs.sortBy(_.position).map(_.itemIdentifier)
      }
    }
  }
}
