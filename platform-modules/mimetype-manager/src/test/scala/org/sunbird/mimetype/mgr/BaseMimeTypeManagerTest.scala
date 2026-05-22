package org.sunbird.mimetype.mgr

import org.scalamock.scalatest.MockFactory
import org.scalatest.{FlatSpec, Matchers}
import org.sunbird.cloudstore.StorageService
import org.sunbird.common.exception.ClientException

import java.io.{File, FileOutputStream}
import java.nio.file.{Files, Paths}
import java.util.zip.{ZipEntry, ZipOutputStream}

class BaseMimeTypeManagerTest extends FlatSpec with Matchers with MockFactory {

  implicit val ss: StorageService = mock[StorageService]

  val manager = new BaseMimeTypeManager()

  "extractPackage" should "extract valid zip entries within basePath" in {
    val (zipFile, basePath) = createTestZip(Map("index.html" -> "hello", "assets/style.css" -> "body{}"))
    try {
      manager.extractPackage(zipFile, basePath)
      new File(basePath + "/index.html").exists() shouldBe true
      new File(basePath + "/assets/style.css").exists() shouldBe true
    } finally {
      deleteDir(new File(basePath))
      zipFile.delete()
    }
  }

  it should "block zip entry with ../ path traversal" in {
    val (zipFile, basePath) = createTestZip(Map("../../etc/evil" -> "malicious"))
    try {
      val ex = intercept[ClientException] {
        manager.extractPackage(zipFile, basePath)
      }
      ex.getMessage should include("path traversal")
    } finally {
      deleteDir(new File(basePath))
      zipFile.delete()
    }
  }

  it should "block zip entry with deep traversal" in {
    val (zipFile, basePath) = createTestZip(Map("../../../tmp/evil.sh" -> "#!/bin/sh\nrm -rf /"))
    try {
      val ex = intercept[ClientException] {
        manager.extractPackage(zipFile, basePath)
      }
      ex.getMessage should include("path traversal")
    } finally {
      deleteDir(new File(basePath))
      zipFile.delete()
    }
  }

  it should "block absolute path in zip entry" in {
    val (zipFile, basePath) = createTestZip(Map("/tmp/absolute_write" -> "danger"))
    try {
      val ex = intercept[ClientException] {
        manager.extractPackage(zipFile, basePath)
      }
      ex.getMessage should include("path traversal")
    } finally {
      deleteDir(new File(basePath))
      zipFile.delete()
    }
  }

  it should "allow nested directories within basePath" in {
    val (zipFile, basePath) = createTestZip(Map("a/b/c/deep.txt" -> "ok"))
    try {
      manager.extractPackage(zipFile, basePath)
      new File(basePath + "/a/b/c/deep.txt").exists() shouldBe true
    } finally {
      deleteDir(new File(basePath))
      zipFile.delete()
    }
  }

  it should "block entry that normalizes outside basePath" in {
    // "safe/../../../etc/passwd" normalizes to outside basePath
    val (zipFile, basePath) = createTestZip(Map("safe/../../../etc/passwd" -> "root:x:0:0"))
    try {
      val ex = intercept[ClientException] {
        manager.extractPackage(zipFile, basePath)
      }
      ex.getMessage should include("path traversal")
    } finally {
      deleteDir(new File(basePath))
      zipFile.delete()
    }
  }

  private def createTestZip(entries: Map[String, String]): (File, String) = {
    val basePath = Files.createTempDirectory("zipslip_test_").toString
    val zipFile = File.createTempFile("test_", ".zip")
    val zos = new ZipOutputStream(new FileOutputStream(zipFile))
    entries.foreach { case (name, content) =>
      zos.putNextEntry(new ZipEntry(name))
      zos.write(content.getBytes)
      zos.closeEntry()
    }
    zos.close()
    (zipFile, basePath)
  }

  private def deleteDir(dir: File): Unit = {
    if (dir.exists()) {
      Option(dir.listFiles()).foreach(_.foreach { f =>
        if (f.isDirectory) deleteDir(f) else f.delete()
      })
      dir.delete()
    }
  }
}
