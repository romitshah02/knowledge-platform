package org.sunbird.common;

import org.apache.commons.lang3.StringUtils;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.sunbird.common.Platform;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class HtmlSanitizer {

    private static final int MAX_DEPTH = Platform.getInteger("html.sanitizer.max.depth", 25);
    
    private static final PolicyFactory STRICT_POLICY = new HtmlPolicyBuilder().toFactory();

    private static final PolicyFactory RICH_TEXT_POLICY = new HtmlPolicyBuilder()
            .allowElements("p", "br", "b", "i", "u", "strong", "em", "ul", "ol", "li",
                    "h1", "h2", "h3", "h4", "h5", "h6", "span", "div", "table", "thead",
                    "tbody", "tr", "td", "th", "blockquote", "pre", "code", "sub", "sup",
                    "figure", "figcaption", "math", "mrow", "mi", "mo", "mn", "msup",
                    "msub", "mfrac", "msqrt", "mover", "munder")
            .allowAttributes("class", "style", "dir", "lang").globally()
            .allowAttributes("colspan", "rowspan").onElements("td", "th")
            .allowAttributes("mathvariant").onElements("mi")
            .allowStyling()
            .toFactory();

    private static final Set<String> RICH_TEXT_FIELDS = new HashSet<>(Arrays.asList(
            "body", "solutions", "instructions", "hints", "answer", "editorState",
            "question", "responseDeclaration", "outcomeDeclaration"
    ));

    private static final Set<String> IGNORE_FIELDS = new HashSet<>(Arrays.asList(
            "createdOn", "lastUpdatedOn", "lastStatusChangedOn", "lastPublishedOn",
            "lastSubmittedOn", "versionDate", "artifactUrl", "downloadUrl", "previewUrl",
            "streamingUrl", "appIcon", "posterImage", "toc_url"
    ));

    public static String sanitizeStrict(String input) {
        if (StringUtils.isBlank(input)) return input;
        return STRICT_POLICY.sanitize(input);
    }

    public static String sanitizeRichText(String input) {
        if (StringUtils.isBlank(input)) return input;
        return RICH_TEXT_POLICY.sanitize(input);
    }

    public static String escapeHtml(String input) {
        if (StringUtils.isBlank(input)) return input;
        return input.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&#39;");
    }

    public static String sanitizeField(String fieldName, String value) {
        return sanitizeField(fieldName, value, 0);
    }

    private static String sanitizeField(String fieldName, String value, int depth) {
        if (StringUtils.isBlank(value) || IGNORE_FIELDS.contains(fieldName) || depth > MAX_DEPTH) return value;
        if (isJson(value)) {
            return sanitizeJsonString(fieldName, value, depth + 1);
        }
        if (RICH_TEXT_FIELDS.contains(fieldName)) {
            return sanitizeRichText(value);
        }
        return sanitizeStrict(value);
    }

    private static boolean isJson(String value) {
        String trimmed = value.trim();
        return (trimmed.startsWith("{") && trimmed.endsWith("}")) || (trimmed.startsWith("[") && trimmed.endsWith("]"));
    }

    private static String sanitizeJsonString(String fieldName, String value, int depth) {
        try {
            Object json = JsonUtils.deserialize(value, Object.class);
            if (json instanceof Map) {
                sanitizeMap((Map<String, Object>) json, depth + 1);
            } else if (json instanceof List) {
                json = ensureMutableList((List<Object>) json);
                sanitizeList(fieldName, (List<Object>) json, depth + 1);
            }
            return JsonUtils.serialize(json);
        } catch (Exception e) {
            return RICH_TEXT_FIELDS.contains(fieldName) ? sanitizeRichText(value) : sanitizeStrict(value);
        }
    }

    private static List<Object> ensureMutableList(List<Object> list) {
        try {
            list.set(0, list.get(0)); // Test if mutable
            return list;
        } catch (UnsupportedOperationException e) {
            return new ArrayList<>(list);
        }
    }

    public static void sanitizeMap(Map<String, Object> data) {
        sanitizeMap(data, 0);
    }

    private static void sanitizeMap(Map<String, Object> data, int depth) {
        if (data == null || data.isEmpty() || depth > MAX_DEPTH) return;
        List<String> keys = data.keySet().stream().collect(Collectors.toList());
        for (String key : keys) {
            Object value = data.get(key);
            if (value instanceof String) {
                data.put(key, sanitizeField(key, (String) value, depth + 1));
            } else if (value instanceof Map) {
                sanitizeMap((Map<String, Object>) value, depth + 1);
            } else if (value instanceof List) {
                sanitizeList(key, (List<Object>) value, depth + 1);
            }
        }
    }

    private static void sanitizeList(String parentKey, List<Object> list, int depth) {
        if (list == null || list.isEmpty() || depth > MAX_DEPTH) return;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof String) {
                try {
                    list.set(i, sanitizeField(parentKey, (String) item, depth + 1));
                } catch (UnsupportedOperationException e) {
                    // List is immutable, skip sanitization for this item
                }
            } else if (item instanceof Map) {
                sanitizeMap((Map<String, Object>) item, depth + 1);
            } else if (item instanceof List) {
                List<Object> mutableSubList = ensureMutableList((List<Object>) item);
                sanitizeList(parentKey, mutableSubList, depth + 1);
            }
        }
    }
}
