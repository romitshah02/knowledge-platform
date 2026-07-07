package org.sunbird.sync;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transforms JanusGraph vertex properties into OpenSearch index documents.
 * Transformation rules (same as Flink graph-read path):
 *  1. stringOnlyFields: if value is List/Map, re-serialize to JSON string.
 *  2. All other String values starting with { or [: deserialize to Map/List.
 *  3. Everything else: pass through as-is.
 *  4. Empty lists become null (removed from document).
 *  5. External properties (from object definition) are skipped.
 *  6. Ignored fields are removed.
 *  7. System fields renamed and added.
 */
public class SyncTransformer {

    private static final Logger logger = LoggerFactory.getLogger(SyncTransformer.class);
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final int MAX_FIELD_LENGTH = 32000;

    private final SyncConfig config;

    public SyncTransformer(SyncConfig config) {
        this.config = config;
    }

    /**
     * Transform JanusGraph vertex properties to OpenSearch document.
     * Returns null if objectType should be skipped.
     */
    public Map<String, Object> transform(Map<String, Object> vertexProps) {
        String objectType = (String) vertexProps.get("IL_FUNC_OBJECT_TYPE");
        if (objectType != null && config.restrictObjectTypes.contains(objectType)) {
            return null;
        }

        Map<String, Object> document = new HashMap<>();

        for (Map.Entry<String, Object> entry : vertexProps.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            // Skip ignored fields
            if (config.ignoredFields.contains(key)) {
                continue;
            }

            // Skip JanusGraph internal fields
            if (key.startsWith("T.") || key.equals("id")) {
                continue;
            }

            // --- Phase 1: buildCompositeIndexerFromGraph behaviour ---
            // Transform the raw JanusGraph value to the {nv} value
            value = transformRawValue(key, value);

            // --- Phase 2: getIndexDocument behaviour ---
            // Empty lists become null → remove from document
            if (value instanceof List && ((List<?>) value).isEmpty()) {
                continue;
            }

            if (value != null) {
                document.put(key, value);
            }
        }

        // Rename system fields
        renameSystemFields(document);

        // Add system fields
        addSystemFields(document, vertexProps);

        // Truncate long strings
        truncateStrings(document);

        return document;
    }

    /**
     *  - stringOnlyFields + value is List/Map → serialize to JSON string
     *  - non-stringOnlyField + value is String starting with { or [ → deserialize to object
     *  - everything else → pass through
     */
    private Object transformRawValue(String key, Object value) {
        if (value == null) {
            return null;
        }

        // stringOnlyFields: re-serialize List/Map back to JSON string
        if (config.stringOnlyFields.contains(key)) {
            if (value instanceof List || value instanceof Map) {
                try {
                    return mapper.writeValueAsString(value);
                } catch (Exception e) {
                    logger.warn("Failed to serialize stringOnlyField '{}', using toString", key);
                    return value.toString();
                }
            }
            return value;
        }

        // All other fields: deserialize any JSON string starting with { or [
        if (value instanceof String) {
            String strVal = (String) value;
            if (strVal.startsWith("{") || strVal.startsWith("[")) {
                try {
                    return mapper.readValue(strVal, new TypeReference<Object>() {});
                } catch (Exception e) {
                    // Invalid JSON — keep as string (same as Flink catch block)
                    return value;
                }
            }
        }

        return value;
    }

    private void renameSystemFields(Map<String, Object> document) {
        rename(document, "IL_UNIQUE_ID", "identifier");
        rename(document, "IL_FUNC_OBJECT_TYPE", "objectType");
        rename(document, "IL_SYS_NODE_TYPE", "nodeType");
    }

    private void rename(Map<String, Object> document, String oldKey, String newKey) {
        if (document.containsKey(oldKey)) {
            document.put(newKey, document.remove(oldKey));
        }
    }

    private void addSystemFields(Map<String, Object> document, Map<String, Object> vertexProps) {
        document.put("graph_id", config.graphId);

        Object vertexId = vertexProps.get("id");
        if (vertexId instanceof Number) {
            document.put("node_id", ((Number) vertexId).longValue());
        }

        // Ensure identifier exists
        if (!document.containsKey("identifier")) {
            String id = (String) vertexProps.get("IL_UNIQUE_ID");
            if (id != null) {
                document.put("identifier", id);
            }
        }
    }

    private void truncateStrings(Map<String, Object> document) {
        for (Map.Entry<String, Object> entry : document.entrySet()) {
            if (entry.getValue() instanceof String) {
                String val = (String) entry.getValue();
                if (val.length() > MAX_FIELD_LENGTH) {
                    document.put(entry.getKey(), val.substring(0, MAX_FIELD_LENGTH));
                }
            }
        }
    }
}
