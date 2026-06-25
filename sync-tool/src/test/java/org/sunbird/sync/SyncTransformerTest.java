package org.sunbird.sync;

import org.junit.Before;
import org.junit.Test;

import java.util.*;

import static org.junit.Assert.*;

public class SyncTransformerTest {

    private SyncConfig config;
    private SyncTransformer transformer;

    @Before
    public void setUp() {
        config = new SyncConfig(
                200, 3,
                Arrays.asList("badgeAssertions", "targets"),          // nestedFields (not used for deserialization in graph-read path)
                Arrays.asList("editorState", "options"),               // stringOnlyFields
                Arrays.asList("responseDeclaration", "body"),          // ignoredFields
                Arrays.asList("EventSet", "Questionnaire"),            // restrictObjectTypes
                "domain",
                "compositesearch",
                "localhost:9200"
        );
        transformer = new SyncTransformer(config);
    }

    // --- System field renaming ---

    @Test
    public void testRenamesSystemFields() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("IL_SYS_NODE_TYPE", "DATA_NODE");
        props.put("name", "Test Content");

        Map<String, Object> doc = transformer.transform(props);

        assertEquals("do_123", doc.get("identifier"));
        assertEquals("Content", doc.get("objectType"));
        assertEquals("DATA_NODE", doc.get("nodeType"));
        assertNull(doc.get("IL_UNIQUE_ID"));
        assertNull(doc.get("IL_FUNC_OBJECT_TYPE"));
        assertNull(doc.get("IL_SYS_NODE_TYPE"));
    }

    // --- System fields added ---

    @Test
    public void testAddsGraphId() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");

        Map<String, Object> doc = transformer.transform(props);

        assertEquals("domain", doc.get("graph_id"));
    }

    @Test
    public void testAddsNodeId() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("id", 12345L);

        Map<String, Object> doc = transformer.transform(props);

        assertEquals(12345L, doc.get("node_id"));
        assertNull(doc.get("id")); // original "id" key should be skipped
    }

    // --- Ignored fields ---

    @Test
    public void testDropsIgnoredFields() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("name", "Test");
        props.put("body", "<html>large content</html>");
        props.put("responseDeclaration", "{\"response1\":{}}");

        Map<String, Object> doc = transformer.transform(props);

        assertNull(doc.get("body"));
        assertNull(doc.get("responseDeclaration"));
        assertEquals("Test", doc.get("name"));
    }

    // --- Restricted object types ---

    @Test
    public void testReturnsNullForRestrictedObjectType() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_999");
        props.put("IL_FUNC_OBJECT_TYPE", "EventSet");

        Map<String, Object> doc = transformer.transform(props);

        assertNull(doc);
    }

    @Test
    public void testAllowsNonRestrictedObjectType() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");

        Map<String, Object> doc = transformer.transform(props);

        assertNotNull(doc);
    }

    // --- JSON string deserialization (mirrors buildCompositeIndexerFromGraph) ---
    // All {/[ strings get deserialized regardless of nestedFields config

    @Test
    public void testDeserializesJsonArrayString() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("badgeAssertions", "[{\"id\":\"badge1\"},{\"id\":\"badge2\"}]");

        Map<String, Object> doc = transformer.transform(props);

        assertTrue(doc.get("badgeAssertions") instanceof List);
        List<?> badges = (List<?>) doc.get("badgeAssertions");
        assertEquals(2, badges.size());
    }

    @Test
    public void testDeserializesJsonObjectString() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("targets", "{\"id\":\"target1\"}");

        Map<String, Object> doc = transformer.transform(props);

        assertTrue(doc.get("targets") instanceof Map);
    }

    @Test
    public void testDeserializesJsonObjectStringNotInNestedFields() {
        // credentials, trackable etc. are NOT in nestedFields config
        // but should still be deserialized (same as Flink graph-read path)
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("credentials", "{\"enabled\":\"No\"}");
        props.put("trackable", "{\"enabled\":\"No\",\"autoBatch\":\"No\"}");

        Map<String, Object> doc = transformer.transform(props);

        assertTrue(doc.get("credentials") instanceof Map);
        assertTrue(doc.get("trackable") instanceof Map);
        assertEquals("No", ((Map<?, ?>) doc.get("credentials")).get("enabled"));
    }

    @Test
    public void testDeserializesJsonArrayStringNotInNestedFields() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("keywords", "[\"math\",\"science\"]");

        Map<String, Object> doc = transformer.transform(props);

        assertTrue(doc.get("keywords") instanceof List);
        List<?> keywords = (List<?>) doc.get("keywords");
        assertEquals(2, keywords.size());
    }

    @Test
    public void testKeepsNonJsonStringAsIs() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("name", "plain text value");

        Map<String, Object> doc = transformer.transform(props);

        assertEquals("plain text value", doc.get("name"));
    }

    @Test
    public void testInvalidJsonKeptAsString() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("someField", "[invalid json");

        Map<String, Object> doc = transformer.transform(props);

        assertEquals("[invalid json", doc.get("someField"));
    }

    // --- String-only fields ---

    @Test
    public void testStringOnlyFieldSerializesListToJson() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("options", Arrays.asList("opt1", "opt2"));

        Map<String, Object> doc = transformer.transform(props);

        assertTrue(doc.get("options") instanceof String);
        assertEquals("[\"opt1\",\"opt2\"]", doc.get("options"));
    }

    @Test
    public void testStringOnlyFieldSerializesMapToJson() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        Map<String, Object> editorState = new HashMap<>();
        editorState.put("key", "value");
        props.put("editorState", editorState);

        Map<String, Object> doc = transformer.transform(props);

        assertTrue(doc.get("editorState") instanceof String);
        assertTrue(((String) doc.get("editorState")).contains("\"key\""));
    }

    @Test
    public void testStringOnlyFieldKeepsStringAsIs() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("editorState", "{\"already\":\"a string\"}");

        Map<String, Object> doc = transformer.transform(props);

        // stringOnlyFields: strings stay as strings (not deserialized)
        assertEquals("{\"already\":\"a string\"}", doc.get("editorState"));
    }

    // --- Empty list handling (Flink getIndexDocument: empty list → null → removed) ---

    @Test
    public void testEmptyListRemovedFromDocument() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("keywords", new ArrayList<>());

        Map<String, Object> doc = transformer.transform(props);

        assertFalse(doc.containsKey("keywords"));
    }

    // --- Regular fields ---

    @Test
    public void testRegularFieldKeepsPlainString() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("name", "Test Content");

        Map<String, Object> doc = transformer.transform(props);

        assertEquals("Test Content", doc.get("name"));
    }

    @Test
    public void testRegularFieldKeepsNonStringTypes() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("size", 1024);
        props.put("live", true);

        Map<String, Object> doc = transformer.transform(props);

        assertEquals(1024, doc.get("size"));
        assertEquals(true, doc.get("live"));
    }

    // --- Truncation ---

    @Test
    public void testTruncatesLongStrings() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        StringBuilder longValue = new StringBuilder();
        for (int i = 0; i < 35000; i++) longValue.append("x");
        props.put("description", longValue.toString());

        Map<String, Object> doc = transformer.transform(props);

        assertEquals(32000, ((String) doc.get("description")).length());
    }

    @Test
    public void testDoesNotTruncateShortStrings() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("name", "Short string");

        Map<String, Object> doc = transformer.transform(props);

        assertEquals("Short string", doc.get("name"));
    }

    // --- JanusGraph internal fields ---

    @Test
    public void testSkipsJanusGraphInternalFields() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("T.id", 12345);
        props.put("T.label", "vertex");

        Map<String, Object> doc = transformer.transform(props);

        assertNull(doc.get("T.id"));
        assertNull(doc.get("T.label"));
    }

    // --- Null handling ---

    @Test
    public void testNullValueRemovedFromDocument() {
        Map<String, Object> props = new HashMap<>();
        props.put("IL_UNIQUE_ID", "do_123");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("description", null);

        Map<String, Object> doc = transformer.transform(props);

        assertFalse(doc.containsKey("description"));
    }

    // --- Full document structure ---

    @Test
    public void testFullTransformation() {
        Map<String, Object> props = new HashMap<>();
        props.put("id", 99999L);
        props.put("IL_UNIQUE_ID", "do_555");
        props.put("IL_FUNC_OBJECT_TYPE", "Content");
        props.put("IL_SYS_NODE_TYPE", "DATA_NODE");
        props.put("name", "Math Lesson");
        props.put("status", "Live");
        props.put("body", "<html>heavy</html>");
        props.put("responseDeclaration", "{}");
        props.put("keywords", "[\"math\",\"grade5\"]");
        props.put("badgeAssertions", "[{\"id\":\"b1\"}]");
        props.put("editorState", Arrays.asList("state1"));
        props.put("credentials", "{\"enabled\":\"No\"}");
        props.put("T.label", "vertex");

        Map<String, Object> doc = transformer.transform(props);

        // Renamed
        assertEquals("do_555", doc.get("identifier"));
        assertEquals("Content", doc.get("objectType"));
        assertEquals("DATA_NODE", doc.get("nodeType"));

        // System fields
        assertEquals("domain", doc.get("graph_id"));
        assertEquals(99999L, doc.get("node_id"));

        // Regular fields
        assertEquals("Math Lesson", doc.get("name"));
        assertEquals("Live", doc.get("status"));

        // Ignored
        assertNull(doc.get("body"));
        assertNull(doc.get("responseDeclaration"));

        // JSON array deserialized
        assertTrue(doc.get("keywords") instanceof List);

        // JSON object deserialized (even though not in nestedFields config)
        assertTrue(doc.get("badgeAssertions") instanceof List);
        assertTrue(doc.get("credentials") instanceof Map);

        // String-only field serialized back to string
        assertTrue(doc.get("editorState") instanceof String);

        // JanusGraph internals skipped
        assertNull(doc.get("T.label"));
        assertNull(doc.get("id"));
    }
}
