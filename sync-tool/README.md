# Sync Tool

Bulk sync tool for populating OpenSearch from JanusGraph.

## When to use

- Populating an empty OpenSearch index after environment setup
- Repairing data after missed/lost CDC events
- On-demand sync for specific content IDs
- Backfilling after adding new searchable fields

## Sync Modes

| Mode | Flag | Example |
|------|------|---------|
| Specific IDs | `--identifiers` | `--identifiers do_123,do_456` |
| By objectType | `--objectType` | `--objectType Content` |
| Recent days | `--days` | `--days 5` |
| From file | `--file` | `--file /config/node_identifiers.csv` |
| Full sync | `--type full` | `--type full` |

## Build

```bash
cd knowledge-platform
mvn clean package -pl sync-tool -am -DskipTests
```

## Run locally

```bash
java -jar sync-tool/target/sync-tool-1.0-SNAPSHOT.jar --objectType Content
```

Override config via environment variables:

```bash
export graph_storage_hostname=localhost
export graph_storage_port=9042
export search_es_conn_info=localhost:9200
java -jar sync-tool/target/sync-tool-1.0-SNAPSHOT.jar --objectType Content
```

## Docker

```bash
docker build -t sync-tool sync-tool/
docker run sync-tool --objectType Content
```

## Output

```
[10:00:01] Connecting to JanusGraph...
[10:00:02] JanusGraph connected.
[10:00:02] Connecting to OpenSearch...
[10:00:02] OpenSearch connected.
[10:00:02] Mode: objectType=Content
[10:00:03] Enumerated 12,000 nodes
[10:00:04] Batch 1/60: 200 synced
[10:00:06] Batch 2/60: 200 synced
[10:00:07] Batch 3/60: 198 synced, 2 failed [do_312, do_589]
...
[10:04:30] Complete. 11,997 synced, 3 failed.
[10:04:30] Failed IDs: do_312, do_589, do_901
```

## Exit Codes

| Code | Meaning |
|------|---------|
| 0 | All nodes synced successfully |
| 1 | Partial failure — some nodes failed (see output for IDs) |
| 2 | Fatal error — sync aborted |

## Configuration

Config loaded via Typesafe Config (`application.conf`) with environment variable overrides.

| Config | Default | Description |
|--------|---------|-------------|
| `graph.storage.hostname` | localhost | JanusGraph (Cassandra) host |
| `graph.storage.port` | 9042 | JanusGraph (Cassandra) port |
| `search.es_conn_info` | localhost:9200 | OpenSearch host:port |
| `compositesearch.index.name` | compositesearch | OpenSearch index name |
| `sync.batchSize` | 200 | Nodes per batch |
| `sync.retryCount` | 3 | Retries per failed batch |
| `sync.nested.fields` | see application.conf | Fields deserialized to objects |
| `sync.string.only.fields` | see application.conf | Fields kept as JSON strings |
| `sync.ignored.fields` | responseDeclaration, body | Fields dropped from index |
| `sync.restrict.objectTypes` | EventSet, Questionnaire, ... | Object types skipped |
