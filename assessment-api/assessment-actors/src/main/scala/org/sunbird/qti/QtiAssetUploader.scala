package org.sunbird.qti

import java.io.File
import org.sunbird.cloudstore.StorageService
import org.sunbird.telemetry.logger.TelemetryManager

import scala.util.Try

// Uploads a QTI package's locally-referenced media file (image/audio/video) to cloud
class QtiAssetUploader(storageService: StorageService) {

  def upload(file: File): Try[String] = Try {
    val Array(_, url) = storageService.uploadFile("qti_assets", file)
    url
  }.recoverWith { case e =>
    TelemetryManager.error(s"Failed to upload QTI asset ${file.getName}: ${e.getMessage}", e)
    scala.util.Failure(e)
  }
}