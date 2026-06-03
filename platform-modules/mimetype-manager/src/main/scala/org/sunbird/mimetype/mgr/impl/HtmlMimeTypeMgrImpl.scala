package org.sunbird.mimetype.mgr.impl

import java.io.File

import org.sunbird.models.UploadParams
import org.sunbird.cloudstore.StorageService
import org.sunbird.common.exception.ClientException
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.dac.model.Node
import org.sunbird.mimetype.mgr.{BaseMimeTypeManager, MimeTypeManager}
import org.sunbird.telemetry.logger.TelemetryManager
import org.sunbird.common.Platform

import scala.concurrent.{ExecutionContext, Future, blocking}

class HtmlMimeTypeMgrImpl(implicit ss: StorageService) extends BaseMimeTypeManager with MimeTypeManager {

    override def upload(objectId: String, node: Node, uploadFile: File, filePath: Option[String], params: UploadParams)(implicit ec: ExecutionContext): Future[Map[String, AnyRef]] = {
        validateUploadRequest(objectId, node, uploadFile)
        val isIndexHtmlValidationRequired: Boolean = if (Platform.config.hasPath("isIndexHtmlValidationRequired")) Platform.config.getBoolean("isIndexHtmlValidationRequired") else true
        val isValidateSuccess: Boolean = if (isIndexHtmlValidationRequired) isValidPackageStructure(uploadFile, List[String]("index.html")) else true
        if (isValidateSuccess) {
            Future {
                blocking {
                    val urls = uploadArtifactToCloud(uploadFile, objectId, filePath)
                    node.getMetadata.put("s3Key", urls(IDX_S3_KEY))
                    node.getMetadata.put("artifactUrl", urls(IDX_S3_URL))
                    extractPackageInCloud(objectId, uploadFile, node, "snapshot", false)
                    Map[String, AnyRef]("identifier" -> objectId, "artifactUrl" -> urls(IDX_S3_URL), "s3Key" -> urls(IDX_S3_KEY), "size" -> getFileSize(uploadFile).asInstanceOf[AnyRef])
                }
            }
        } else {
            TelemetryManager.error("ERR_INVALID_FILE:: " + "Please Provide Valid File! with file name: " + uploadFile.getName)
            throw new ClientException("ERR_INVALID_FILE", "Please Provide Valid File!")
        }

    }

    override def upload(objectId: String, node: Node, fileUrl: String, filePath: Option[String], params: UploadParams)(implicit ec: ExecutionContext): Future[Map[String, AnyRef]] = {
        validateUploadRequest(objectId, node, fileUrl)
        val file = copyURLToFile(objectId, fileUrl)
        try {
            upload(objectId, node, file, filePath, params)
        } finally {
            val parentDir = file.getParentFile
            if (parentDir != null && parentDir.exists()) {
                org.apache.commons.io.FileUtils.deleteDirectory(parentDir)
            }
        }
    }

    override def review(objectId: String, node: Node)(implicit ec: ExecutionContext, ontologyEngineContext: OntologyEngineContext): Future[Map[String, AnyRef]] = {
        validate(node, " | [HTML archive should be uploaded for further processing!]")
        Future(getEnrichedMetadata(node.getMetadata.getOrDefault("status", "").asInstanceOf[String]))
    }

    override def publish(objectId: String, node: Node)(implicit ec: ExecutionContext, ontologyEngineContext: OntologyEngineContext): Future[Map[String, AnyRef]] = {
        validate(node, " | [HTML archive should be uploaded for further processing!]")
        Future(getEnrichedPublishMetadata(node.getMetadata.getOrDefault("status", "").asInstanceOf[String]))
    }

}
