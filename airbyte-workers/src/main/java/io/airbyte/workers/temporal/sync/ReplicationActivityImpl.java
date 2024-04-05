/*
 * Copyright (c) 2020-2024 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.workers.temporal.sync;

import static io.airbyte.metrics.lib.ApmTraceConstants.ACTIVITY_TRACE_OPERATION_NAME;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.ATTEMPT_NUMBER_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.CONNECTION_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.DESTINATION_DOCKER_IMAGE_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.JOB_ID_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.REPLICATION_BYTES_SYNCED_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.REPLICATION_RECORDS_SYNCED_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.REPLICATION_STATUS_KEY;
import static io.airbyte.metrics.lib.ApmTraceConstants.Tags.SOURCE_DOCKER_IMAGE_KEY;

import datadog.trace.api.Trace;
import io.airbyte.api.client.AirbyteApiClient;
import io.airbyte.api.client.model.generated.StreamDescriptor;
import io.airbyte.commons.functional.CheckedSupplier;
import io.airbyte.commons.temporal.HeartbeatUtils;
import io.airbyte.commons.temporal.utils.PayloadChecker;
import io.airbyte.config.Configs.WorkerEnvironment;
import io.airbyte.config.ReplicationAttemptSummary;
import io.airbyte.config.ReplicationOutput;
import io.airbyte.config.StandardSyncOutput;
import io.airbyte.config.StandardSyncSummary;
import io.airbyte.config.helpers.LogConfigs;
import io.airbyte.config.secrets.SecretsRepositoryReader;
import io.airbyte.featureflag.Connection;
import io.airbyte.featureflag.FeatureFlagClient;
import io.airbyte.featureflag.WriteReplicationOutputToObjectStorage;
import io.airbyte.metrics.lib.ApmTraceUtils;
import io.airbyte.metrics.lib.MetricAttribute;
import io.airbyte.metrics.lib.MetricClient;
import io.airbyte.metrics.lib.MetricTags;
import io.airbyte.metrics.lib.OssMetricsRegistry;
import io.airbyte.persistence.job.models.ReplicationInput;
import io.airbyte.workers.ReplicationInputHydrator;
import io.airbyte.workers.Worker;
import io.airbyte.workers.helper.BackfillHelper;
import io.airbyte.workers.models.ReplicationActivityInput;
import io.airbyte.workers.orchestrator.OrchestratorHandleFactory;
import io.airbyte.workers.payload.ActivityPayloadStorageClient;
import io.airbyte.workers.payload.ActivityPayloadURI;
import io.airbyte.workers.sync.WorkloadApiWorker;
import io.airbyte.workers.temporal.TemporalAttemptExecution;
import io.airbyte.workers.workload.JobOutputDocStore;
import io.airbyte.workers.workload.WorkloadIdGenerator;
import io.airbyte.workload.api.client.generated.WorkloadApi;
import io.micronaut.context.annotation.Value;
import io.temporal.activity.Activity;
import io.temporal.activity.ActivityExecutionContext;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Replication temporal activity impl.
 */
@Singleton
public class ReplicationActivityImpl implements ReplicationActivity {

  private static final Logger LOGGER = LoggerFactory.getLogger(ReplicationActivityImpl.class);
  private static final int MAX_TEMPORAL_MESSAGE_SIZE = 2 * 1024 * 1024;

  private final ReplicationInputHydrator replicationInputHydrator;
  private final Path workspaceRoot;
  private final WorkerEnvironment workerEnvironment;
  private final LogConfigs logConfigs;
  private final String airbyteVersion;
  private final AirbyteApiClient airbyteApiClient;
  private final JobOutputDocStore jobOutputDocStore;
  private final WorkloadApi workloadApi;
  private final WorkloadIdGenerator workloadIdGenerator;
  private final OrchestratorHandleFactory orchestratorHandleFactory;
  private final MetricClient metricClient;
  private final FeatureFlagClient featureFlagClient;
  private final PayloadChecker payloadChecker;
  private final ActivityPayloadStorageClient storageClient;

  public ReplicationActivityImpl(final SecretsRepositoryReader secretsRepositoryReader,
                                 @Named("workspaceRoot") final Path workspaceRoot,
                                 final WorkerEnvironment workerEnvironment,
                                 final LogConfigs logConfigs,
                                 @Value("${airbyte.version}") final String airbyteVersion,
                                 final AirbyteApiClient airbyteApiClient,
                                 final JobOutputDocStore jobOutputDocStore,
                                 final WorkloadApi workloadApi,
                                 final WorkloadIdGenerator workloadIdGenerator,
                                 final OrchestratorHandleFactory orchestratorHandleFactory,
                                 final MetricClient metricClient,
                                 final FeatureFlagClient featureFlagClient,
                                 final PayloadChecker payloadChecker,
                                 final ActivityPayloadStorageClient storageClient) {
    this.replicationInputHydrator = new ReplicationInputHydrator(airbyteApiClient.getConnectionApi(),
        airbyteApiClient.getJobsApi(),
        airbyteApiClient.getStateApi(),
        airbyteApiClient.getSecretPersistenceConfigApi(), secretsRepositoryReader,
        featureFlagClient);
    this.workspaceRoot = workspaceRoot;
    this.workerEnvironment = workerEnvironment;
    this.logConfigs = logConfigs;
    this.airbyteVersion = airbyteVersion;
    this.airbyteApiClient = airbyteApiClient;
    this.jobOutputDocStore = jobOutputDocStore;
    this.workloadApi = workloadApi;
    this.workloadIdGenerator = workloadIdGenerator;
    this.orchestratorHandleFactory = orchestratorHandleFactory;
    this.metricClient = metricClient;
    this.featureFlagClient = featureFlagClient;
    this.payloadChecker = payloadChecker;
    this.storageClient = storageClient;
  }

  /**
   * Performs the replication activity.
   * <p>
   * Takes a lite input (no catalog, no state, no secrets) to avoid passing those through Temporal and
   * hydrates it before launching the replication orchestrator.
   * <p>
   * TODO: this is the preferred method. Once we remove `replicate`, this can be renamed.
   *
   * @param replicationActivityInput the input to the replication activity
   * @return output from the replication activity, populated in the StandardSyncOutput
   */
  @Trace(operationName = ACTIVITY_TRACE_OPERATION_NAME)
  @Override
  public StandardSyncOutput replicateV2(final ReplicationActivityInput replicationActivityInput) {
    metricClient.count(OssMetricsRegistry.ACTIVITY_REPLICATION, 1);

    final var connectionId = replicationActivityInput.getConnectionId();
    final var jobId = replicationActivityInput.getJobRunConfig().getJobId();
    final var attemptNumber = replicationActivityInput.getJobRunConfig().getAttemptId();

    final Map<String, Object> traceAttributes =
        Map.of(
            CONNECTION_ID_KEY, connectionId,
            JOB_ID_KEY, jobId,
            ATTEMPT_NUMBER_KEY, attemptNumber,
            DESTINATION_DOCKER_IMAGE_KEY, replicationActivityInput.getDestinationLauncherConfig().getDockerImage(),
            SOURCE_DOCKER_IMAGE_KEY, replicationActivityInput.getSourceLauncherConfig().getDockerImage());
    ApmTraceUtils
        .addTagsToTrace(traceAttributes);

    final MetricAttribute[] metricAttributes = traceAttributes.entrySet().stream()
        .map(e -> new MetricAttribute(ApmTraceUtils.formatTag(e.getKey()), e.getValue().toString()))
        .collect(Collectors.toSet()).toArray(new MetricAttribute[] {});

    if (replicationActivityInput.getIsReset()) {
      metricClient.count(OssMetricsRegistry.RESET_REQUEST, 1);
    }
    final ActivityExecutionContext context = Activity.getExecutionContext();

    final AtomicReference<Runnable> cancellationCallback = new AtomicReference<>(null);

    return HeartbeatUtils.withBackgroundHeartbeat(
        cancellationCallback,
        () -> {
          final ReplicationInput hydratedReplicationInput = replicationInputHydrator.getHydratedReplicationInput(replicationActivityInput);
          LOGGER.info("connection {}, hydrated input: {}", replicationActivityInput.getConnectionId(), hydratedReplicationInput);
          final Worker<ReplicationInput, ReplicationOutput> worker;

          // TODO: remove this once migration to workloads complete
          if (useWorkloadApi(replicationActivityInput)) {
            worker = new WorkloadApiWorker(jobOutputDocStore, airbyteApiClient,
                workloadApi, workloadIdGenerator, replicationActivityInput, featureFlagClient);
          } else {
            final CheckedSupplier<Worker<ReplicationInput, ReplicationOutput>, Exception> workerFactory =
                orchestratorHandleFactory.create(hydratedReplicationInput.getSourceLauncherConfig(),
                    hydratedReplicationInput.getDestinationLauncherConfig(), hydratedReplicationInput.getJobRunConfig(), hydratedReplicationInput,
                    () -> context);
            worker = workerFactory.get();
          }
          cancellationCallback.set(worker::cancel);

          final TemporalAttemptExecution<ReplicationInput, ReplicationOutput> temporalAttempt =
              new TemporalAttemptExecution<>(
                  workspaceRoot,
                  workerEnvironment,
                  logConfigs,
                  hydratedReplicationInput.getJobRunConfig(),
                  worker,
                  hydratedReplicationInput,
                  airbyteApiClient,
                  airbyteVersion,
                  () -> context,
                  Optional.ofNullable(replicationActivityInput.getTaskQueue()));

          final ReplicationOutput attemptOutput = temporalAttempt.get();
          final StandardSyncOutput standardSyncOutput = reduceReplicationOutput(attemptOutput, metricAttributes);

          final String standardSyncOutputString = standardSyncOutput.toString();
          LOGGER.info("sync summary: {}", standardSyncOutputString);
          if (standardSyncOutputString.length() > MAX_TEMPORAL_MESSAGE_SIZE) {
            LOGGER.error("Sync output exceeds the max temporal message size of {}, actual is {}.", MAX_TEMPORAL_MESSAGE_SIZE,
                standardSyncOutputString.length());
          } else {
            LOGGER.info("Sync summary length: {}", standardSyncOutputString.length());
          }
          List<StreamDescriptor> streamsToBackfill = List.of();
          if (replicationActivityInput.getSchemaRefreshOutput() != null) {
            streamsToBackfill = BackfillHelper
                .getStreamsToBackfill(replicationActivityInput.getSchemaRefreshOutput().getAppliedDiff(), hydratedReplicationInput.getCatalog());
          }
          BackfillHelper.markBackfilledStreams(streamsToBackfill, standardSyncOutput);
          LOGGER.info("sync summary after backfill: {}", standardSyncOutput);

          final StandardSyncOutput output = payloadChecker.validatePayloadSize(standardSyncOutput, metricAttributes);
          final ActivityPayloadURI uri = ActivityPayloadURI.v1(connectionId, jobId, attemptNumber, "replication-output");

          if (featureFlagClient.boolVariation(WriteReplicationOutputToObjectStorage.INSTANCE, new Connection(connectionId))) {
            output.setUri(uri.toOpenApi());

            try {
              storageClient.writeJSON(uri, output);
            } catch (final Exception e) {
              final List<MetricAttribute> attrs = new ArrayList<>(Arrays.asList(metricAttributes));
              attrs.add(new MetricAttribute(MetricTags.URI_ID, uri.getId()));
              attrs.add(new MetricAttribute(MetricTags.URI_VERSION, uri.getVersion()));
              attrs.add(new MetricAttribute(MetricTags.FAILURE_CAUSE, e.getClass().getSimpleName()));

              metricClient.count(OssMetricsRegistry.PAYLOAD_FAILURE_WRITE, 1, attrs.toArray(new MetricAttribute[] {}));
            }
          }

          return output;
        },
        context);
  }

  private StandardSyncOutput reduceReplicationOutput(final ReplicationOutput output, final MetricAttribute[] metricAttributes) {
    final StandardSyncOutput standardSyncOutput = new StandardSyncOutput();
    final StandardSyncSummary syncSummary = new StandardSyncSummary();
    final ReplicationAttemptSummary replicationSummary = output.getReplicationAttemptSummary();

    traceReplicationSummary(replicationSummary, metricAttributes);

    syncSummary.setBytesSynced(replicationSummary.getBytesSynced());
    syncSummary.setRecordsSynced(replicationSummary.getRecordsSynced());
    syncSummary.setStartTime(replicationSummary.getStartTime());
    syncSummary.setEndTime(replicationSummary.getEndTime());
    syncSummary.setStatus(replicationSummary.getStatus());
    syncSummary.setTotalStats(replicationSummary.getTotalStats());
    syncSummary.setStreamStats(replicationSummary.getStreamStats());
    syncSummary.setPerformanceMetrics(output.getReplicationAttemptSummary().getPerformanceMetrics());

    standardSyncOutput.setState(output.getState());
    standardSyncOutput.setOutputCatalog(output.getOutputCatalog());
    standardSyncOutput.setStandardSyncSummary(syncSummary);
    standardSyncOutput.setFailures(output.getFailures());

    return standardSyncOutput;
  }

  private boolean useWorkloadApi(final ReplicationActivityInput input) {
    // TODO: remove this once active workloads finish
    if (input.getUseWorkloadApi() == null) {
      return false;
    } else {
      return input.getUseWorkloadApi();
    }
  }

  private void traceReplicationSummary(final ReplicationAttemptSummary replicationSummary, final MetricAttribute[] metricAttributes) {
    if (replicationSummary == null) {
      return;
    }

    final Map<String, Object> tags = new HashMap<>();
    if (replicationSummary.getBytesSynced() != null) {
      tags.put(REPLICATION_BYTES_SYNCED_KEY, replicationSummary.getBytesSynced());
      metricClient.count(OssMetricsRegistry.REPLICATION_BYTES_SYNCED, replicationSummary.getBytesSynced(), metricAttributes);
    }
    if (replicationSummary.getRecordsSynced() != null) {
      tags.put(REPLICATION_RECORDS_SYNCED_KEY, replicationSummary.getRecordsSynced());
      metricClient.count(OssMetricsRegistry.REPLICATION_RECORDS_SYNCED, replicationSummary.getRecordsSynced(), metricAttributes);
    }
    if (replicationSummary.getStatus() != null) {
      tags.put(REPLICATION_STATUS_KEY, replicationSummary.getStatus().value());
    }
    if (!tags.isEmpty()) {
      ApmTraceUtils.addTagsToTrace(tags);
    }
  }

}
