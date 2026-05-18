package org.sunbird.search.util;

import org.junit.Assert;
import org.junit.Test;
import org.sunbird.common.exception.ClientException;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SearchInputValidatorTest {

    // === escapeRegexValue tests (INJ-VULN-01, INJ-VULN-02) ===

    @Test
    public void testEscapeRegexWildcard() {
        String result = SearchInputValidator.escapeRegexValue(".*");
        Assert.assertEquals("\\.\\*", result);
    }

    @Test
    public void testEscapeRegexDot() {
        String result = SearchInputValidator.escapeRegexValue("test.value");
        Assert.assertEquals("test\\.value", result);
    }

    @Test
    public void testEscapeRegexPipe() {
        String result = SearchInputValidator.escapeRegexValue("a|b");
        Assert.assertEquals("a\\|b", result);
    }

    @Test
    public void testEscapeRegexBrackets() {
        String result = SearchInputValidator.escapeRegexValue("[a-z]");
        Assert.assertEquals("\\[a-z\\]", result);
    }

    @Test
    public void testEscapeRegexParentheses() {
        String result = SearchInputValidator.escapeRegexValue("(group)");
        Assert.assertEquals("\\(group\\)", result);
    }

    @Test
    public void testEscapeRegexPlainText() {
        String result = SearchInputValidator.escapeRegexValue("normal text");
        Assert.assertEquals("normal text", result);
    }

    @Test
    public void testEscapeRegexNull() {
        Assert.assertNull(SearchInputValidator.escapeRegexValue(null));
    }

    @Test
    public void testEscapeRegexEmpty() {
        Assert.assertEquals("", SearchInputValidator.escapeRegexValue(""));
    }

    @Test
    public void testEscapeRegexFullEnumerationPayload() {
        // INJ-VULN-01: attacker sends ".*" as contains value to dump entire index
        String result = SearchInputValidator.escapeRegexValue(".*");
        Assert.assertFalse(result.equals(".*"));
        Assert.assertEquals("\\.\\*", result);
    }

    @Test
    public void testEscapeRegexComplexPayload() {
        String result = SearchInputValidator.escapeRegexValue(".*test{1,2}[abc]");
        // Wildcards and brackets must be escaped
        Assert.assertFalse(result.startsWith(".*"));
        Assert.assertFalse(result.contains("[abc]"));
        Assert.assertTrue(result.contains("\\.\\*"));
        Assert.assertTrue(result.contains("\\[abc\\]"));
    }

    // === validateFacets tests (INJ-VULN-03) ===

    @Test
    public void testValidFacets() {
        List<String> facets = Arrays.asList("objectType", "status", "mimeType");
        SearchInputValidator.validateFacets(facets); // should not throw
    }

    @Test
    public void testNullFacets() {
        SearchInputValidator.validateFacets(null); // should not throw
    }

    @Test
    public void testEmptyFacets() {
        SearchInputValidator.validateFacets(Collections.emptyList()); // should not throw
    }

    @Test(expected = ClientException.class)
    public void testInvalidFacetField() {
        // INJ-VULN-03: attacker probes arbitrary field names via facets
        List<String> facets = Arrays.asList("objectType", "_internal_field");
        SearchInputValidator.validateFacets(facets);
    }

    @Test(expected = ClientException.class)
    public void testFacetFieldProbing() {
        List<String> facets = Arrays.asList("password", "secretKey");
        SearchInputValidator.validateFacets(facets);
    }

    // === validateSortBy tests (INJ-VULN-04) ===

    @Test
    public void testValidSortBy() {
        Map<String, String> sortBy = new HashMap<>();
        sortBy.put("name", "asc");
        sortBy.put("lastUpdatedOn", "desc");
        SearchInputValidator.validateSortBy(sortBy); // should not throw
    }

    @Test
    public void testNullSortBy() {
        SearchInputValidator.validateSortBy(null); // should not throw
    }

    @Test(expected = ClientException.class)
    public void testInvalidSortField() {
        // INJ-VULN-04: attacker uses invalid sort fields to leak infrastructure info
        Map<String, String> sortBy = new HashMap<>();
        sortBy.put("nonExistentField", "asc");
        SearchInputValidator.validateSortBy(sortBy);
    }

    @Test(expected = ClientException.class)
    public void testInvalidSortDirection() {
        Map<String, String> sortBy = new HashMap<>();
        sortBy.put("name", "INVALID");
        SearchInputValidator.validateSortBy(sortBy);
    }

    // === validateExistsFields tests (INJ-VULN-06) ===

    @Test
    public void testValidExistsFields() {
        List<String> fields = Arrays.asList("name", "description", "artifactUrl");
        SearchInputValidator.validateExistsFields(fields); // should not throw
    }

    @Test
    public void testNullExistsFields() {
        SearchInputValidator.validateExistsFields(null); // should not throw
    }

    @Test(expected = ClientException.class)
    public void testInvalidExistsField() {
        // INJ-VULN-06: attacker probes field existence as binary oracle
        List<String> fields = Arrays.asList("name", "_secret_internal_field");
        SearchInputValidator.validateExistsFields(fields);
    }

    @Test(expected = ClientException.class)
    public void testExistsFieldsTooMany() {
        List<String> fields = Arrays.asList(
                "name", "description", "status", "mimeType", "primaryCategory",
                "objectType", "contentType", "channel", "framework", "board", "medium"
        );
        SearchInputValidator.validateExistsFields(fields); // 11 > max 10
    }

    // === validateSoftConstraints tests (INJ-VULN-08) ===

    @Test
    public void testValidSoftConstraints() {
        Map<String, Object> sc = new HashMap<>();
        sc.put("board", 100);
        sc.put("gradeLevel", 50);
        SearchInputValidator.validateSoftConstraints(sc); // should not throw
    }

    @Test
    public void testNullSoftConstraints() {
        SearchInputValidator.validateSoftConstraints(null); // should not throw
    }

    @Test(expected = ClientException.class)
    public void testSoftConstraintWithDotNotation() {
        // INJ-VULN-08: attacker uses dot notation for nested field probing
        Map<String, Object> sc = new HashMap<>();
        sc.put("nested.field.probe", 100);
        SearchInputValidator.validateSoftConstraints(sc);
    }

    @Test(expected = ClientException.class)
    public void testSoftConstraintWithBracketNotation() {
        Map<String, Object> sc = new HashMap<>();
        sc.put("field[0]", 100);
        SearchInputValidator.validateSoftConstraints(sc);
    }
}
