package org.sunbird.common;

import org.apache.commons.lang3.StringUtils;
import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class HtmlSanitizer {

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
        if (StringUtils.isBlank(value)) return value;
        if (RICH_TEXT_FIELDS.contains(fieldName)) {
            return sanitizeRichText(value);
        }
        return sanitizeStrict(value);
    }

    public static void sanitizeMap(Map<String, Object> data) {
        if (data == null || data.isEmpty()) return;
        List<String> keys = data.keySet().stream().collect(Collectors.toList());
        for (String key : keys) {
            Object value = data.get(key);
            if (value instanceof String) {
                data.put(key, sanitizeField(key, (String) value));
            } else if (value instanceof Map) {
                sanitizeMap((Map<String, Object>) value);
            } else if (value instanceof List) {
                sanitizeList(key, (List<Object>) value);
            }
        }
    }

    private static void sanitizeList(String parentKey, List<Object> list) {
        if (list == null || list.isEmpty()) return;
        for (int i = 0; i < list.size(); i++) {
            Object item = list.get(i);
            if (item instanceof String) {
                list.set(i, sanitizeField(parentKey, (String) item));
            } else if (item instanceof Map) {
                sanitizeMap((Map<String, Object>) item);
            }
        }
    }
}
