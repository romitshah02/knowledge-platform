package org.sunbird.qti

import scala.xml.Elem
import org.sunbird.common.exception.ClientException
import org.sunbird.telemetry.logger.TelemetryManager

case class QtiResource(identifier: String, resourceType: String, href: String)

class QtiManifestReader {

  def readResources(manifest: Elem): List[QtiResource] = {
    val resources = (manifest \\ "resource")
      .flatMap { res =>
        val identifier = (res \@ "identifier").trim
        val resourceType = (res \@ "type").trim
        val href = (res \@ "href").trim

        // Filter for QTI 3.0 only (Phase 1 decision: reject 2.x)
        if (isQti3Resource(resourceType)) {
          if (identifier.isEmpty || href.isEmpty) {
            TelemetryManager.warn(s"Skipping resource with missing identifier or href: type=$resourceType")
            None
          } else {
            Some(QtiResource(identifier, resourceType, href))
          }
        } else if (resourceType.nonEmpty) {
          TelemetryManager.warn(s"Skipping non-QTI-3.0 resource: type=$resourceType, id=$identifier")
          None
        } else {
          None
        }
      }
      .toList

    if (resources.isEmpty) {
      throw new ClientException(QtiConstants.ERR_INVALID_MANIFEST,
        "No valid QTI 3.0 resources found in manifest")
    }

    resources
  }

  private def isQti3Resource(resourceType: String): Boolean = {
    resourceType == QtiConstants.QTI_ITEM_V3_TYPE ||
    resourceType == QtiConstants.QTI_TEST_V3_TYPE ||
    resourceType == QtiConstants.QTI_STIMULUS_V3_TYPE
  }
}
