package org.sunbird.search.strategy;

import org.opensearch.index.query.QueryBuilder;
import org.sunbird.search.dto.SearchDTO;
import org.sunbird.search.processor.SearchProcessor;

/**
 * Strategy for building the OpenSearch query for a single search request.
 *
 * Implementations are stateless and thread-safe. The factory returns shared
 * singletons. Three strategies available:
 *  - TextQueryStrategy: existing keyword search (delegates to SearchProcessor)
 *  - SemanticQueryStrategy: nested kNN on int8-quantized vectors
 *  - HybridQueryStrategy: parallel text + semantic, fused via RRF
 */
public interface QueryStrategy {

    /** Mode key — matches request.search_mode. */
    String getMode();

    /**
     * Build the OpenSearch QueryBuilder for the given DTO.
     * The processor is supplied so the strategy can call back into shared
     * helpers (filter parsing, soft constraints, implicit filters, etc.)
     * without duplicating logic across strategies.
     */
    QueryBuilder build(SearchDTO dto, SearchProcessor processor) throws Exception;
}
