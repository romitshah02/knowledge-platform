package org.sunbird.mimetype.mgr.impl

import java.io.File
import java.util
import org.sunbird.cloudstore.StorageService
import org.sunbird.common.exception.ClientException
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.dac.model.Node
import org.sunbird.mimetype.mgr.{BaseMimeTypeManager, MimeTypeManager}
import org.sunbird.models.UploadParams
import org.sunbird.telemetry.logger.TelemetryManager

import scala.concurrent.{ExecutionContext, Future}
import scala.xml.{Node => XmlNode}

class QtiMimeTypeMgrImpl(implicit ss: StorageService) extends BaseMimeTypeManager()(ss) with MimeTypeManager {

    private val ITEM_TYPE_PREFIX         = "imsqti_item_xml"
    private val TEST_TYPE_PREFIX         = "imsqti_test_xml"
    private val STIMULUS_TYPE_PREFIX     = "imsqti_stimulus_xml"
    private val SUPPORTED_VERSION_SUFFIX = "v3p0"
    private val QTI_VERSION              = "3.0"
    private val QTI_MIME_TYPE            = "application/vnd.ekstep.qti-archive"

    override def upload(objectId: String, node: Node, uploadFile: File, filePath: Option[String], params: UploadParams)(implicit ec: ExecutionContext): Future[Map[String, AnyRef]] = {
        validateUploadRequest(objectId, node, uploadFile)
        TelemetryManager.info("QTI content upload for objectId:: " + objectId)
        val extractionBasePath = getBasePath(objectId)
        try {
            if (isValidPackageStructure(uploadFile, List("imsmanifest.xml"))) {
                extractPackage(uploadFile, extractionBasePath)
                val manifestFile = new File(extractionBasePath + File.separator + "imsmanifest.xml")
                val manifestXml  = getSecureXml(manifestFile)
                val resources    = manifestXml \ "resources" \ "resource"

                validateQtiVersion(resources)

                val manifestBase  = manifestXml.attributes.find(_.key == "base").map(_.value.text).getOrElse("")
                val resourcesElem = (manifestXml \ "resources").headOption.getOrElse(<resources/>)
                val resourcesBase = resourcesElem.attributes.find(_.key == "base").map(_.value.text).getOrElse("")

                def resolveEntry(res: XmlNode): (String, String, List[String]) = {
                    val identifier   = res \@ "identifier"
                    val resourceBase = res.attributes.find(_.key == "base").map(_.value.text).getOrElse("")
                    val rawHref      = res \@ "href"
                    val href         = resolveManifestHref(manifestBase, resourcesBase, resourceBase, rawHref, "")
                    val dependencies = (res \ "dependency").map(_ \@ "identifierref").filter(_.nonEmpty).toList
                    validateManifestResourcePath(extractionBasePath, href, "resource file")
                    (identifier, href, dependencies)
                }

                val itemResources          = resources.filter(res => (res \@ "type").startsWith(ITEM_TYPE_PREFIX))
                val declaredTestResources  = resources.filter(res => (res \@ "type").startsWith(TEST_TYPE_PREFIX))
                val declaredStimulusResources = resources.filter(res => (res \@ "type").startsWith(STIMULUS_TYPE_PREFIX))
                val itemDependencyTargets = itemResources.flatMap(res => (res \ "dependency").map(_ \@ "identifierref")).toSet
                val (misclassifiedStimulusResources, testResources) =
                    declaredTestResources.partition(res => itemDependencyTargets.contains(res \@ "identifier"))
                val stimulusResources = declaredStimulusResources ++ misclassifiedStimulusResources

                if (itemResources.isEmpty && testResources.isEmpty)
                    throw new ClientException("ERR_INVALID_FILE", "No QTI items or tests found in imsmanifest.xml!")

                val stimulusEntries     = stimulusResources.map(resolveEntry)
                val stimulusIdentifiers = stimulusEntries.map(_._1).toSet

                val itemEntries      = itemResources.map(resolveEntry)
                val itemIdentifiers  = itemEntries.map(_._1).toSet

                val testEntries = testResources.map(resolveEntry)

                val javaItemList = new util.ArrayList[util.Map[String, AnyRef]]()
                itemEntries.foreach { case (identifier, href, dependencies) =>
                    javaItemList.add(toResourceMap(identifier, href, "stimulusRefs", dependencies.filter(stimulusIdentifiers.contains)))
                }

                val javaTestList = new util.ArrayList[util.Map[String, AnyRef]]()
                testEntries.foreach { case (identifier, href, dependencies) =>
                    javaTestList.add(toResourceMap(identifier, href, "itemRefs", dependencies.filter(itemIdentifiers.contains)))
                }

                val javaStimulusList = new util.ArrayList[util.Map[String, String]]()
                stimulusEntries.foreach { case (identifier, href, _) =>
                    val javaMap = new util.HashMap[String, String]()
                    javaMap.put("identifier", identifier)
                    javaMap.put("href", href)
                    javaStimulusList.add(javaMap)
                }

                val urls: Array[String] = uploadArtifactToCloud(uploadFile, objectId, filePath)
                extractPackageInCloud(objectId, uploadFile, node, "snapshot", false)
                // uploadDirectory's returned "url" is a comma-joined list of every uploaded file's
                // URL (directory upload semantics), not one folder URL - build the folder URL
                // directly from the known extraction key instead.
                val previewUrl = ss.formatUrl(ss.getUri(getExtractionPath(objectId, node, "snapshot", QTI_MIME_TYPE)))

                Future(Map[String, AnyRef](
                    "identifier"   -> objectId,
                    "artifactUrl"  -> urls(IDX_S3_URL),
                    "s3Key"        -> urls(IDX_S3_KEY),
                    "size"         -> getFileSize(uploadFile).asInstanceOf[AnyRef],
                    "previewUrl"   -> previewUrl,
                    "qtiVersion"   -> QTI_VERSION,
                    "itemList"     -> javaItemList,
                    "testList"     -> javaTestList,
                    "stimulusList" -> javaStimulusList
                ))

            } else {
                TelemetryManager.error("ERR_INVALID_FILE:: Invalid QTI package: imsmanifest.xml not found for objectId: " + objectId)
                throw new ClientException("ERR_INVALID_FILE", "Invalid QTI package: imsmanifest.xml is missing!")
            }
        } finally {
            delete(new File(extractionBasePath))
        }
    }

    private def validateQtiVersion(resources: scala.xml.NodeSeq): Unit = {
        val unsupported = resources.map(res => res \@ "type").filter(_.nonEmpty).filter { resType =>
            (resType.startsWith(ITEM_TYPE_PREFIX) || resType.startsWith(TEST_TYPE_PREFIX) || resType.startsWith(STIMULUS_TYPE_PREFIX)) &&
              !resType.endsWith(SUPPORTED_VERSION_SUFFIX)
        }
        if (unsupported.nonEmpty) {
            TelemetryManager.error(s"Unsupported QTI resource type(s): ${unsupported.mkString(", ")}")
            throw new ClientException("ERR_INVALID_FILE", "Unsupported QTI version. Only QTI 3.0 is supported.")
        }
    }

    private def toResourceMap(identifier: String, href: String, refsKey: String, refs: List[String]): util.Map[String, AnyRef] = {
        val javaMap = new util.HashMap[String, AnyRef]()
        javaMap.put("identifier", identifier)
        javaMap.put("href", href)
        val javaRefs = new util.ArrayList[String]()
        refs.foreach(javaRefs.add)
        javaMap.put(refsKey, javaRefs)
        javaMap
    }

    override def upload(objectId: String, node: Node, fileUrl: String, filePath: Option[String], params: UploadParams)(implicit ec: ExecutionContext): Future[Map[String, AnyRef]] = {
        validateUploadRequest(objectId, node, fileUrl)
        val file = copyURLToFile(objectId, fileUrl)
        upload(objectId, node, file, filePath, params)
    }

    override def review(objectId: String, node: Node)(implicit ec: ExecutionContext, ontologyEngineContext: OntologyEngineContext): Future[Map[String, AnyRef]] = {
        validate(node, "[QTI file should be uploaded for further processing!]")
        Future(getEnrichedMetadata(node.getMetadata.getOrDefault("status", "").asInstanceOf[String]))
    }

    override def publish(objectId: String, node: Node)(implicit ec: ExecutionContext, ontologyEngineContext: OntologyEngineContext): Future[Map[String, AnyRef]] = {
        validate(node, "[QTI file should be uploaded for further processing!]")
        Future(getEnrichedPublishMetadata(node.getMetadata.getOrDefault("status", "").asInstanceOf[String]))
    }
}
