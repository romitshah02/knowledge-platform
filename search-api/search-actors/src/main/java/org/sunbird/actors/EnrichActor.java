package org.sunbird.actors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.sunbird.common.Platform;
import org.sunbird.common.dto.Request;
import org.sunbird.common.dto.Response;
import org.sunbird.common.dto.ResponseHandler;
import org.sunbird.common.exception.ClientException;
import org.sunbird.kafka.client.KafkaClient;
import org.sunbird.search.util.SearchConstants;
import org.sunbird.telemetry.logger.TelemetryManager;
import scala.concurrent.Future;

import java.util.*;

/**
 * Handles the enrich API: resolves objectType for each identifier via the
 * composite search index (OpenSearch), then emits a {@code BE_JOB_REQUEST}
 * Kafka event with {@code edata.action=enrich} for each valid identifier.
 * The downstream knowlg-publish Flink job consumes these events via
 * {@code EnrichOnlyFunction} to produce enriched metadata.
 */
public class EnrichActor extends SearchBaseActor {

    private static volatile KafkaClient KAFKA_CLIENT;
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static KafkaClient kafkaClient() {
        if (KAFKA_CLIENT == null) {
            synchronized (EnrichActor.class) {
                if (KAFKA_CLIENT == null) KAFKA_CLIENT = new KafkaClient();
            }
        }
        return KAFKA_CLIENT;
    }

    /** Test hook — inject a mock KafkaClient. */
    static void overrideKafkaClientForTest(KafkaClient client) {
        synchronized (EnrichActor.class) { KAFKA_CLIENT = client; }
    }

    /** Test hook — reset to force lazy re-init. */
    static void resetKafkaClientForTest() {
        synchronized (EnrichActor.class) { KAFKA_CLIENT = null; }
    }

    @Override
    public void preStart() throws Exception {
        super.preStart();
        org.sunbird.search.client.ElasticSearchUtil.initialiseESClient(
                SearchConstants.COMPOSITE_SEARCH_INDEX,
                Platform.config.getString("search.es_conn_info"));
    }

    @Override
    public Future<Response> onReceive(Request request) throws Throwable {
        if ("triggerEnrich".equals(request.getOperation())) {
            return triggerEnrich(request);
        }
        return ERROR(request.getOperation());
    }

    @SuppressWarnings("unchecked")
    private Future<Response> triggerEnrich(Request request) {
        List<String> identifiers = parseIdentifiers(request);
        int maxIds = Platform.config.hasPath("enrich.max_identifiers")
                ? Platform.config.getInt("enrich.max_identifiers") : 100;
        if (identifiers.size() > maxIds)
            throw new ClientException("ERR_TOO_MANY_IDENTIFIERS",
                    "Maximum " + maxIds + " identifiers per request, got: " + identifiers.size());
        String topic = Platform.config.hasPath("kafka.publish.request.topic")
                ? Platform.config.getString("kafka.publish.request.topic")
                : "sunbirddev.publish.job.request";

        return scala.concurrent.Future.apply(new scala.runtime.AbstractFunction0<Response>() {
            @Override
            public Response apply() {
                try {
                    List<String> rawDocs = org.sunbird.search.client.ElasticSearchUtil
                            .getMultiDocumentAsStringByIdList(SearchConstants.COMPOSITE_SEARCH_INDEX, identifiers);

                    Map<String, Map<String, Object>> docMap = new LinkedHashMap<>();
                    for (String raw : rawDocs) {
                        Map<String, Object> doc = MAPPER.readValue(raw, Map.class);
                        String id = String.valueOf(doc.getOrDefault("identifier", ""));
                        if (id.endsWith(".img")) id = id.substring(0, id.length() - 4);
                        docMap.putIfAbsent(id, doc);
                    }

                    List<String> notFound = new ArrayList<>();
                    for (String id : identifiers) {
                        if (!docMap.containsKey(id)) notFound.add(id);
                    }
                    if (!notFound.isEmpty())
                        throw new ClientException("ERR_CONTENT_NOT_FOUND",
                                "Content not found for identifier(s): " + String.join(", ", notFound));

                    List<String> succeeded = new ArrayList<>();
                    List<String> failed = new ArrayList<>();
                    for (String id : identifiers) {
                        Map<String, Object> doc = docMap.get(id);
                        String objectType = normalizeObjectType(String.valueOf(doc.getOrDefault("objectType", "Content")));
                        String mimeType = String.valueOf(doc.getOrDefault("mimeType", ""));
                        try {
                            kafkaClient().send(buildEvent(id, objectType, mimeType), topic);
                            TelemetryManager.log("EnrichActor: emitted enrich event for " + id + " (" + objectType + ")");
                            succeeded.add(id);
                        } catch (Exception e) {
                            TelemetryManager.error("EnrichActor: kafka send failed for " + id + ": " + e.getMessage(), e);
                            failed.add(id);
                        }
                    }

                    if (succeeded.isEmpty())
                        throw new ClientException("ERR_KAFKA_SEND_FAILED",
                                "Failed to emit enrich events for: " + String.join(", ", failed));

                    Response response = ResponseHandler.OK();
                    response.put("count", succeeded.size());
                    response.put("identifiers", succeeded);
                    response.put("failed", failed);
                    return response;
                } catch (ClientException e) {
                    throw e;
                } catch (Exception e) {
                    throw new RuntimeException("EnrichActor: failed to fetch documents from index", e);
                }
            }
        }, org.sunbird.search.processor.SemanticEmbeddingPool.context());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private List<String> parseIdentifiers(Request request) {
        Object raw = request.getRequest().get("identifiers");
        if (!(raw instanceof List) || ((List) raw).isEmpty()) {
            throw new ClientException("ERR_INVALID_REQUEST", "identifiers must be a non-empty list");
        }
        List<String> ids = new ArrayList<>();
        for (Object o : (List) raw) {
            String s = (o == null) ? "" : o.toString().trim();
            if (!s.isEmpty()) ids.add(s);
        }
        if (ids.isEmpty()) {
            throw new ClientException("ERR_INVALID_REQUEST", "identifiers list must not be empty");
        }
        return ids;
    }

    private String normalizeObjectType(String objectType) {
        // Strip "Image" suffix — ContentImage → Content, CollectionImage → Collection, etc.
        if (objectType.endsWith("Image")) {
            return objectType.substring(0, objectType.length() - 5);
        }
        return objectType;
    }

    private String buildEvent(String identifier, String objectType, String mimeType) {
        long ets = System.currentTimeMillis();
        String mid = "LP." + ets + "." + UUID.randomUUID();

        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("identifier", identifier);
        metadata.put("objectType", objectType);
        metadata.put("mimeType", mimeType);

        Map<String, Object> edata = new LinkedHashMap<>();
        edata.put("action", "enrich");
        edata.put("metadata", metadata);

        Map<String, Object> event = new LinkedHashMap<>();
        event.put("eid", "BE_JOB_REQUEST");
        event.put("ets", ets);
        event.put("mid", mid);
        event.put("actor", Map.of("id", "content-enrich-api", "type", "System"));
        event.put("context", Map.of("pdata", Map.of("ver", "1.0", "id", "org.sunbird.platform")));
        event.put("object", Map.of("id", identifier, "ver", "1.0"));
        event.put("edata", edata);

        try {
            return MAPPER.writeValueAsString(event);
        } catch (Exception e) {
            throw new RuntimeException("Failed to serialize enrich event for " + identifier, e);
        }
    }
}
