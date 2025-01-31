/*
 * Copyright (c) 2023 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.test.acceptance;

import static io.airbyte.test.utils.AirbyteAcceptanceTestHarness.COLUMN_ID;
import static io.airbyte.test.utils.AirbyteAcceptanceTestHarness.waitForConnectionState;
import static io.airbyte.test.utils.AirbyteAcceptanceTestHarness.waitForSuccessfulJob;
import static io.airbyte.test.utils.AirbyteAcceptanceTestHarness.waitWhileJobHasStatus;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.invoker.generated.ApiClient;
import io.airbyte.api.client.invoker.generated.ApiException;
import io.airbyte.api.client.model.generated.AirbyteCatalog;
import io.airbyte.api.client.model.generated.AirbyteStream;
import io.airbyte.api.client.model.generated.AttemptInfoRead;
import io.airbyte.api.client.model.generated.ConnectionCreate;
import io.airbyte.api.client.model.generated.ConnectionIdRequestBody;
import io.airbyte.api.client.model.generated.ConnectionRead;
import io.airbyte.api.client.model.generated.ConnectionScheduleType;
import io.airbyte.api.client.model.generated.ConnectionState;
import io.airbyte.api.client.model.generated.ConnectionStatus;
import io.airbyte.api.client.model.generated.ConnectorBuilderProjectDetails;
import io.airbyte.api.client.model.generated.ConnectorBuilderProjectIdWithWorkspaceId;
import io.airbyte.api.client.model.generated.ConnectorBuilderProjectWithWorkspaceId;
import io.airbyte.api.client.model.generated.ConnectorBuilderPublishRequestBody;
import io.airbyte.api.client.model.generated.DeclarativeSourceManifest;
import io.airbyte.api.client.model.generated.DestinationDefinitionIdRequestBody;
import io.airbyte.api.client.model.generated.DestinationDefinitionRead;
import io.airbyte.api.client.model.generated.DestinationRead;
import io.airbyte.api.client.model.generated.DestinationSyncMode;
import io.airbyte.api.client.model.generated.JobIdRequestBody;
import io.airbyte.api.client.model.generated.JobInfoRead;
import io.airbyte.api.client.model.generated.JobRead;
import io.airbyte.api.client.model.generated.JobStatus;
import io.airbyte.api.client.model.generated.SourceCreate;
import io.airbyte.api.client.model.generated.SourceDefinitionIdRequestBody;
import io.airbyte.api.client.model.generated.SourceDefinitionRead;
import io.airbyte.api.client.model.generated.SourceRead;
import io.airbyte.api.client.model.generated.SyncMode;
import io.airbyte.commons.json.Jsons;
import io.airbyte.commons.lang.MoreBooleans;
import io.airbyte.db.Database;
import io.airbyte.test.utils.AirbyteAcceptanceTestHarness;
import io.airbyte.test.utils.SchemaTableNamePair;
import java.io.IOException;
import java.net.URISyntaxException;
import java.sql.SQLException;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junitpioneer.jupiter.RetryingTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The class test for advanced platform functionality that can be affected by the networking
 * difference between the Kube and Docker deployments i.e. distributed vs local processes. All tests
 * in this class should pass when ran on either type of deployment.
 * <p>
 * Tests use the {@link RetryingTest} annotation instead of the more common {@link Test} to allow
 * multiple tries for a test to pass. This is because these tests sometimes fail transiently, and we
 * haven't been able to fix that yet.
 * <p>
 * However, in general we should prefer using {@code @Test} instead and only resort to using
 * {@code @RetryingTest} for tests that we can't get to pass reliably. New tests should thus default
 * to using {@code @Test} if possible.
 * <p>
 * We order tests such that earlier tests test more basic behavior that is relied upon in later
 * tests. e.g. We test that we can create a destination before we test whether we can sync data to
 * it.
 */
@SuppressWarnings({"rawtypes", "ConstantConditions"})
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class AdvancedAcceptanceTests {

  private static final Logger LOGGER = LoggerFactory.getLogger(AdvancedAcceptanceTests.class);
  private static final String TYPE = "type";
  private static final String COLUMN1 = "column1";

  private static AirbyteAcceptanceTestHarness testHarness;
  private static AirbyteApiClient apiClient;
  private static UUID workspaceId;
  private static final JsonNode A_DECLARATIVE_MANIFEST;
  private static final JsonNode A_SPEC;

  static {
    try {
      A_DECLARATIVE_MANIFEST =
          new ObjectMapper().readTree("""
                                      {
                                        "version": "0.30.3",
                                        "type": "DeclarativeSource",
                                        "check": {
                                          "type": "CheckStream",
                                          "stream_names": [
                                            "records"
                                          ]
                                        },
                                        "streams": [
                                          {
                                            "type": "DeclarativeStream",
                                            "name": "records",
                                            "primary_key": [],
                                            "schema_loader": {
                                              "type": "InlineSchemaLoader",
                                                "schema": {
                                                  "type": "object",
                                                  "$schema": "http://json-schema.org/schema#",
                                                  "properties": {
                                                    "id": {
                                                    "type": "string"
                                                  }
                                                }
                                              }
                                            },
                                            "retriever": {
                                              "type": "SimpleRetriever",
                                              "requester": {
                                                "type": "HttpRequester",
                                                "url_base": "<url_base needs to be update in order to work since port is defined only in @BeforeAll>",
                                                "path": "/",
                                                "http_method": "GET",
                                                "request_parameters": {},
                                                "request_headers": {},
                                                "request_body_json": "{\\"records\\":[{\\"id\\":1},{\\"id\\":2},{\\"id\\":3}]}",
                                                "authenticator": {
                                                  "type": "NoAuth"
                                                }
                                              },
                                              "record_selector": {
                                                "type": "RecordSelector",
                                                "extractor": {
                                                  "type": "DpathExtractor",
                                                  "field_path": [
                                                    "json",
                                                    "records"
                                                  ]
                                                }
                                              },
                                              "paginator": {
                                                "type": "NoPagination"
                                              }
                                            }
                                          }
                                        ],
                                        "spec": {
                                          "connection_specification": {
                                            "$schema": "http://json-schema.org/draft-07/schema#",
                                            "type": "object",
                                            "required": [],
                                            "properties": {},
                                            "additionalProperties": true
                                          },
                                          "documentation_url": "https://example.org",
                                          "type": "Spec"
                                        }
                                      }""");
      A_SPEC = new ObjectMapper().readTree("""
                                           {
                                             "connectionSpecification": {
                                               "$schema": "http://json-schema.org/draft-07/schema#",
                                               "type": "object",
                                               "required": [],
                                               "properties": {},
                                               "additionalProperties": true
                                             },
                                             "documentationUrl": "https://example.org",
                                             "type": "Spec"
                                           }""");
    } catch (final JsonProcessingException e) {
      throw new RuntimeException(e);
    }
  }

  @SuppressWarnings("UnstableApiUsage")
  @BeforeAll
  static void init() throws URISyntaxException, IOException, InterruptedException, ApiException {
    apiClient = new AirbyteApiClient(
        new ApiClient().setScheme("http")
            .setHost("localhost")
            .setPort(8001)
            .setBasePath("/api"));
    // work in whatever default workspace is present.
    workspaceId = apiClient.getWorkspaceApi().listWorkspaces().getWorkspaces().get(0).getWorkspaceId();
    LOGGER.info("workspaceId = " + workspaceId);

    // log which connectors are being used.
    final SourceDefinitionRead sourceDef = apiClient.getSourceDefinitionApi()
        .getSourceDefinition(new SourceDefinitionIdRequestBody()
            .sourceDefinitionId(UUID.fromString("decd338e-5647-4c0b-adf4-da0e75f5a750")));
    final DestinationDefinitionRead destinationDef = apiClient.getDestinationDefinitionApi()
        .getDestinationDefinition(new DestinationDefinitionIdRequestBody()
            .destinationDefinitionId(UUID.fromString("25c5221d-dce2-4163-ade9-739ef790f503")));
    LOGGER.info("pg source definition: {}", sourceDef.getDockerImageTag());
    LOGGER.info("pg destination definition: {}", destinationDef.getDockerImageTag());

    testHarness = new AirbyteAcceptanceTestHarness(apiClient, workspaceId);
  }

  @AfterAll
  static void end() {
    testHarness.stopDbAndContainers();
  }

  @BeforeEach
  void setup() throws URISyntaxException, IOException, SQLException {
    testHarness.setup();
  }

  @AfterEach
  void tearDown() {
    testHarness.cleanup();
  }

  @RetryingTest(3)
  @Order(1)
  void testManualSync() throws Exception {
    final String connectionName = "test-connection";
    final UUID sourceId = testHarness.createPostgresSource().getSourceId();
    final UUID destinationId = testHarness.createPostgresDestination().getDestinationId();
    final UUID operationId = testHarness.createOperation().getOperationId();
    final AirbyteCatalog catalog = testHarness.discoverSourceSchema(sourceId);
    final SyncMode syncMode = SyncMode.FULL_REFRESH;
    final DestinationSyncMode destinationSyncMode = DestinationSyncMode.OVERWRITE;
    catalog.getStreams().forEach(s -> s.getConfig().syncMode(syncMode).destinationSyncMode(destinationSyncMode));
    final UUID connectionId =
        testHarness.createConnection(connectionName, sourceId, destinationId, List.of(operationId), catalog, ConnectionScheduleType.MANUAL, null)
            .getConnectionId();
    final JobInfoRead connectionSyncRead = apiClient.getConnectionApi().syncConnection(new ConnectionIdRequestBody().connectionId(connectionId));
    waitForSuccessfulJob(apiClient.getJobsApi(), connectionSyncRead.getJob());
    testHarness.assertSourceAndDestinationDbInSync(false);
  }

  @RetryingTest(3)
  @Order(2)
  void testCheckpointing() throws Exception {
    final SourceDefinitionRead sourceDefinition = testHarness.createE2eSourceDefinition(workspaceId);
    final DestinationDefinitionRead destinationDefinition = testHarness.createE2eDestinationDefinition(workspaceId);

    final SourceRead source = testHarness.createSource(
        "E2E Test Source -" + UUID.randomUUID(),
        workspaceId,
        sourceDefinition.getSourceDefinitionId(),
        Jsons.jsonNode(ImmutableMap.builder()
            .put(TYPE, "EXCEPTION_AFTER_N")
            .put("throw_after_n_records", 100)
            .build()));

    final DestinationRead destination = testHarness.createDestination(
        "E2E Test Destination -" + UUID.randomUUID(),
        workspaceId,
        destinationDefinition.getDestinationDefinitionId(),
        Jsons.jsonNode(ImmutableMap.of(TYPE, "SILENT")));

    final String connectionName = "test-connection";
    final UUID sourceId = source.getSourceId();
    final UUID destinationId = destination.getDestinationId();
    final AirbyteCatalog catalog = testHarness.discoverSourceSchema(sourceId);
    final AirbyteStream stream = catalog.getStreams().get(0).getStream();

    assertEquals(
        Lists.newArrayList(SyncMode.FULL_REFRESH, SyncMode.INCREMENTAL),
        stream.getSupportedSyncModes());
    assertTrue(MoreBooleans.isTruthy(stream.getSourceDefinedCursor()));

    final SyncMode syncMode = SyncMode.INCREMENTAL;
    final DestinationSyncMode destinationSyncMode = DestinationSyncMode.APPEND;
    catalog.getStreams().forEach(s -> s.getConfig()
        .syncMode(syncMode)
        .cursorField(List.of(COLUMN_ID))
        .destinationSyncMode(destinationSyncMode));
    final UUID connectionId =
        testHarness.createConnection(connectionName, sourceId, destinationId, Collections.emptyList(), catalog, ConnectionScheduleType.MANUAL, null)
            .getConnectionId();
    final JobInfoRead connectionSyncRead1 = apiClient.getConnectionApi()
        .syncConnection(new ConnectionIdRequestBody().connectionId(connectionId));

    // wait to get out of pending.
    final JobRead runningJob = waitWhileJobHasStatus(apiClient.getJobsApi(), connectionSyncRead1.getJob(), Sets.newHashSet(JobStatus.PENDING));
    // wait to get out of running.
    waitWhileJobHasStatus(apiClient.getJobsApi(), runningJob, Sets.newHashSet(JobStatus.RUNNING));
    // now cancel it so that we freeze state!
    try {
      apiClient.getJobsApi().cancelJob(new JobIdRequestBody().id(connectionSyncRead1.getJob().getId()));
    } catch (final Exception e) {
      LOGGER.error("error:", e);
    }

    final ConnectionState connectionState = waitForConnectionState(apiClient, connectionId);

    // the source is set to emit a state message every 5th message. because of the multi threaded
    // nature, we can't guarantee exactly what checkpoint will be registered. what we can do is send
    // enough messages to make sure that we checkpoint at least once.
    assertNotNull(connectionState.getState());
    assertTrue(connectionState.getState().get(COLUMN1).isInt());
    LOGGER.info("state value: {}", connectionState.getState().get(COLUMN1).asInt());
    assertTrue(connectionState.getState().get(COLUMN1).asInt() > 0);
    assertEquals(0, connectionState.getState().get(COLUMN1).asInt() % 5);
  }

  // verify that when the worker uses backpressure from pipes that no records are lost.
  @RetryingTest(3)
  @Order(4)
  void testBackpressure() throws Exception {
    final SourceDefinitionRead sourceDefinition = testHarness.createE2eSourceDefinition(workspaceId);
    final DestinationDefinitionRead destinationDefinition = testHarness.createE2eDestinationDefinition(workspaceId);

    final SourceRead source = testHarness.createSource(
        "E2E Test Source -" + UUID.randomUUID(),
        workspaceId,
        sourceDefinition.getSourceDefinitionId(),
        Jsons.jsonNode(ImmutableMap.builder()
            .put(TYPE, "INFINITE_FEED")
            .put("max_records", 5000)
            .build()));

    final DestinationRead destination = testHarness.createDestination(
        "E2E Test Destination -" + UUID.randomUUID(),
        workspaceId,
        destinationDefinition.getDestinationDefinitionId(),
        Jsons.jsonNode(ImmutableMap.builder()
            .put(TYPE, "THROTTLED")
            .put("millis_per_record", 1)
            .build()));

    final String connectionName = "test-connection";
    final UUID sourceId = source.getSourceId();
    final UUID destinationId = destination.getDestinationId();
    final AirbyteCatalog catalog = testHarness.discoverSourceSchema(sourceId);

    final UUID connectionId =
        testHarness.createConnection(connectionName, sourceId, destinationId, Collections.emptyList(), catalog, ConnectionScheduleType.MANUAL, null)
            .getConnectionId();
    final JobInfoRead connectionSyncRead1 = apiClient.getConnectionApi()
        .syncConnection(new ConnectionIdRequestBody().connectionId(connectionId));

    // wait to get out of pending.
    final JobRead runningJob = waitWhileJobHasStatus(apiClient.getJobsApi(), connectionSyncRead1.getJob(), Sets.newHashSet(JobStatus.PENDING));
    // wait to get out of running.
    waitWhileJobHasStatus(apiClient.getJobsApi(), runningJob, Sets.newHashSet(JobStatus.RUNNING));

    final JobInfoRead jobInfo = apiClient.getJobsApi().getJobInfo(new JobIdRequestBody().id(runningJob.getId()));
    final AttemptInfoRead attemptInfoRead = jobInfo.getAttempts().get(jobInfo.getAttempts().size() - 1);
    assertNotNull(attemptInfoRead);

    int expectedMessageNumber = 0;
    final int max = 10_000;
    for (final String logLine : attemptInfoRead.getLogs().getLogLines()) {
      if (expectedMessageNumber > max) {
        break;
      }

      if (logLine.contains("received record: ") && logLine.contains("\"type\": \"RECORD\"")) {
        assertTrue(
            logLine.contains(String.format("\"column1\": \"%s\"", expectedMessageNumber)),
            String.format("Expected %s but got: %s", expectedMessageNumber, logLine));
        expectedMessageNumber++;
      }
    }
  }

  @Test
  void testConnectorBuilderPublish() throws Exception {
    final UUID sourceDefinitionId = publishSourceDefinitionThroughConnectorBuilder();
    final SourceRead sourceRead = createSource(sourceDefinitionId);
    try {
      final UUID destinationId = testHarness.createPostgresDestination().getDestinationId();
      final ConnectionRead connectionRead = createConnection(sourceRead.getSourceId(), destinationId);
      runConnection(connectionRead.getConnectionId());

      final Database destination = testHarness.getDestinationDatabase();
      final Set<SchemaTableNamePair> destinationTables = testHarness.listAllTables(destination);
      assertEquals(1, destinationTables.size());
      assertEquals(3, testHarness.retrieveDestinationRecords(destination, destinationTables.iterator().next().getFullyQualifiedTableName()).size());
    } finally {
      // clean up
      apiClient.getSourceDefinitionApi().deleteSourceDefinition(new SourceDefinitionIdRequestBody().sourceDefinitionId(sourceDefinitionId));
    }
  }

  private static UUID publishSourceDefinitionThroughConnectorBuilder() throws ApiException {
    final JsonNode manifest = A_DECLARATIVE_MANIFEST.deepCopy();
    ((ObjectNode) manifest.at("/streams/0/retriever/requester")).put("url_base", testHarness.getEchoServerUrl());

    final ConnectorBuilderProjectIdWithWorkspaceId connectorBuilderProject = apiClient.getConnectorBuilderProjectApi()
        .createConnectorBuilderProject(new ConnectorBuilderProjectWithWorkspaceId()
            .workspaceId(workspaceId)
            .builderProject(new ConnectorBuilderProjectDetails().name("A custom declarative source")));
    return apiClient.getConnectorBuilderProjectApi()
        .publishConnectorBuilderProject(new ConnectorBuilderPublishRequestBody()
            .workspaceId(workspaceId)
            .builderProjectId(connectorBuilderProject.getBuilderProjectId())
            .name("A custom declarative source")
            .initialDeclarativeManifest(new DeclarativeSourceManifest()
                .manifest(manifest)
                .spec(A_SPEC)
                .description("A description")
                .version(1L)))
        .getSourceDefinitionId();
  }

  private static SourceRead createSource(final UUID sourceDefinitionId) throws ApiException, JsonProcessingException {
    return apiClient.getSourceApi().createSource(new SourceCreate()
        .sourceDefinitionId(sourceDefinitionId)
        .name("A custom declarative source")
        .workspaceId(workspaceId)
        .connectionConfiguration(new ObjectMapper().readTree("{\"__injected_declarative_manifest\": {}\n}")));
  }

  private static ConnectionRead createConnection(final UUID sourceId, final UUID destinationId) throws ApiException {
    final AirbyteCatalog syncCatalog = testHarness.discoverSourceSchemaWithoutCache(sourceId);
    return apiClient.getConnectionApi().createConnection(new ConnectionCreate()
        .name("name")
        .sourceId(sourceId)
        .destinationId(destinationId)
        .status(ConnectionStatus.ACTIVE)
        .syncCatalog(syncCatalog));
  }

  private static void runConnection(final UUID connectionId) throws ApiException, InterruptedException {
    final JobInfoRead connectionSyncRead = apiClient.getConnectionApi()
        .syncConnection(new ConnectionIdRequestBody().connectionId(connectionId));
    waitForSuccessfulJob(apiClient.getJobsApi(), connectionSyncRead.getJob());
  }

}
