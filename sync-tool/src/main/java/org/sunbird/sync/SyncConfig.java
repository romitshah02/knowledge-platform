package org.sunbird.sync;

import org.sunbird.common.Platform;

import java.util.Arrays;
import java.util.List;

public class SyncConfig {

    public final int batchSize;
    public final int retryCount;
    public final List<String> nestedFields;
    public final List<String> stringOnlyFields;
    public final List<String> ignoredFields;
    public final List<String> restrictObjectTypes;
    public final String graphId;
    public final String indexName;
    public final String esConnInfo;

    public SyncConfig() {
        this.batchSize = Platform.getInteger("sync.batchSize", 200);
        this.retryCount = Platform.getInteger("sync.retryCount", 3);
        this.nestedFields = Platform.getStringList("sync.nested.fields", Arrays.asList());
        this.stringOnlyFields = Platform.getStringList("sync.string.only.fields", Arrays.asList());
        this.ignoredFields = Platform.getStringList("sync.ignored.fields", Arrays.asList("responseDeclaration", "body"));
        this.restrictObjectTypes = Platform.getStringList("sync.restrict.objectTypes", Arrays.asList());
        this.graphId = Platform.getString("graph.graphId", "domain");
        this.indexName = Platform.getString("compositesearch.index.name", "compositesearch");
        this.esConnInfo = Platform.getString("search.es_conn_info", "localhost:9200");
    }

    /** Test-friendly constructor */
    public SyncConfig(int batchSize, int retryCount, List<String> nestedFields,
                      List<String> stringOnlyFields, List<String> ignoredFields,
                      List<String> restrictObjectTypes, String graphId,
                      String indexName, String esConnInfo) {
        this.batchSize = batchSize;
        this.retryCount = retryCount;
        this.nestedFields = nestedFields;
        this.stringOnlyFields = stringOnlyFields;
        this.ignoredFields = ignoredFields;
        this.restrictObjectTypes = restrictObjectTypes;
        this.graphId = graphId;
        this.indexName = indexName;
        this.esConnInfo = esConnInfo;
    }
}
