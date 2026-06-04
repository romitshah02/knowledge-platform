# Commit Range Analysis: f3ab89d to 60e28be (inclusive)

## Overview
This commit range (9 commits spanning May 24-25, 2026) implements end-to-end semantic search with hybrid mode via RRF, pluggable embeddings with int8 quantization, and a new content enrichment API endpoint. Major changes across search-api (query strategy pattern, embedding clients, quantization, hybrid executor) and content-api (enrich endpoint).

---

## Commit Breakdown

### 1. **f3ab89d0** - `feat: semantic search Phase 1 + 2 — strategy pattern, pluggable embedding, int8 quantization`
**Date:** Sun May 24 00:40:38 2026 | **Size:** ~2100 lines (largest commit)

**Purpose:** Foundation for semantic search. Introduces strategy pattern for query building, pluggable embedding clients (OpenAI/E5), int8 quantization, embedding cache, and comprehensive documentation.

**Key Components:**

**Strategy Pattern** (`search-core/src/main/java/org/sunbird/search/strategy/`)
- QueryStrategy interface + TextQueryStrategy (wraps existing logic) + QueryStrategyFactory
- Enables clean mode dispatch without modifying SearchProcessor internals

**Embedding Stack** (`search-core/src/main/java/org/sunbird/search/embedding/`)
- EmbeddingClient interface (getName, getVersion, getDimensions, embed, embedBatch, close)
- OpenAIEmbeddingClient: Supports OpenAI + Azure modes, Bearer token vs api-key headers
- E5EmbeddingClient: HuggingFace TEI client with host validation (SSRF protection)
- EmbeddingClientFactory: Lazy singleton pattern per service
- EmbeddingClientConfig: POJO carrying service params
- EmbeddingCache: LRU+TTL keyed on sha256(service:model:text), 5-minute default TTL

**Quantization Stack** (`search-core/src/main/java/org/sunbird/search/quantization/`)
- QuantizationStrategy interface: byte[] quantize(float[] vector)
- Int8QuantizationStrategy: Two paths — normalized (L2≈1 within tol 0.01) uses global scale byte=round(v×127); unnormalized uses per-vector min-max into [-128,127]
- QuantizationStrategyFactory: Returns int8 implementation

**Data Model**
- SearchDTO: Added searchMode="text" (default), semanticParams (Map)
- SearchBaseActor.getSearchDTO(): Parses request.search_mode and request.semantic block
- SearchProcessor: Routes via QueryStrategyFactory instead of inline query building
- Exposed buildTextQuery() public method for strategy implementations

**Documentation** (`search-api/docs/semantic-search/`)
- API_SPEC.md: Request/response contracts, examples, error codes for text/semantic/hybrid
- DESIGN.md: Architecture decisions, embedding contract alignment, vector-space invariants, risks
- IMPLEMENTATION_PLAN.md: Five-phase rollout with acceptance criteria
- FLOWCHART.md: Mermaid diagrams (request flow, degradation, circuit breaker, dependencies)

**Configuration** (`application.conf`)
```hocon
semantic_search {
  enabled = false  # feature flag
  embedding_service = "openai"  # or "e5"
  openai { api_key, model, dimensions=1536, timeout=5, azure_endpoint, azure_deployment, azure_api_version }
  e5 { host, port=80, dimensions=768, timeout=5 }
  quantization_strategy = "int8"
  embedding_cache { enabled=true, size=1024, ttl_seconds=300 }
  circuit_breaker { failure_threshold=10, window_seconds=30, open_seconds=60 }
}
```

**Why:** Baseline infrastructure for semantic mode. Mirrored embedding job's architecture (not a dependency; Flink overhead too high) so query-time and index-time vectors use identical embedding and quantization algorithms.

---

### 2. **0f7066ff** - `feat: hybrid search via RRF over parallel text + semantic queries`
**Date:** Sun May 24 00:49:40 2026

**Purpose:** Implement core hybrid search capability that runs text and semantic searches in parallel and fuses their results using Reciprocal Rank Fusion algorithm.

**New Files:**

**RrfFusion.java** — Pure function implementing Reciprocal Rank Fusion
- Score formula: score(d) = Σ over input lists of 1 / (k + rank_i(d))
- Default k=60 per Cormack et al. recommendation
- Generic type support; identity via caller-provided key extractor
- FusedHit inner class: id, payload (original doc), score, ranks[per_list]

**HybridSearchExecutor.java** — Orchestrates parallel execution
- Runs text and semantic searches in parallel via Pekko Futures
- Clones SearchDTO, flips searchMode field for each leg
- Applies RRF fusion algorithm to merge ranked lists
- Handles pagination on fused results (offset/limit after fusion)
- Surfaces score_components: text_rank, semantic_rank per result
- Borrows text leg's facets (broader population representation)
- Deferred: response-level score caching, parallel-only latency budgets

**HybridQueryStrategy.java** — Marker strategy class
- Dispatching happens at SearchProcessor level before QueryStrategy
- Exists only to validate mode string via QueryStrategyFactory
- Throws UnsupportedOperationException if build() called directly

**QueryStrategyFactory.java**
- Added registration of HybridQueryStrategy

**SearchProcessor.java**
- Added early-exit check for hybrid mode before single-query path
- Routes hybrid requests to HybridSearchExecutor.execute()
- Ensures re-entrancy so executor can call processSearch back with mode=text/semantic

**SemanticQueryStrategy.java**
- Extended isFullTextLeg() to detect match_phrase, simple_query_string (not just multi_match)
- Improved documentation on filter composition: must→filter (kNN owns scoring), should→dropped

**Why:** Hybrid combines best of both worlds — keyword for precision, semantic for recall — by running both in parallel and fusing via RRF, avoiding expensive rank_features API variance across OpenSearch versions.

---

### 3. **4328820e** - `fix: semantic and hybrid edge cases`
**Date:** Sun May 24 07:07:39 2026

**Purpose:** Fix three critical race conditions and fallback bugs introduced by hybrid implementation.

**HybridSearchExecutor.java**
- **Bug:** shared processor.relevanceSort flag races in parallel execution, flipping sort order non-deterministically
- **Fix:** Create fresh SearchProcessor() instances for text and semantic legs
- Ensure each leg has independent mutable state

**HybridQueryStrategy.java**
- **Bug:** processCount, getCollectionsResult, external processSearchQuery callers crash when given hybrid DTO without going through HybridSearchExecutor
- **Fix:** Fall back to text query instead of throwing UnsupportedOperationException
- Updated JavaDoc: leaf-level fallback needed for non-result paths (count, exists, collection children)

**SemanticQueryStrategy.java**
- **Bug:** prepareFilteredSearchQuery returns FunctionScoreQueryBuilder (not BoolQueryBuilder) when fuzzySearch=true, silently dropping all filters and leaving kNN unconstrained
- **Fix:** Temporarily disable fuzzy while building inherited filters, restore after
- Save/restore pattern prevents mutation of DTO observed by callers

**Why:** Edge cases broke count queries, collection expansion, and filter application. Fixes ensure hybrid degrades gracefully.

---

### 4. **cca48b0a** - `docs: add OpenAPI 3.0 spec for /v3/search with semantic and hybrid modes`
**Date:** Sun May 24 21:29:06 2026 | **Size:** 503 lines

**Purpose:** Document complete API contract for text, semantic, and hybrid search.

**openapi.yaml** — OpenAPI 3.0 specification for POST /v3/search

**Request Schema (SearchEnvelope → SearchRequest)**
- query: Free text (required for semantic/hybrid, optional for text)
- search_mode: enum [text, semantic, hybrid] (default text)
- semantic: SemanticParams object with k, min_score, schema_versions, rrf_k, vector_field
- Standard fields: filters, fields, facets, sort_by, limit, offset, fuzzy, mode, softConstraints, aggregations

**Response Schema**
- New in params: search_mode (echo), degraded (fallback flag), degraded_reason, embedding_ms, search_ms, fusion_ms (hybrid only)
- Per-result score_components (hybrid only): text_rank, semantic_rank, text_score, semantic_score
- Timing: embedding_ms=0 on cache hit

**Examples**
- 7 request examples: text_basic, filters_only, fuzzy, exists, soft_constraints, semantic, hybrid
- 4 response examples: text, semantic, hybrid, degraded (with reasons: embedding_unavailable, semantic_disabled, circuit_open)

**Error Codes Documented**
- ERR_SEMANTIC_QUERY_REQUIRED, ERR_SEMANTIC_DISABLED, ERR_INVALID_SEARCH_MODE, ERR_SEMANTIC_K_TOO_HIGH

**Why:** Clear API contract for consumers on available modes, parameters, response structure, degradation behavior, and errors.

---

### 5. **927c672f** - `fix: add missing space in openapi.yaml description key`
**Date:** Sun May 24 22:16:04 2026

**Purpose:** YAML formatting fix.

**Change:** Line 456 in openapi.yaml
- Before: `description:{ type: string, nullable: true }`
- After: `description: { type: string, nullable: true }`
- Added missing space after colon for YAML readability consistency

**Why:** Minor but improves documentation formatting.

---

### 6. **e1581807** - `fix: skip full-text property when inheriting filters in SemanticQueryStrategy`
**Date:** Mon May 25 00:28:26 2026

**Purpose:** Fix semantic search filter inheritance that was incorrectly demoting all-fields search to hard filter, excluding all results.

**Root Cause Analysis:**
- getAllFieldsPropertyQuery() wraps multi_match inside BoolQueryBuilder
- Previous getName()-based detection only checked outer builder ("bool"), missed wrapped "multi_match"
- Wrapped full-text leg then slipped into filter clause
- Filter clause's implicit minimum_should_match=1 turned every query term into hard requirement
- Result: excluded all matching documents

**Solution — Changed Approach:**
- Instead of building full text query and trying to strip legs, rebuild text query against modified DTO
- Strip propertyName='*' entries from properties list before processor.buildTextQuery()
- This ensures inherited bool contains only property filters, not full-text
- Restore original properties after in finally block

**Code Changes:**
- Removed isFullTextLeg() helper entirely
- For each property: filter out where propertyName='*'
- Inherited must clauses: filter all (full-text already excluded)
- Inherited should clauses: filter them to preserve constraint behavior

**Why:** Ensures semantic mode properly filters results while letting kNN algorithm own relevance scoring.

---

### 7. **9946039c** - `fix: align semantic-mode response with text path`
**Date:** Mon May 25 10:54:53 2026

**Purpose:** Fix semantic response format to always show cosine scores and preserve kNN ranking.

**Issue #1 — Score Visibility (SearchProcessor ~line 85):**
- Previous: Only return scores when fuzzySearch=true for all modes
- Fixed: Return scores for semantic mode OR fuzzySearch=true
- Ensures callers see cosine similarity scores for kNN results
- Text mode unchanged: scores only when fuzzy=true (backward compatible)

**Issue #2 — Sort Handling (SearchProcessor ~line 257):**
- Previous: Default name/lastUpdatedOn sort overrides kNN relevance ordering
- Fixed: if search_mode=semantic, set relevanceSort=true
- Skips default sort, preserves explicit sort_by from request if provided
- Allows kNN relevance order to remain intact

**Code:**
```java
final boolean isSemantic = SearchConstants.SEARCH_MODE_SEMANTIC.equals(searchDTO.getSearchMode());
if (searchDTO.isFuzzySearch() || isSemantic) {
    List<Map> results = ElasticSearchUtil.getDocumentsFromSearchResultWithScore(searchResult);
    resp.put("results", results);
}
if (SearchConstants.SEARCH_MODE_SEMANTIC.equals(searchDTO.getSearchMode()))
    relevanceSort = true;
```

**Why:** Makes semantic response consistent with expectations: scores visible and ranking preserved by relevance.

---

### 8. **c2a080cc** - `feat: apply minScore to search queries when semantic mode is enabled`
**Date:** Mon May 25 11:51:42 2026

**Purpose:** Implement semantic.min_score filtering to drop low-confidence matches.

**Implementation** (SearchProcessor ~line 264-270):
- Checks if search_mode is semantic AND semanticParams exists
- Extracts min_score from semanticParams map
- Validates type (must be Number)
- Applies searchSourceBuilder.minScore() only if min_score > 0.0
- Filters out chunks below cosine similarity threshold

**Code:**
```java
if (SearchConstants.SEARCH_MODE_SEMANTIC.equals(searchDTO.getSearchMode())
        && searchDTO.getSemanticParams() != null) {
    Object msObj = searchDTO.getSemanticParams().get("min_score");
    if (msObj instanceof Number) {
        float ms = ((Number) msObj).floatValue();
        if (ms > 0f) searchSourceBuilder.minScore(ms);
    }
}
```

**Why:** Allows callers to filter semantic results by confidence/relevance threshold, reducing noise from low-confidence matches. Particularly useful in early phases when vector quality may vary.

---

### 9. **60e28be7** - `feat: add enrich API endpoint for triggering enriched metadata emission`
**Date:** Mon May 25 15:53:25 2026

**Purpose:** Create new POST /content/v3/enrich endpoint to asynchronously trigger metadata enrichment without modifying publish pipelines.

**New File:**

**EnrichManager.scala** (89 lines)
- **triggerEnrich()** method:
  - Accepts Request with "identifiers" list
  - Validates identifiers non-empty, throws ERR_INVALID_REQUEST otherwise
  - Reads each content node via DataNode.read() to validate existence
  - Derives objectType from mimeType mapping:
    - "application/vnd.ekstep.content-collection" → Collection
    - "application/vnd.sunbird.question" → Question
    - "application/vnd.sunbird.questionset" → QuestionSet
    - default → Content
  - Emits BE_JOB_REQUEST events to Kafka (topic: publish.job.request) for async processing
  - Returns count of successfully validated identifiers
- Kafka event structure:
  ```json
  {
    "eid": "BE_JOB_REQUEST",
    "ets": timestamp,
    "mid": unique message ID (LP.${ets}.${uuid}),
    "actor": {id: "content-enrich-api", type: "System"},
    "context": {pdata: {ver: "1.0", id: "org.sunbird.platform"}},
    "object": {id, ver: "1.0"},
    "edata": {
      "action": "enrich",
      "metadata": {identifier, objectType, mimeType}
    }
  }
  ```

**Modified Files:**

**ContentActor.scala**
- Added case for "triggerEnrich" operation
- Routes to EnrichManager.triggerEnrich()

**ContentController.scala** (v3)
- New triggerEnrich() action method
- Extracts request headers and body
- Calls getRequest() with operation="triggerEnrich"
- Sets context and routes to ContentActor via getResult()

**ApiId.scala**
- Added constant: `val TRIGGER_ENRICH = "api.content.enrich"`

**Routes** (content-service and knowlg-service)
- Added: `POST /content/v3/enrich content.controllers.v3.ContentController.triggerEnrich()`

**API Contract:**
- Endpoint: POST /content/v3/enrich
- Request: `{"identifiers": ["do_123", "do_456"]}`
- Success (200): `{"count": 2, "identifiers": ["do_123", "do_456"]}`
- Error (400):
  - ERR_INVALID_REQUEST: malformed identifiers
  - ERR_CONTENT_NOT_FOUND: one or more identifiers don't exist

**Why:** Enables on-demand enrichment of content metadata without modifying publish pipelines. Useful for backfilling enriched metadata after schema changes or when index needs refresh.

---

## Summary by Component

### Search API (8 commits)
- **Core Architecture:** Strategy pattern (Phase 1) with pluggable embedding, quantization, circuit breaker
- **RRF Fusion:** New pure-function implementation for combining ranked lists
- **Hybrid Executor:** Parallel text + semantic search with intelligent pagination and fresh processor instances
- **Semantic Enhancements:** 
  - Proper filter inheritance (all-fields fix)
  - Score visibility (kNN confidence always shown)
  - Min-score filtering (quality gating)
  - Sort order preservation (relevance not overridden)
- **Documentation:** Complete OpenAPI 3.0 spec + design docs + implementation plan + flowcharts

### Content API (1 commit)
- **Enrich Endpoint:** New asynchronous metadata enrichment trigger via Kafka
- **Integration:** ContentActor, ContentController, route updates
- **Validation:** DataNode.read() ensures content exists before emission
- **ObjectType Mapping:** Derives from mimeType to avoid coupling

---

## Key Design Decisions

1. **Mirrored Architecture:** EmbeddingClient/QuantizationStrategy mirror embedding job (not a dependency; Flink overhead prohibitive)

2. **Strategy Pattern:** QueryStrategy interface enables clean mode dispatch without SearchProcessor refactoring

3. **Parallel Execution:** HybridSearchExecutor runs text+semantic in parallel to minimize latency overhead

4. **RRF Weighting:** Default k=60 balances influence across ranked lists; configurable via semantic.rrf_k

5. **Fallback Strategy:** HybridQueryStrategy falls back to text for non-result paths (count, facets) instead of throwing

6. **Filter Inheritance:** SemanticQueryStrategy rebuilds text query with full-text properties stripped to avoid double-filtering

7. **Async Enrichment:** Kafka-based event emission for non-blocking enrichment processing

8. **ObjectType Mapping:** Derives from mimeType to avoid content.objectType dependency

9. **Vector-Space Alignment:** All embedding/quantization constants identical to embedding job; mismatch breaks recall

---

## Testing Considerations

- Verify RRF score formula with known test vectors
- Test hybrid with asymmetric text vs semantic result sets
- Validate min_score filtering at various thresholds (0.0 to 1.0)
- Test enrich endpoint with missing/invalid identifiers
- Verify Kafka events for audit trail
- Load test parallel search executor for thread safety and state isolation
- Test circuit breaker: 10 consecutive failures within 30s opens; semantic requests degrade to text
- Embedding cache: verify TTL eviction, hash collisions across services/models
- E5 host validation: reject public hostnames, allow RFC1918 + .svc.cluster.local
- Int8 quantization: normalized vs unnormalized vector branches, L2 norm tolerance edge cases

