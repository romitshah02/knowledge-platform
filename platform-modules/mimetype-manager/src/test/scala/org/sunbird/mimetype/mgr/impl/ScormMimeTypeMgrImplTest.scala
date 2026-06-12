package org.sunbird.mimetype.mgr.impl

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.scala.DefaultScalaModule
import java.io.{File, FileOutputStream}
import java.util.zip.{ZipEntry, ZipOutputStream}
import org.apache.commons.io.FileUtils
import org.scalamock.scalatest.AsyncMockFactory
import org.scalatest.{AsyncFlatSpec, Matchers}
import org.sunbird.cloudstore.StorageService
import org.sunbird.common.exception.ClientException
import org.sunbird.graph.OntologyEngineContext
import org.sunbird.graph.dac.model.Node
import org.sunbird.models.UploadParams
import scala.concurrent.Future
import scala.collection.JavaConverters._ 

class ScormMimeTypeMgrImplTest extends AsyncFlatSpec with Matchers with AsyncMockFactory {
    val mapper = new ObjectMapper()
    mapper.registerModule(DefaultScalaModule)

    implicit val ss: StorageService = mock[StorageService]

    implicit val oec: OntologyEngineContext = stub[OntologyEngineContext]
    val scormMgr = new ScormMimeTypeMgrImpl()(ss) {
        override protected val TEMP_FILE_LOCATION: String = System.getProperty("java.io.tmpdir") + File.separator + "content"
    }

    def getNode(): Node = {
        val node = new Node()
        node.setMetadata(new java.util.HashMap[String, AnyRef]())
        node.getMetadata.put("mimeType", "application/vnd.ekstep.scorm-archive")
        node
    }

    def createZip(files: Map[String, String]): File = {
        val zipFile = File.createTempFile("scorm", ".zip")
        val zos = new ZipOutputStream(new FileOutputStream(zipFile))
        files.foreach { case (name, content) =>
            val entry = new ZipEntry(name)
            zos.putNextEntry(entry)
            zos.write(content.getBytes)
            zos.closeEntry()
        }
        zos.close()
        zipFile
    }

    // Happy path — valid SCORM zip with correct imsmanifest.xml and existing launch file succeeds
    "upload" should "succeed for a valid SCORM package" in {
        val manifest = """<manifest><organizations default="org"><organization identifier="org"><item identifierref="res"/></organization></organizations><resources><resource identifier="res" href="index.html"/></resources></manifest>"""
        val file = createZip(Map("imsmanifest.xml" -> manifest, "index.html" -> "<html></html>"))

        (ss.uploadFile(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("s3Key", "s3Url"))
        (ss.uploadDirectory(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("url"))

        scormMgr.upload("do_1", getNode(), file, None, UploadParams()).map { result =>
            result("launchFile") shouldBe "index.html"
            FileUtils.deleteQuietly(file)
            succeed
        }
    }

    // Asset exclusion - verify items with scormType="asset" are filtered out
    "upload" should "exclude items that are explicitly defined as assets in SCORM 2004" in {
        val manifest = """<manifest xmlns="http://www.imsglobal.org/xsd/imscp_v1p1" xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_v1p3"><metadata><schema>ADL SCORM</schema><schemaversion>2004</schemaversion></metadata><organizations default="org"><organization identifier="org"><item identifier="item1" identifierref="res1"><title>SCO 1</title></item><item identifier="item2" identifierref="res2"><title>Asset 1</title></item></organization></organizations><resources><resource identifier="res1" href="sco1/index.html" adlcp:scormType="sco"/><resource identifier="res2" href="asset1/image.png" adlcp:scormType="asset"/></resources></manifest>"""
        val file = createZip(Map("imsmanifest.xml" -> manifest, "sco1/index.html" -> "<html></html>", "asset1/image.png" -> "image data"))

        (ss.uploadFile(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("s3Key", "s3Url"))
        (ss.uploadDirectory(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("url"))

        scormMgr.upload("do_1", getNode(), file, None, UploadParams()).map { result =>
            result("scormVersion") shouldBe "2004"
            val javaList = result("scoList").asInstanceOf[java.util.List[java.util.Map[String, String]]]
            val scoList = javaList.iterator().asScala.map(_.asScala.toMap).toList
            scoList.size shouldBe 1
            scoList.exists(sco => sco("identifier") == "item1") shouldBe true
            scoList.exists(sco => sco("identifier") == "item2") shouldBe false
            FileUtils.deleteQuietly(file)
            succeed
        }
    }
    }

    // Version detection - no metadata
    "upload" should "detect SCORM 1.2 when metadata block is missing" in {
        val manifest = """<manifest xmlns="http://www.imsglobal.org/xsd/imscp_v1p1" xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_v1p3"><organizations default="org"><organization identifier="org"><item identifier="item1" identifierref="res1"><title>SCO 1</title></item></organization></organizations><resources><resource identifier="res1" href="index.html"/></resources></manifest>"""
        val file = createZip(Map("imsmanifest.xml" -> manifest, "index.html" -> "<html></html>"))

        (ss.uploadFile(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("s3Key", "s3Url"))
        (ss.uploadDirectory(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("url"))

        scormMgr.upload("do_1", getNode(), file, None, UploadParams()).map { result =>
            result("scormVersion") shouldBe "1.2"
            FileUtils.deleteQuietly(file)
            succeed
        }
    }

    // Version detection - CAM 1.3
    "upload" should "detect SCORM 2004 when schemaVersion is cam 1.3" in {
        val manifest = """<manifest xmlns="http://www.imsglobal.org/xsd/imscp_v1p1" xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_v1p3"><metadata><schema>cam</schema><schemaversion>1.3</schemaversion></metadata><organizations default="org"><organization identifier="org"><item identifier="item1" identifierref="res1"><title>SCO 1</title></item></organization></organizations><resources><resource identifier="res1" href="index.html" adlcp:scormType="sco"/></resources></manifest>"""
        val file = createZip(Map("imsmanifest.xml" -> manifest, "index.html" -> "<html></html>"))

        (ss.uploadFile(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("s3Key", "s3Url"))
        (ss.uploadDirectory(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("url"))

        scormMgr.upload("do_1", getNode(), file, None, UploadParams()).map { result =>
            result("scormVersion") shouldBe "2004"
            FileUtils.deleteQuietly(file)
            succeed
        }
    }

    // Unrecognized version
    "upload" should "throw ClientException for unrecognized SCORM version" in {
        val manifest = """<manifest xmlns="http://www.imsglobal.org/xsd/imscp_v1p1" xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_v1p3"><metadata><schema>ADL SCORM</schema><schemaversion>3.0</schemaversion></metadata><organizations default="org"><organization identifier="org"><item identifier="item1" identifierref="res1"><title>SCO 1</title></item></organization></organizations><resources><resource identifier="res1" href="index.html"/></resources></manifest>"""
        val file = createZip(Map("imsmanifest.xml" -> manifest, "index.html" -> "<html></html>"))

        Future { scormMgr.upload("do_1", getNode(), file, None, UploadParams()) }.flatten.transform {
            case scala.util.Failure(e: ClientException) =>
                e.getErrCode shouldBe "ERR_INVALID_FILE"
                FileUtils.deleteQuietly(file)
                scala.util.Success(succeed)
            case scala.util.Failure(e) =>
                FileUtils.deleteQuietly(file)
                scala.util.Failure(new Exception(s"Expected ClientException, got ${e.getClass.getName}: ${e.getMessage}", e))
            case scala.util.Success(_) =>
                FileUtils.deleteQuietly(file)
                scala.util.Failure(new Exception("Expected ClientException, but upload succeeded"))
        }
    }

    // xml:base composition
    "upload" should "compose xml:base attributes correctly to form final href" in {
        val manifest = """<manifest xml:base="base1/" xmlns="http://www.imsglobal.org/xsd/imscp_v1p1" xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_v1p3"><metadata><schema>ADL SCORM</schema><schemaversion>2004</schemaversion></metadata><organizations default="org"><organization identifier="org"><item identifier="item1" identifierref="res1" parameters="?test=1"><title>SCO 1</title></item></organization></organizations><resources xml:base="base2/"><resource identifier="res1" href="index.html" xml:base="base3/" adlcp:scormType="sco"/></resources></manifest>"""
        val file = createZip(Map("imsmanifest.xml" -> manifest, "base1/base2/base3/index.html" -> "<html></html>"))

        (ss.uploadFile(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("s3Key", "s3Url"))
        (ss.uploadDirectory(_: String, _: File, _: Option[Boolean])).expects(*, *, *).returns(Array("url"))

        scormMgr.upload("do_1", getNode(), file, None, UploadParams()).map { result =>
            val javaList = result("scoList").asInstanceOf[java.util.List[java.util.Map[String, String]]]
            val scoList = javaList.iterator().asScala.map(_.asScala.toMap).toList
            scoList.head("href") shouldBe "base1/base2/base3/index.html?test=1"
            result("launchFile") shouldBe "base1/base2/base3/index.html?test=1"
            FileUtils.deleteQuietly(file)
            succeed
        }
    }

    // Missing manifest — zip without imsmanifest.xml throws ClientException synchronously;
    // wrap in Future{}.flatten so that .transform can handle it.
    "upload" should "throw ClientException for zip missing imsmanifest.xml" in {
        val file = createZip(Map("index.html" -> "<html></html>"))
        Future { scormMgr.upload("do_1", getNode(), file, None, UploadParams()) }.flatten.transform {
            case scala.util.Failure(e: ClientException) =>
                e.getErrCode shouldBe "ERR_INVALID_FILE"
                FileUtils.deleteQuietly(file)
                scala.util.Success(succeed)
            case scala.util.Failure(e) =>
                FileUtils.deleteQuietly(file)
                scala.util.Failure(new Exception(s"Expected ClientException, got ${e.getClass.getName}: ${e.getMessage}", e))
            case scala.util.Success(_) =>
                FileUtils.deleteQuietly(file)
                scala.util.Failure(new Exception("Expected ClientException, but upload succeeded"))
        }
    }

    // Missing launch file — manifest references a file absent from the zip throws ClientException
    "upload" should "throw ClientException when launch file is absent from package" in {
        val manifest = """<manifest xmlns="http://www.imsglobal.org/xsd/imscp_v1p1" xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_v1p3"><organizations default="org"><organization identifier="org"><item identifierref="res"/></organization></organizations><resources><resource identifier="res" href="missing.html"/></resources></manifest>"""
        val file = createZip(Map("imsmanifest.xml" -> manifest))
        Future { scormMgr.upload("do_1", getNode(), file, None, UploadParams()) }.flatten.transform {
            case scala.util.Failure(e: ClientException) =>
                e.getErrCode shouldBe "ERR_INVALID_FILE"
                FileUtils.deleteQuietly(file)
                scala.util.Success(succeed)
            case scala.util.Failure(e) =>
                FileUtils.deleteQuietly(file)
                scala.util.Failure(new Exception(s"Expected ClientException, got ${e.getClass.getName}: ${e.getMessage}", e))
            case scala.util.Success(_) =>
                FileUtils.deleteQuietly(file)
                scala.util.Failure(new Exception("Expected ClientException, but upload succeeded"))
        }
    }

    // Path traversal in manifest — manifest href "../../../etc/passwd" throws ClientException
    "upload" should "throw ClientException for href with path traversal" in {
        val manifest = """<manifest xmlns="http://www.imsglobal.org/xsd/imscp_v1p1" xmlns:adlcp="http://www.adlnet.org/xsd/adlcp_v1p3"><organizations default="org"><organization identifier="org"><item identifierref="res"/></organization></organizations><resources><resource identifier="res" href="../../../etc/passwd"/></resources></manifest>"""
        val file = createZip(Map("imsmanifest.xml" -> manifest))
        Future { scormMgr.upload("do_1", getNode(), file, None, UploadParams()) }.flatten.transform {
            case scala.util.Failure(e: ClientException) =>
                e.getErrCode shouldBe "ERR_INVALID_FILE"
                FileUtils.deleteQuietly(file)
                scala.util.Success(succeed)
            case scala.util.Failure(e) =>
                FileUtils.deleteQuietly(file)
                scala.util.Failure(new Exception(s"Expected ClientException, got ${e.getClass.getName}: ${e.getMessage}", e))
            case scala.util.Success(_) =>
                FileUtils.deleteQuietly(file)
                scala.util.Failure(new Exception("Expected ClientException, but upload succeeded"))
        }
    }

    // review with no artifact — validate() throws ClientException("VALIDATOR_ERROR", ...) synchronously
    "review" should "throw ClientException when no artifactUrl is present" in {
        val node = getNode()
        Future { scormMgr.review("do_1", node) }.flatten.transform {
            case scala.util.Failure(e: ClientException) =>
                e.getErrCode shouldBe "VALIDATOR_ERROR"
                scala.util.Success(succeed)
            case scala.util.Failure(e) =>
                scala.util.Failure(new Exception(s"Expected ClientException, got ${e.getClass.getName}: ${e.getMessage}", e))
            case scala.util.Success(_) =>
                scala.util.Failure(new Exception("Expected ClientException, but review succeeded"))
        }
    }
}
