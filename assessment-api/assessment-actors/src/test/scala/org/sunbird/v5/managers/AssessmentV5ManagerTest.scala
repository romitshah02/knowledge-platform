package org.sunbird.v5.managers

import org.scalatest.{FlatSpec, Matchers}
import org.sunbird.common.dto.Request
import org.sunbird.common.exception.ClientException

import java.util
import scala.collection.JavaConverters._
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

class AssessmentV5ManagerTest extends FlatSpec with Matchers {

  // === getAnswer: single cardinality ===

  "getAnswer" should "return HTML with escaped label for single cardinality" in {
    val data = buildSingleCardinalityData("Paris")
    val result = AssessmentV5Manager.getAnswer(data)
    result should include("<div class=\"anwser-container\">")
    result should include("Paris")
  }

  it should "escape script tag in single cardinality label" in {
    val data = buildSingleCardinalityData("<script>alert('xss')</script>")
    val result = AssessmentV5Manager.getAnswer(data)
    result should not include "<script>"
    result should include("&lt;script&gt;")
  }

  it should "escape img onerror XSS payload in single cardinality label" in {
    val data = buildSingleCardinalityData("<img src=x onerror=\"steal(document.cookie)\">")
    val result = AssessmentV5Manager.getAnswer(data)
    result should not include "<img"
    result should include("&lt;img")
    result should not include "onerror=\"steal"
    result should include("&quot;")
  }

  it should "escape nested XSS with event handler in single cardinality label" in {
    val data = buildSingleCardinalityData("<div onmouseover=\"alert(document.cookie)\">hover</div>")
    val result = AssessmentV5Manager.getAnswer(data)
    // Raw div tag must be escaped so onmouseover can't execute
    result should not include "<div onmouseover"
    result should include("&lt;div")
  }

  it should "preserve plain text label in single cardinality" in {
    val data = buildSingleCardinalityData("42 is the answer")
    val result = AssessmentV5Manager.getAnswer(data)
    result should include("42 is the answer")
    result should include("<div class=\"anwser-body\">")
  }

  it should "escape ampersand in single cardinality label" in {
    val data = buildSingleCardinalityData("Tom & Jerry")
    val result = AssessmentV5Manager.getAnswer(data)
    result should include("Tom &amp; Jerry")
  }

  // === getAnswer: multiple cardinality ===

  it should "escape XSS payloads in multiple cardinality labels" in {
    val data = buildMultipleCardinalityData(
      List("<script>evil()</script>Option A", "<img src=x onerror=alert(1)>Option B"),
      List(0, 1)
    )
    val result = AssessmentV5Manager.getAnswer(data)
    result should not include "<script>"
    result should not include "<img"
    result should include("&lt;script&gt;")
    result should include("&lt;img")
  }

  it should "return correct HTML structure for multiple cardinality" in {
    val data = buildMultipleCardinalityData(
      List("Option A", "Option B"),
      List(0, 1)
    )
    val result = AssessmentV5Manager.getAnswer(data)
    result should include("<div class=\"anwser-container\">")
    result should include("Option A")
    result should include("Option B")
    // Should have two anwser-body divs
    result.split("anwser-body").length should be(3) // 2 occurrences = 3 splits
  }

  it should "escape only correct answers in multiple cardinality" in {
    val data = buildMultipleCardinalityData(
      List("<script>xss</script>Right", "Wrong", "<img src=x>Also Right"),
      List(0, 2)
    )
    val result = AssessmentV5Manager.getAnswer(data)
    result should not include "<script>"
    result should include("&lt;script&gt;")
    result should not include "Wrong" // Wrong is value=1, not in correctResponse
    result should include("&lt;img")
  }

  // === getAnswer: subjective question bypass ===

  it should "return raw answer field for Subjective Question" in {
    val data = new util.HashMap[String, AnyRef]()
    data.put("primaryCategory", "Subjective Question")
    data.put("answer", "This is a text answer")
    val result = AssessmentV5Manager.getAnswer(data)
    result shouldBe "This is a text answer"
  }

  it should "return empty string when no answer field for Subjective Question" in {
    val data = new util.HashMap[String, AnyRef]()
    data.put("primaryCategory", "Subjective Question")
    val result = AssessmentV5Manager.getAnswer(data)
    result shouldBe ""
  }

  // === getAnswer: cookie/JWT theft payload (exact from security report) ===

  it should "prevent cookie theft XSS payload from security report XSS-VULN-02" in {
    val payload = "<img src=x onerror=\"document.title='XSS-VULN-02: '+document.cookie\">"
    val data = buildSingleCardinalityData(payload)
    val result = AssessmentV5Manager.getAnswer(data)
    // Raw HTML tags must be escaped — browser will render as text, not execute
    result should not include "<img"
    result should include("&lt;img")
    // onerror attribute must be escaped so it can't execute
    result should not include "onerror=\"document"
    result should include("onerror=&quot;")
  }

  it should "prevent JWT theft XSS payload" in {
    val payload = "<img src=x onerror=\"var d=document.createElement('div');d.id='xss-proof';" +
      "d.innerText='COOKIES:'+document.cookie+' JWT:'+localStorage.getItem('access_token');" +
      "document.body.appendChild(d)\">"
    val data = buildSingleCardinalityData(payload)
    val result = AssessmentV5Manager.getAnswer(data)
    // Raw HTML tags must be escaped — browser will render as text, not execute
    result should not include "<img"
    result should include("&lt;img")
    // Quotes must be escaped so onerror handler can't execute
    result should not include "onerror=\"var"
    result should include("onerror=&quot;")
  }

  // === getValidatedNodeForUpdateComment ===

  it should "return failed future when reviewComment is null" in {
    val request = buildCommentRequest(null)
    val future = AssessmentV5Manager.getValidatedNodeForUpdateComment(request, "ERR_TEST")(global, null)
    val ex = intercept[ClientException] {
      Await.result(future, 5.seconds)
    }
    ex.getMessage shouldBe "Comment key is missing or value is empty in the request body."
  }

  it should "return failed future when reviewComment is empty string" in {
    val request = buildCommentRequest("")
    val future = AssessmentV5Manager.getValidatedNodeForUpdateComment(request, "ERR_TEST")(global, null)
    val ex = intercept[ClientException] {
      Await.result(future, 5.seconds)
    }
    ex.getMessage shouldBe "Comment key is missing or value is empty in the request body."
  }

  it should "return failed future when reviewComment is whitespace only" in {
    val request = buildCommentRequest("   ")
    val future = AssessmentV5Manager.getValidatedNodeForUpdateComment(request, "ERR_TEST")(global, null)
    val ex = intercept[ClientException] {
      Await.result(future, 5.seconds)
    }
    ex.getMessage shouldBe "Comment key is missing or value is empty in the request body."
  }

  // === Helper methods ===

  private def buildSingleCardinalityData(label: String): util.Map[String, AnyRef] = {
    val option0 = new util.HashMap[String, AnyRef]()
    option0.put("value", Integer.valueOf(0))
    option0.put("label", label)

    val option1 = new util.HashMap[String, AnyRef]()
    option1.put("value", Integer.valueOf(1))
    option1.put("label", "Wrong answer")

    val options = new util.ArrayList[util.Map[String, AnyRef]]()
    options.add(option0)
    options.add(option1)

    val resp1Interactions = new util.HashMap[String, AnyRef]()
    resp1Interactions.put("options", options)

    val interactions = new util.HashMap[String, AnyRef]()
    interactions.put("response1", resp1Interactions)

    val correctResponse = new util.HashMap[String, AnyRef]()
    correctResponse.put("value", Integer.valueOf(0))

    val response1 = new util.HashMap[String, AnyRef]()
    response1.put("cardinality", "single")
    response1.put("correctResponse", correctResponse)

    val responseDeclaration = new util.HashMap[String, AnyRef]()
    responseDeclaration.put("response1", response1)

    val data = new util.HashMap[String, AnyRef]()
    data.put("primaryCategory", "Multiple Choice Question")
    data.put("interactions", interactions)
    data.put("responseDeclaration", responseDeclaration)
    data
  }

  private def buildMultipleCardinalityData(labels: List[String], correctValues: List[Int]): util.Map[String, AnyRef] = {
    val options = new util.ArrayList[util.Map[String, AnyRef]]()
    labels.zipWithIndex.foreach { case (label, idx) =>
      val option = new util.HashMap[String, AnyRef]()
      option.put("value", Integer.valueOf(idx))
      option.put("label", label)
      options.add(option)
    }

    val resp1Interactions = new util.HashMap[String, AnyRef]()
    resp1Interactions.put("options", options)

    val interactions = new util.HashMap[String, AnyRef]()
    interactions.put("response1", resp1Interactions)

    val correctResponse = new util.HashMap[String, AnyRef]()
    correctResponse.put("value", correctValues.map(Integer.valueOf).asJava)

    val response1 = new util.HashMap[String, AnyRef]()
    response1.put("cardinality", "multiple")
    response1.put("correctResponse", correctResponse)

    val responseDeclaration = new util.HashMap[String, AnyRef]()
    responseDeclaration.put("response1", response1)

    val data = new util.HashMap[String, AnyRef]()
    data.put("primaryCategory", "Multiple Choice Question")
    data.put("interactions", interactions)
    data.put("responseDeclaration", responseDeclaration)
    data
  }

  private def buildCommentRequest(comment: AnyRef): Request = {
    val request = new Request()
    request.setContext(new util.HashMap[String, AnyRef]() {
      { put("identifier", "do_1234") }
    })
    if (comment != null) {
      request.getRequest.put("reviewComment", comment)
    }
    request
  }
}
