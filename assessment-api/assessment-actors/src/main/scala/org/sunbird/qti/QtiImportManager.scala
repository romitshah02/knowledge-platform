package org.sunbird.qti

import java.io.File
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}
import scala.collection.JavaConverters._
import org.sunbird.common.dto.{Request, Response, ResponseHandler}
import org.sunbird.common.exception.ClientException
import org.sunbird.common.{DateUtils, Platform}
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.nodes.DataNode
import org.sunbird.managers.questionset.{AssessmentManager, UpdateHierarchyManager}
import org.sunbird.telemetry.logger.TelemetryManager

case class PersistResult(
  questionSetId: String,
  createdItemIds: List[(String, String)],
  failedItems: List[(String, String)]
)

class QtiImportManager {

  private val extractor = new QtiPackageExtractor()
  private val manifestReader = new QtiManifestReader()
  private val itemParser = new QtiItemParser()
  private val testParser = new QtiTestParser()
  private val itemTransformer = new QtiItemTransformer()
  private val testTransformer = new QtiTestTransformer()

  def importPackage(request: Request)(
    implicit ec: ExecutionContext,
    oec: OntologyEngineContext
  ): Future[Response] = {
    val packageFile = request.getRequest.getOrDefault("file", null).asInstanceOf[File]
    if (packageFile == null || !packageFile.exists())
      throw new ClientException(QtiConstants.ERR_INVALID_PACKAGE, "QTI package file not provided or invalid")
    val requestMap = request.getRequest.asScala.toMap.asInstanceOf[Map[String, AnyRef]]
    val extractionPath = getTempPath()

    try {
      // Phase 1: Extract and validate package structure
      TelemetryManager.info(s"Starting QTI import from ${packageFile.getName}")
      val extractedDir = extractor.extractPackage(packageFile, extractionPath)
      val manifest = extractor.readManifest(extractionPath)

      // Phase 2: Read resources from manifest (3.0 only)
      val resources = manifestReader.readResources(manifest)
      val itemResources = resources.filter(_.resourceType == QtiConstants.QTI_ITEM_V3_TYPE)
      val testResources = resources.filter(_.resourceType == QtiConstants.QTI_TEST_V3_TYPE)

      if (itemResources.isEmpty && testResources.isEmpty) {
        throw new ClientException(QtiConstants.ERR_INVALID_MANIFEST,
          "No QTI 3.0 items or tests found in manifest")
      }

      TelemetryManager.info(s"Found ${itemResources.size} items and ${testResources.size} tests")

      val itemFailures = scala.collection.mutable.Map[String, String]()
      val transformedItems = scala.collection.mutable.Map[String, QuestionMetadata]()

      val parsedTest = if (testResources.nonEmpty) {
        val testResource = testResources.head
        val testFilePath = extractor.resolveItemPath(extractionPath, testResource.href)
        val testFile = new File(extractionPath + File.separator + testFilePath)
        testParser.parse(testFile) match {
          case Right(qtiTest) => Some(qtiTest)
          case Left(error) =>
            throw new ClientException(QtiConstants.ERR_IMPORT_FAILED, s"Test parsing failed: $error")
        }
      } else None

      val itemResourcesById = itemResources.map(r => r.identifier -> r).toMap
      val itemResourcesToParse = parsedTest match {
        case Some(qtiTest) =>
          val referencedIds = testTransformer.collectItemIds(qtiTest).toSet

          // Manifest items the test never references — skipped, not silently dropped.
          itemResources.filterNot(r => referencedIds.contains(r.identifier)).foreach { r =>
            itemFailures(r.identifier) = "Not referenced by any test section (skipped)"
            TelemetryManager.warn(s"Item ${r.identifier} is in the manifest but not referenced by the test — skipped")
          }

          // Test refs with no matching manifest item resource.
          (referencedIds -- itemResourcesById.keySet).foreach { missingId =>
            itemFailures(missingId) = "Referenced by test but no matching item resource in manifest"
            TelemetryManager.warn(s"Test references item $missingId but no matching manifest resource was found")
          }

          itemResources.filter(r => referencedIds.contains(r.identifier))
        case None =>
          itemResources
      }

      itemResourcesToParse.foreach { resource =>
        val itemFilePath = extractor.resolveItemPath(extractionPath, resource.href)
        val itemFile = new File(extractionPath + File.separator + itemFilePath)

        Try(itemParser.parse(itemFile)) match {
          case Success(Right(parsedItem)) =>
            itemTransformer.transform(parsedItem) match {
              case Right(metadata) =>
                transformedItems(resource.identifier) = metadata
              case Left(error) =>
                itemFailures(resource.identifier) = error.reason
                TelemetryManager.warn(s"Failed to transform item ${resource.identifier}: ${error.reason}")
            }
          case Success(Left(error)) =>
            itemFailures(resource.identifier) = error
            TelemetryManager.warn(s"Failed to parse item ${resource.identifier}: $error")
          case Failure(ex) =>
            itemFailures(resource.identifier) = ex.getMessage
            TelemetryManager.error(s"Error processing item ${resource.identifier}", ex)
        }
      }

      if (transformedItems.isEmpty) {
        throw new ClientException(QtiConstants.ERR_IMPORT_FAILED,
          s"No items could be transformed. Failures: ${itemFailures.values.mkString("; ")}")
      }

      val persistResult = parsedTest match {
        case Some(qtiTest) =>
          testTransformer.transform(qtiTest, transformedItems.toMap, itemFailures.toMap) match {
            case Right(payload) =>
              createQuestionSetAndItems(payload, requestMap)
            case Left(error) =>
              throw new ClientException(QtiConstants.ERR_IMPORT_FAILED, s"Test transformation failed: $error")
          }
        case None =>
          createDefaultQuestionSet(transformedItems.toMap, itemFailures.toMap, requestMap)
      }

      val transformFailures = itemFailures.map { case (id, msg) => Map("itemId" -> id, "reason" -> msg) }.toList
      val persistFailures = persistResult.failedItems.map { case (id, msg) => Map("itemId" -> id, "reason" -> msg) }
      val createdItems = persistResult.createdItemIds.map { case (itemId, doId) => Map("itemId" -> itemId, "identifier" -> doId) }

      val javaFailures = new java.util.ArrayList[java.util.Map[String, String]]()
      (transformFailures ++ persistFailures).foreach(f => javaFailures.add(new java.util.HashMap[String, String](f.asJava)))
      val javaCreatedItems = new java.util.ArrayList[java.util.Map[String, String]]()
      createdItems.foreach(c => javaCreatedItems.add(new java.util.HashMap[String, String](c.asJava)))

      Future.successful(ResponseHandler.OK.putAll(Map[String, AnyRef](
        "identifier" -> persistResult.questionSetId,
        "itemsImported" -> persistResult.createdItemIds.size.asInstanceOf[AnyRef],
        "itemsFailed" -> (itemFailures.size + persistResult.failedItems.size).asInstanceOf[AnyRef],
        "failures" -> javaFailures,
        "createdItems" -> javaCreatedItems
      ).asJava))

    } catch {
      case ex: ClientException =>
        TelemetryManager.error(s"QTI import failed: ${ex.getMessage}", ex)
        Future.failed(ex)
      case ex: Exception =>
        TelemetryManager.error(s"Unexpected error during QTI import", ex)
        Future.failed(new ClientException(QtiConstants.ERR_IMPORT_FAILED, ex.getMessage))
    } finally {
      cleanup(extractionPath)
    }
  }

  private def createQuestionSetAndItems(
    payload: QuestionSetPayload,
    request: Map[String, AnyRef]
  )(implicit ec: ExecutionContext, oec: OntologyEngineContext): PersistResult = {
    val qsMetadata = payload.questionSetMetadata ++ Map(
      "createdOn" -> DateUtils.formatCurrentDate(),
      "lastUpdatedOn" -> DateUtils.formatCurrentDate(),
      "objectType" -> "QuestionSet"
    )

    val qsContext = new java.util.HashMap[String, AnyRef]()
    qsContext.put("graph_id", "domain")
    qsContext.put("version", "1.0")
    qsContext.put("objectType", "QuestionSet")
    qsContext.put("schemaName", "questionset")

    val qsInput = new java.util.HashMap[String, AnyRef](qsMetadata.asJava)
    val qsRequest = new Request(qsContext, qsInput, "createQuestionSet", "QuestionSet")

    val qsNode = scala.concurrent.Await.result(
      DataNode.create(qsRequest),
      scala.concurrent.duration.Duration.Inf
    )
    val qsDoId = qsNode.getIdentifier
    TelemetryManager.info(s"Created QuestionSet $qsDoId (source: ${payload.questionSetIdentifier})")

    // qtiItemId -> do_id, for successfully-created Questions only
    val createdItemIds = scala.collection.mutable.ListBuffer[(String, String)]()
    val failedItems = scala.collection.mutable.ListBuffer[(String, String)]()

    payload.nodesModified.values.filter(_.nodeType == "Question").foreach { qNode =>
      val qMetadata = qNode.metadata ++ Map(
        "objectType" -> "Question",
        "createdOn" -> DateUtils.formatCurrentDate(),
        "lastUpdatedOn" -> DateUtils.formatCurrentDate()
      )
      val qContext = new java.util.HashMap[String, AnyRef]()
      qContext.put("graph_id", "domain")
      qContext.put("version", "1.0")
      qContext.put("objectType", "Question")
      qContext.put("schemaName", "question")

      val qInput = new java.util.HashMap[String, AnyRef](qMetadata.asJava)
      val questionRequest = new Request(qContext, qInput, "createQuestion", "Question")

      Try(scala.concurrent.Await.result(
        DataNode.create(questionRequest),
        scala.concurrent.duration.Duration.Inf
      )) match {
        case Success(createdNode) =>
          val doId = createdNode.getIdentifier
          TelemetryManager.info(s"Created Question $doId (source: ${qNode.identifier})")
          createdItemIds += ((qNode.identifier, doId))
        case Failure(ex: org.sunbird.common.exception.MiddlewareException) =>
          val detail = Option(ex.getMessages).filter(!_.isEmpty).map(_.asScala.mkString("; ")).getOrElse(ex.getMessage)
          TelemetryManager.error(s"Failed to create Question ${qNode.identifier}: $detail")
          failedItems += ((qNode.identifier, detail))
        case Failure(ex) =>
          TelemetryManager.error(s"Failed to create Question ${qNode.identifier}: ${ex.getMessage}")
          failedItems += ((qNode.identifier, ex.getMessage))
      }
    }

    val qtiIdToDoId = createdItemIds.toMap
    val filteredHierarchy = payload.hierarchy.map { case (rootId, value) =>
      value match {
        case m: Map[String, AnyRef] @unchecked =>
          val translatedChildren = m.get("children") match {
            case Some(children: List[String] @unchecked) => children.flatMap(qtiIdToDoId.get)
            case _ => List()
          }
          qsDoId -> (m + ("children" -> translatedChildren))
        case other => qsDoId -> other
      }
    }

    val hierarchyContext = new java.util.HashMap[String, AnyRef]()
    hierarchyContext.put("identifier", qsDoId)
    hierarchyContext.put("graph_id", "domain")
    hierarchyContext.put("version", "1.0")
    hierarchyContext.put("objectType", "QuestionSet")
    hierarchyContext.put("schemaName", "questionset")
    val hierarchyInput: java.util.Map[String, AnyRef] = testTransformer.deepAsJava(Map[String, AnyRef](
      "nodesModified" -> (
        Map[String, AnyRef]("identifier" -> qsDoId, "nodeType" -> "QuestionSet", "metadata" -> payload.questionSetMetadata) ::
        payload.nodesModified.values.filter(_.nodeType == "Question").toList.flatMap(n =>
          qtiIdToDoId.get(n.identifier).map(doId =>
            Map[String, AnyRef]("identifier" -> doId, "nodeType" -> "Question", "metadata" -> n.metadata)
          )
        )
      ),
      "hierarchy" -> filteredHierarchy
    )).asInstanceOf[java.util.Map[String, AnyRef]]
    val hierarchyRequest = new Request(hierarchyContext, hierarchyInput, "updateHierarchy", "QuestionSet")

    Try(scala.concurrent.Await.result(
      UpdateHierarchyManager.updateHierarchy(hierarchyRequest),
      scala.concurrent.duration.Duration.Inf
    )) match {
      case Success(_) =>
        TelemetryManager.info(s"Updated hierarchy for QuestionSet $qsDoId")
      case Failure(ex) =>
        TelemetryManager.warn(s"Hierarchy update failed (non-critical): ${ex.getMessage}")
    }

    PersistResult(qsDoId, createdItemIds.toList, failedItems.toList)
  }

  private def createDefaultQuestionSet(
    transformedItems: Map[String, QuestionMetadata],
    itemFailures: Map[String, String],
    request: Map[String, AnyRef]
  )(implicit ec: ExecutionContext, oec: OntologyEngineContext): PersistResult = {
    val qsCode = s"qs_qti_${System.currentTimeMillis()}"
    val qsMetadata = Map[String, AnyRef](
      "name" -> s"QTI Import - $qsCode",
      "code" -> qsCode,
      "mimeType" -> "application/vnd.sunbird.questionset",
      "primaryCategory" -> "Practice Question Set",
      "description" -> "Imported from QTI 3.0 package",
      "status" -> "Draft",
      "origin" -> "qti",
      "createdOn" -> DateUtils.formatCurrentDate(),
      "lastUpdatedOn" -> DateUtils.formatCurrentDate(),
      "objectType" -> "QuestionSet"
    )

    val qsContext = new java.util.HashMap[String, AnyRef]()
    qsContext.put("graph_id", "domain")
    qsContext.put("version", "1.0")
    qsContext.put("objectType", "QuestionSet")
    qsContext.put("schemaName", "questionset")

    val qsInput = new java.util.HashMap[String, AnyRef](qsMetadata.asJava)
    val qsRequest = new Request(qsContext, qsInput, "createQuestionSet", "QuestionSet")

    val qsNode = scala.concurrent.Await.result(
      DataNode.create(qsRequest),
      scala.concurrent.duration.Duration.Inf
    )

    TelemetryManager.warn(s"Created default QuestionSet ${qsNode.getIdentifier} with no test file — " +
      s"${transformedItems.size} parsed items were not persisted as Questions (known gap)")
    PersistResult(qsNode.getIdentifier, List(), List())
  }

  private def getTempPath(): String = {
    val basePath = Platform.getString("content.upload.temp_location", "/tmp/content")
    basePath + File.separator + System.currentTimeMillis() + "_qti_import"
  }

  private def cleanup(path: String): Unit = {
    Try {
      val dir = new File(path)
      if (dir.exists()) {
        org.apache.commons.io.FileUtils.deleteDirectory(dir)
        TelemetryManager.info(s"Cleaned up temporary directory: $path")
      }
    }.recover { case ex =>
      TelemetryManager.warn(s"Failed to clean up temporary directory $path: ${ex.getMessage}")
    }
  }
}
