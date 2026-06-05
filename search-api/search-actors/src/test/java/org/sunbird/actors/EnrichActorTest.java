package org.sunbird.actors;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.pekko.actor.Props;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.powermock.api.mockito.PowerMockito;
import org.powermock.core.classloader.annotations.PowerMockIgnore;
import org.powermock.core.classloader.annotations.PrepareForTest;
import org.powermock.modules.junit4.PowerMockRunner;
import org.sunbird.common.dto.Request;
import org.sunbird.common.dto.Response;
import org.sunbird.common.exception.ResponseCode;
import org.sunbird.kafka.client.KafkaClient;
import org.sunbird.search.client.ElasticSearchUtil;
import org.sunbird.search.util.SearchConstants;

import java.util.*;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@RunWith(PowerMockRunner.class)
@PrepareForTest(ElasticSearchUtil.class)
@PowerMockIgnore({"javax.management.*", "javax.net.ssl.*", "javax.security.*",
        "com.sun.org.apache.xerces.*", "javax.xml.*", "org.xml.*"})
public class EnrichActorTest extends SearchBaseActorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Mock
    private KafkaClient mockKafkaClient;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);
        PowerMockito.mockStatic(ElasticSearchUtil.class);
        EnrichActor.overrideKafkaClientForTest(mockKafkaClient);
    }

    @After
    public void teardown() {
        EnrichActor.resetKafkaClientForTest();
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private Request enrichRequest(List<String> identifiers) {
        Request request = new Request();
        request.setOperation("triggerEnrich");
        request.setContext(new HashMap<>());
        request.put("identifiers", identifiers);
        return request;
    }

    private Response getEnrichResponse(Request request) {
        return getResponse(request, Props.create(EnrichActor.class));
    }

    private String doc(String id, String objectType, String mimeType) throws Exception {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("identifier", id);
        m.put("objectType", objectType);
        m.put("mimeType", mimeType);
        return MAPPER.writeValueAsString(m);
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    public void testValidRequest_success() throws Exception {
        PowerMockito.when(ElasticSearchUtil.getMultiDocumentAsStringByIdList(
                eq(SearchConstants.COMPOSITE_SEARCH_INDEX), anyList()))
                .thenReturn(Collections.singletonList(doc("do_123", "Content", "application/pdf")));

        Response response = getEnrichResponse(enrichRequest(Collections.singletonList("do_123")));

        Assert.assertEquals(ResponseCode.OK, response.getResponseCode());
        Assert.assertEquals(1, response.getResult().get("count"));
        List<String> succeeded = (List<String>) response.getResult().get("identifiers");
        Assert.assertTrue(succeeded.contains("do_123"));
        List<String> failed = (List<String>) response.getResult().get("failed");
        Assert.assertTrue(failed.isEmpty());
        verify(mockKafkaClient, times(1)).send(anyString(), anyString());
    }

    @Test
    public void testKafkaEventShape() throws Exception {
        PowerMockito.when(ElasticSearchUtil.getMultiDocumentAsStringByIdList(
                eq(SearchConstants.COMPOSITE_SEARCH_INDEX), anyList()))
                .thenReturn(Collections.singletonList(doc("do_123", "Content", "application/pdf")));

        getEnrichResponse(enrichRequest(Collections.singletonList("do_123")));

        verify(mockKafkaClient).send(argThat(eventJson -> {
            try {
                Map<String, Object> event = MAPPER.readValue(eventJson, Map.class);
                Assert.assertEquals("BE_JOB_REQUEST", event.get("eid"));
                Map<String, Object> edata = (Map<String, Object>) event.get("edata");
                Assert.assertEquals("enrich", edata.get("action"));
                Map<String, Object> metadata = (Map<String, Object>) edata.get("metadata");
                Assert.assertEquals("do_123", metadata.get("identifier"));
                Assert.assertEquals("Content", metadata.get("objectType"));
                Assert.assertEquals("application/pdf", metadata.get("mimeType"));
                return true;
            } catch (Exception e) {
                return false;
            }
        }), eq("test.publish.job.request"));
    }

    @Test
    public void testObjectTypeNormalisation_contentImage() throws Exception {
        PowerMockito.when(ElasticSearchUtil.getMultiDocumentAsStringByIdList(
                eq(SearchConstants.COMPOSITE_SEARCH_INDEX), anyList()))
                .thenReturn(Collections.singletonList(doc("do_123", "ContentImage", "application/pdf")));

        getEnrichResponse(enrichRequest(Collections.singletonList("do_123")));

        verify(mockKafkaClient).send(argThat(eventJson -> {
            try {
                Map<String, Object> event = MAPPER.readValue(eventJson, Map.class);
                Map<String, Object> metadata = (Map<String, Object>)
                        ((Map<String, Object>) event.get("edata")).get("metadata");
                Assert.assertEquals("Content", metadata.get("objectType"));
                return true;
            } catch (Exception e) { return false; }
        }), anyString());
    }

    @Test
    public void testIdentifierNotFound_clientError() throws Exception {
        PowerMockito.when(ElasticSearchUtil.getMultiDocumentAsStringByIdList(
                eq(SearchConstants.COMPOSITE_SEARCH_INDEX), anyList()))
                .thenReturn(Collections.emptyList());

        Response response = getEnrichResponse(enrichRequest(Collections.singletonList("do_notexist")));

        Assert.assertEquals(ResponseCode.CLIENT_ERROR, response.getResponseCode());
        verify(mockKafkaClient, never()).send(anyString(), anyString());
    }

    @Test
    public void testPartialKafkaFailure() throws Exception {
        PowerMockito.when(ElasticSearchUtil.getMultiDocumentAsStringByIdList(
                eq(SearchConstants.COMPOSITE_SEARCH_INDEX), anyList()))
                .thenReturn(Arrays.asList(
                        doc("do_123", "Content", "application/pdf"),
                        doc("do_456", "Content", "application/pdf")));

        doNothing().when(mockKafkaClient).send(contains("do_123"), anyString());
        doThrow(new RuntimeException("broker unavailable"))
                .when(mockKafkaClient).send(contains("do_456"), anyString());

        Response response = getEnrichResponse(enrichRequest(Arrays.asList("do_123", "do_456")));

        Assert.assertEquals(ResponseCode.OK, response.getResponseCode());
        Assert.assertEquals(1, response.getResult().get("count"));
        List<String> succeeded = (List<String>) response.getResult().get("identifiers");
        Assert.assertTrue(succeeded.contains("do_123"));
        List<String> failed = (List<String>) response.getResult().get("failed");
        Assert.assertTrue(failed.contains("do_456"));
    }

    @Test
    public void testAllKafkaFail_serverError() throws Exception {
        PowerMockito.when(ElasticSearchUtil.getMultiDocumentAsStringByIdList(
                eq(SearchConstants.COMPOSITE_SEARCH_INDEX), anyList()))
                .thenReturn(Collections.singletonList(doc("do_123", "Content", "application/pdf")));

        doThrow(new RuntimeException("broker down")).when(mockKafkaClient).send(anyString(), anyString());

        Response response = getEnrichResponse(enrichRequest(Collections.singletonList("do_123")));

        Assert.assertEquals(ResponseCode.CLIENT_ERROR, response.getResponseCode());
    }

    @Test
    public void testEmptyIdentifiers_clientError() {
        Response response = getEnrichResponse(enrichRequest(Collections.emptyList()));
        Assert.assertEquals(ResponseCode.CLIENT_ERROR, response.getResponseCode());
    }

    @Test
    public void testTooManyIdentifiers_clientError() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 101; i++) ids.add("do_" + i);
        Response response = getEnrichResponse(enrichRequest(ids));
        Assert.assertEquals(ResponseCode.CLIENT_ERROR, response.getResponseCode());
    }
}
