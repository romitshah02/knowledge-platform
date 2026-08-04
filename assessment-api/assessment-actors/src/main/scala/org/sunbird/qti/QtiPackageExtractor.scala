package org.sunbird.qti

import java.io.File
import scala.xml.Elem
import scala.jdk.CollectionConverters._
import org.sunbird.common.SafeZipExtractor
import org.sunbird.common.SecureXmlParser
import org.sunbird.common.exception.ClientException
import org.sunbird.telemetry.logger.TelemetryManager

class QtiPackageExtractor {

  def extractPackage(zipFile: File, extractionPath: String): File = {
    TelemetryManager.info(s"Extracting QTI package to $extractionPath")

    if (!SafeZipExtractor.hasRequiredEntries(zipFile, List("imsmanifest.xml").asJava)) {
      throw new ClientException(QtiConstants.ERR_INVALID_PACKAGE,
        "QTI package must contain imsmanifest.xml")
    }

    SafeZipExtractor.extract(zipFile, extractionPath)
    new File(extractionPath)
  }

  def readManifest(extractionPath: String): Elem = {
    val manifestFile = new File(extractionPath + File.separator + "imsmanifest.xml")
    if (!manifestFile.exists()) {
      throw new ClientException(QtiConstants.ERR_INVALID_MANIFEST,
        "imsmanifest.xml not found after extraction")
    }

    try {
      val saxParser = SecureXmlParser.newSecureFactory().newSAXParser()
      scala.xml.XML.withSAXParser(saxParser).loadFile(manifestFile)
    } catch {
      case e: Exception =>
        TelemetryManager.error(s"Failed to parse manifest: ${e.getMessage}")
        throw new ClientException(QtiConstants.ERR_INVALID_MANIFEST,
          s"Failed to parse imsmanifest.xml: ${e.getMessage}")
    }
  }

  def resolveItemPath(basePath: String, href: String): String = {
    SafeZipExtractor.resolveWithinBase(basePath, href)
  }
}
