package org.sunbird.mimetype.mgr.impl

import java.io.File
import org.sunbird.cloudstore.StorageService
import org.sunbird.common.exception.ClientException
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.dac.model.Node
import org.sunbird.mimetype.mgr.{BaseMimeTypeManager, MimeTypeManager}
import org.sunbird.models.UploadParams
import org.sunbird.telemetry.logger.TelemetryManager
import java.util
import scala.concurrent.{ExecutionContext, Future}
import scala.xml.Elem

class ScormMimeTypeMgrImpl(implicit ss: StorageService) extends BaseMimeTypeManager()(ss) with MimeTypeManager {

    override def upload(objectId: String, node: Node, uploadFile: File, filePath: Option[String], params: UploadParams)(implicit ec: ExecutionContext): Future[Map[String, AnyRef]] = {
                validateUploadRequest(objectId, node, uploadFile)
                TelemetryManager.info("SCORM content upload for objectId:: " + objectId)
                val extractionBasePath = getBasePath(objectId)
                try {
                    if (isValidPackageStructure(uploadFile, List("imsmanifest.xml"))) {
                        extractPackage(uploadFile, extractionBasePath)
                        val manifestFile = new File(extractionBasePath + File.separator + "imsmanifest.xml")
                        val manifestXml  = getSecureXml(manifestFile)
                        val scormVersion = detectScormVersion(manifestXml)
                        val scoList      = getScoList(manifestXml, scormVersion)
                        val maxAttempts  = detectMaxAttempts(manifestXml, scormVersion)

                        if (scoList.isEmpty)
                            throw new ClientException("ERR_INVALID_FILE", "No SCOs found in imsmanifest.xml!")

                        // Validate all SCO hrefs up-front
                        scoList.foreach(sco => validateManifestResourcePath(extractionBasePath, sco.getOrElse("href", ""), "launch file"))
                        
                        val launchFile = scoList.head.getOrElse("href", "")
        
                        val javaScoList = new util.ArrayList[util.Map[String, String]]()

                        scoList.foreach { sco =>
                                            val javaMap = new util.HashMap[String, String]()
                                            sco.foreach { case (k, v) => javaMap.put(k, v) }
                                            javaScoList.add(javaMap)
                                        }

                        val urls: Array[String] = uploadArtifactToCloud(uploadFile, objectId, filePath)
                        
                        extractPackageInCloud(objectId, uploadFile, node, "snapshot", false)

                        val baseResult = Map[String, AnyRef](
                            "identifier"   -> objectId,
                            "artifactUrl"  -> urls(IDX_S3_URL),
                            "s3Key"        -> urls(IDX_S3_KEY),
                            "size"         -> getFileSize(uploadFile).asInstanceOf[AnyRef],
                            "launchFile"   -> launchFile,
                            "scoList"      -> javaScoList,
                            "scormVersion" -> scormVersion
                        )

                        Future(maxAttempts.fold(baseResult)(limit => baseResult + ("maxAttempts" -> limit.asInstanceOf[AnyRef])))

                    } else {
                        TelemetryManager.error("ERR_INVALID_FILE:: Invalid SCORM package: imsmanifest.xml not found for objectId: " + objectId)
                        throw new ClientException("ERR_INVALID_FILE", "Invalid SCORM package: imsmanifest.xml is missing!")
                    }
                } finally {
                    delete(new File(extractionBasePath))
                }
    }

    private def detectScormVersion(xml: Elem): String = {

        val (schema, schemaVersion) = readManifestSchema(xml)

        if (schema.isEmpty && schemaVersion.isEmpty) return "1.2"

        schemaVersion match {
            case "1.2"                   => "1.2"
            case v if v.startsWith("2004") => "2004"
            case "1.3" if schema == "cam" => "2004" 
            case _ if schema.contains("adl scorm") => "2004"
            case _ =>
                TelemetryManager.error(s"Unsupported SCORM version: schema='$schema' schemaversion='$schemaVersion'")
                throw new ClientException("ERR_INVALID_FILE",
                    "Unsupported SCORM version. Only SCORM 1.2 and SCORM 2004 are supported.")
        }
    }

    private def detectMaxAttempts(xml: Elem, scormVersion: String): Option[Int] = {
        if (scormVersion != "2004") None
        else (xml \\ "limitConditions").flatMap(_.attribute("attemptLimit").map(_.text.trim)).headOption
            .flatMap(v => scala.util.Try(v.toInt).toOption)
    }

    private def getScoList(xml: Elem, scormVersion: String): List[Map[String, String]] = {

        val manifestBase  = xml.attributes.find(_.key == "base").map(_.value.text).getOrElse("")
        val resourcesElem = (xml \ "resources").headOption.getOrElse(<resources/>)
        val resourcesBase = resourcesElem.attributes.find(_.key == "base").map(_.value.text).getOrElse("")

        (xml \\ "item").filter(item => (item \@ "identifierref").nonEmpty).flatMap { item =>
            val ref   = item \@ "identifierref"
            val title = (item \ "title").text

            val resourceNode = (xml \\ "resource").find { res =>
                (res \@ "identifier") == ref && {
                    val st = res.attributes.find(_.key == "scormType").map(_.value.text).getOrElse("").trim.toLowerCase
                    st == "sco" || (st.isEmpty && scormVersion == "1.2")
                }
            }

            resourceNode.map { res =>
                val resourceBase = res.attributes.find(_.key == "base").map(_.value.text).getOrElse("")
                val rawHref      = res \@ "href"
                val parameters   = item \@ "parameters"
                val finalHref    = resolveManifestHref(manifestBase, resourcesBase, resourceBase, rawHref, parameters)

                Map(
                    "identifier"          -> (item \@ "identifier"),
                    "title"               -> title,
                    "href"                -> finalHref,
                    "parameters"          -> parameters
                )
            }
        }.toList
    }

    override def upload(objectId: String, node: Node, fileUrl: String, filePath: Option[String], params: UploadParams)(implicit ec: ExecutionContext): Future[Map[String, AnyRef]] = {
        validateUploadRequest(objectId, node, fileUrl)
        val file = copyURLToFile(objectId, fileUrl)
        upload(objectId, node, file, filePath, params)
    }

    override def review(objectId: String, node: Node)(implicit ec: ExecutionContext, ontologyEngineContext: OntologyEngineContext): Future[Map[String, AnyRef]] = {
        validate(node, "[SCORM file should be uploaded for further processing!]")
        Future(getEnrichedMetadata(node.getMetadata.getOrDefault("status", "").asInstanceOf[String]))
    }

    override def publish(objectId: String, node: Node)(implicit ec: ExecutionContext, ontologyEngineContext: OntologyEngineContext): Future[Map[String, AnyRef]] = {
        validate(node, "[SCORM file should be uploaded for further processing!]")
        Future(getEnrichedPublishMetadata(node.getMetadata.getOrDefault("status", "").asInstanceOf[String]))
    }
}