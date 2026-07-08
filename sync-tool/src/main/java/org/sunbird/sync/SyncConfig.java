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
    public final String relativePathPrefix;
    public final String absolutePath;
    public final List<String> cspMetaFields;

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
        this.relativePathPrefix = Platform.getString("cloudstorage.relative_path_prefix", "CONTENT_STORAGE_BASE_PATH");
        String readBasePath = Platform.getString("cloudstorage.read_base_path", "");
        String container = Platform.getString("cloud_storage_container", "");
        this.absolutePath = readBasePath + (readBasePath.isEmpty() || container.isEmpty() ? "" : "/") + container;
        this.cspMetaFields = Platform.getStringList("cloudstorage.metadata.list", Arrays.asList());
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
        this.relativePathPrefix = "CONTENT_STORAGE_BASE_PATH";
        this.absolutePath = "";
        this.cspMetaFields = Arrays.asList();
    }
}
