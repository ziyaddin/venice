package com.linkedin.venice.controller.kafka.consumer;

import com.linkedin.venice.common.VeniceSystemStore;
import com.linkedin.venice.common.VeniceSystemStoreUtils;
import com.linkedin.venice.compression.CompressionStrategy;
import com.linkedin.venice.controller.ExecutionIdAccessor;
import com.linkedin.venice.controller.VeniceHelixAdmin;
import com.linkedin.venice.controller.kafka.offsets.AdminOffsetManager;
import com.linkedin.venice.controller.kafka.protocol.admin.AbortMigration;
import com.linkedin.venice.controller.kafka.protocol.admin.AddVersion;
import com.linkedin.venice.controller.kafka.protocol.admin.AdminOperation;
import com.linkedin.venice.controller.kafka.protocol.admin.DeleteAllVersions;
import com.linkedin.venice.controller.kafka.protocol.admin.DeleteOldVersion;
import com.linkedin.venice.controller.kafka.protocol.admin.DeleteStore;
import com.linkedin.venice.controller.kafka.protocol.admin.DerivedSchemaCreation;
import com.linkedin.venice.controller.kafka.protocol.admin.DisableStoreRead;
import com.linkedin.venice.controller.kafka.protocol.admin.EnableStoreRead;
import com.linkedin.venice.controller.kafka.protocol.admin.KillOfflinePushJob;
import com.linkedin.venice.controller.kafka.protocol.admin.MigrateStore;
import com.linkedin.venice.controller.kafka.protocol.admin.PauseStore;
import com.linkedin.venice.controller.kafka.protocol.admin.ResumeStore;
import com.linkedin.venice.controller.kafka.protocol.admin.SetStoreCurrentVersion;
import com.linkedin.venice.controller.kafka.protocol.admin.SetStoreOwner;
import com.linkedin.venice.controller.kafka.protocol.admin.SetStorePartitionCount;
import com.linkedin.venice.controller.kafka.protocol.admin.StoreCreation;
import com.linkedin.venice.controller.kafka.protocol.admin.SupersetSchemaCreation;
import com.linkedin.venice.controller.kafka.protocol.admin.UpdateStore;
import com.linkedin.venice.controller.kafka.protocol.admin.ValueSchemaCreation;
import com.linkedin.venice.controller.kafka.protocol.enums.AdminMessageType;
import com.linkedin.venice.controller.stats.AdminConsumptionStats;
import com.linkedin.venice.controllerapi.UpdateStoreQueryParams;
import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.exceptions.VeniceRetriableException;
import com.linkedin.venice.exceptions.VeniceUnsupportedOperationException;
import com.linkedin.venice.meta.BackupStrategy;
import com.linkedin.venice.meta.IncrementalPushPolicy;
import com.linkedin.venice.meta.Store;
import com.linkedin.venice.meta.Version;
import com.linkedin.venice.schema.SchemaEntry;
import com.linkedin.venice.utils.Utils;
import java.util.Queue;
import java.util.concurrent.Callable;
import java.util.concurrent.ConcurrentHashMap;
import org.apache.log4j.Logger;

import static com.linkedin.venice.controller.kafka.consumer.AdminConsumptionTask.*;


/**
 * This class is used to create {@link Callable} that execute {@link AdminOperation}s for a given store.
 */
public class AdminExecutionTask implements Callable<Void> {

  /**
   * This logger is intended to be passed from the {@link AdminConsumptionTask} in order to produce logs with the
   * tag AdminConsumptionTask#CONSUMER_TASK_ID_FORMAT.
   */
  private final Logger logger;
  private final String clusterName;
  private final String storeName;
  private final Queue<AdminOperationWrapper> internalTopic;
  private final VeniceHelixAdmin admin;
  private final ExecutionIdAccessor executionIdAccessor;
  private final boolean isParentController;
  private final AdminConsumptionStats stats;
  private final ConcurrentHashMap<String, Long> lastSucceededExecutionIdMap;
  private final long lastPersistedExecutionId;

  AdminExecutionTask(
      Logger logger,
      String clusterName,
      String storeName,
      ConcurrentHashMap<String, Long> lastSucceededExecutionIdMap,
      long lastPersistedExecutionId,
      Queue<AdminOperationWrapper> internalTopic,
      VeniceHelixAdmin admin,
      ExecutionIdAccessor executionIdAccessor,
      boolean isParentController,
      AdminConsumptionStats stats) {
    this.logger = logger;
    this.clusterName = clusterName;
    this.storeName = storeName;
    this.lastSucceededExecutionIdMap = lastSucceededExecutionIdMap;
    this.lastPersistedExecutionId = lastPersistedExecutionId;
    this.internalTopic = internalTopic;
    this.admin = admin;
    this.executionIdAccessor = executionIdAccessor;
    this.isParentController = isParentController;
    this.stats = stats;
  }

  @Override
  public Void call() {
    while (!internalTopic.isEmpty() && admin.isMasterController(clusterName)) {
      AdminOperationWrapper adminOperationWrapper = internalTopic.peek();
      try {
        if (adminOperationWrapper.getStartProcessingTimestamp() == null) {
          adminOperationWrapper.setStartProcessingTimestamp(System.currentTimeMillis());
          stats.recordAdminMessageStartProcessingLatency(Math.max(0,
              adminOperationWrapper.getStartProcessingTimestamp() - adminOperationWrapper.getLocalBrokerTimestamp()));
        }
        processMessage(adminOperationWrapper.getAdminOperation());
        long completionTimestamp = System.currentTimeMillis();
        long processLatency = Math.max(0, completionTimestamp - adminOperationWrapper.getStartProcessingTimestamp());
        if (AdminMessageType.valueOf(adminOperationWrapper.getAdminOperation()) == AdminMessageType.ADD_VERSION) {
          stats.recordAdminMessageProcessLatency(processLatency);
        } else {
          stats.recordAdminMessageProcessLatency(processLatency);
        }
        stats.recordAdminMessageTotalLatency(Math.max(0,
            completionTimestamp - adminOperationWrapper.getProducerTimestamp()));
        internalTopic.poll();
      } catch (Exception e) {
        // Retry of the admin operation is handled automatically by keeping the failed admin operation inside the queue.
        // The queue with the problematic operation will be delegated and retried by the worker thread in the next cycle.
        String logMessage = "when processing admin message for store " + storeName + " with offset "
            + adminOperationWrapper.getOffset() + " and execution id " + adminOperationWrapper.getAdminOperation().executionId;
        if (e instanceof VeniceRetriableException) {
          // These retriable exceptions are expected, therefore logging at the info level should be sufficient.
          stats.recordFailedRetriableAdminConsumption();
          logger.info("Retriable exception thrown " + logMessage, e);
        } else {
          stats.recordFailedAdminConsumption();
          logger.error("Error " + logMessage, e);
        }
        throw e;
      }
    }
    return null;
  }

  private void processMessage(AdminOperation adminOperation) {
    long lastSucceededExecutionId = lastSucceededExecutionIdMap.getOrDefault(storeName, lastPersistedExecutionId);
    if (adminOperation.executionId <= lastSucceededExecutionId) {
      /**
       * Since {@link AdminOffsetManager} will only keep the latest several
       * producer guids, which could not filter very old messages.
       * {@link #lastSucceededExecutionId} is monotonically increasing, and being persisted to Zookeeper after processing
       * each message, so it is safe to filter out all the processed messages.
       */
      logger.warn("Execution id of message: " + adminOperation
          + " for store " + storeName + " is smaller than last succeeded execution id: "
          + lastSucceededExecutionId + ", so will skip it");
      return;
    }
    try {
      switch (AdminMessageType.valueOf(adminOperation)) {
        case STORE_CREATION:
          handleStoreCreation((StoreCreation) adminOperation.payloadUnion);
          break;
        case VALUE_SCHEMA_CREATION:
          handleValueSchemaCreation((ValueSchemaCreation) adminOperation.payloadUnion);
          break;
        case DISABLE_STORE_WRITE:
          handleDisableStoreWrite((PauseStore) adminOperation.payloadUnion);
          break;
        case ENABLE_STORE_WRITE:
          handleEnableStoreWrite((ResumeStore) adminOperation.payloadUnion);
          break;
        case KILL_OFFLINE_PUSH_JOB:
          handleKillOfflinePushJob((KillOfflinePushJob) adminOperation.payloadUnion);
          break;
        case DISABLE_STORE_READ:
          handleDisableStoreRead((DisableStoreRead) adminOperation.payloadUnion);
          break;
        case ENABLE_STORE_READ:
          handleEnableStoreRead((EnableStoreRead) adminOperation.payloadUnion);
          break;
        case DELETE_ALL_VERSIONS:
          handleDeleteAllVersions((DeleteAllVersions) adminOperation.payloadUnion);
          break;
        case SET_STORE_CURRENT_VERSION:
          handleSetStoreCurrentVersion((SetStoreCurrentVersion) adminOperation.payloadUnion);
          break;
        case SET_STORE_OWNER:
          handleSetStoreOwner((SetStoreOwner) adminOperation.payloadUnion);
          break;
        case SET_STORE_PARTITION:
          handleSetStorePartitionCount((SetStorePartitionCount) adminOperation.payloadUnion);
          break;
        case UPDATE_STORE:
          handleSetStore((UpdateStore) adminOperation.payloadUnion);
          break;
        case DELETE_STORE:
          handleDeleteStore((DeleteStore) adminOperation.payloadUnion);
          break;
        case DELETE_OLD_VERSION:
          handleDeleteOldVersion((DeleteOldVersion) adminOperation.payloadUnion);
          break;
        case MIGRATE_STORE:
          handleStoreMigration((MigrateStore) adminOperation.payloadUnion);
          break;
        case ABORT_MIGRATION:
          handleAbortMigration((AbortMigration) adminOperation.payloadUnion);
          break;
        case ADD_VERSION:
          handleAddVersion((AddVersion) adminOperation.payloadUnion);
          break;
        case DERIVED_SCHEMA_CREATION:
          handleDerivedSchemaCreation((DerivedSchemaCreation) adminOperation.payloadUnion);
          break;
        case SUPERSET_SCHEMA_CREATION:
          handleSupersetSchemaCreation((SupersetSchemaCreation) adminOperation.payloadUnion);
          break;
        default:
          throw new VeniceException("Unknown admin operation type: " + adminOperation.operationType);
      }
    } catch (VeniceUnsupportedOperationException e) {
      logger.info("Ignoring the " + e.getClass().getSimpleName() + " caught when processing "
          + AdminMessageType.valueOf(adminOperation).toString() + "with detailed message: " + e.getMessage());
    }
    executionIdAccessor.updateLastSucceededExecutionIdMap(clusterName, storeName, adminOperation.executionId);
    lastSucceededExecutionIdMap.put(storeName, adminOperation.executionId);
  }

  private void handleStoreCreation(StoreCreation message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    String owner = message.owner.toString();
    String keySchema = message.keySchema.definition.toString();
    String valueSchema = message.valueSchema.definition.toString();

    // Check whether the store exists or not, the duplicate message could be
    // introduced by Kafka retry
    if (admin.hasStore(clusterName, storeName)) {
      logger.info("Adding store: " + storeName + ", which already exists, so just skip this message: " + message);
    } else {
      // Adding store
      admin.addStore(clusterName, storeName, owner, keySchema, valueSchema,
          VeniceSystemStoreUtils.isSystemStore(storeName));
      logger.info("Added store: " + storeName + " to cluster: " + clusterName);
    }
  }

  private void handleValueSchemaCreation(ValueSchemaCreation message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    String schemaStr = message.schema.definition.toString();
    int schemaId = message.schemaId;

    SchemaEntry valueSchemaEntry = admin.addValueSchema(clusterName, storeName, schemaStr, schemaId);
    logger.info("Added value schema: " + schemaStr + " to store: " + storeName + ", schema id: " + valueSchemaEntry.getId());
  }


  private void handleDerivedSchemaCreation(DerivedSchemaCreation message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    String derivedSchemaStr = message.schema.definition.toString();
    int valueSchemaId = message.valueSchemaId;
    int derivedSchemaId = message.derivedSchemaId;

    admin.addDerivedSchema(clusterName, storeName, valueSchemaId, derivedSchemaId, derivedSchemaStr);
    logger.info(String.format("Added derived schema:\n %s\n to store: %s, value schema id: %d, derived schema id: %d",
        derivedSchemaStr, storeName, valueSchemaId, derivedSchemaId));
  }

  private void handleSupersetSchemaCreation(SupersetSchemaCreation message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    String valueSchemaStr = message.valueSchema.definition.toString();
    int valueSchemaId = message.valueSchemaId;
    String supersetSchemaStr = message.supersetSchema.definition.toString();
    int supersetSchemaId = message.supersetSchemaId;

    admin.addSupersetSchema(clusterName, storeName, valueSchemaStr, valueSchemaId, supersetSchemaStr, supersetSchemaId);
    logger.info(String.format("Added value schema:\n %s\n to store: %s, value schema id: %d, also added superset schema: %s, superset schema id: %d",
        valueSchemaStr, storeName, valueSchemaId, supersetSchemaStr, supersetSchemaId));
  }

  private void handleDisableStoreWrite(PauseStore message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    admin.setStoreWriteability(clusterName, storeName, false);

    logger.info("Disabled store to write: " + storeName + " in cluster: " + clusterName);
  }

  private void handleEnableStoreWrite(ResumeStore message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    admin.setStoreWriteability(clusterName, storeName, true);

    logger.info("Enabled store to write: " + storeName + " in cluster: " + clusterName);
  }

  private void handleDisableStoreRead(DisableStoreRead message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    admin.setStoreReadability(clusterName, storeName, false);

    logger.info("Disabled store to read: " + storeName + " in cluster: " + clusterName);
  }

  private void handleEnableStoreRead(EnableStoreRead message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    admin.setStoreReadability(clusterName, storeName, true);
    logger.info("Enabled store to read: " + storeName + " in cluster: " + clusterName);
  }

  private void handleKillOfflinePushJob(KillOfflinePushJob message) {
    if (isParentController) {
      // Do nothing for Parent Controller
      return;
    }
    String clusterName = message.clusterName.toString();
    String kafkaTopic = message.kafkaTopic.toString();
    admin.killOfflinePush(clusterName, kafkaTopic, false);

    logger.info("Killed job with topic: " + kafkaTopic + " in cluster: " + clusterName);
  }

  private void handleDeleteAllVersions(DeleteAllVersions message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    admin.deleteAllVersionsInStore(clusterName, storeName);
    logger.info("Deleted all of version in store:" + storeName + " in cluster: " + clusterName);
  }

  private void handleDeleteOldVersion(DeleteOldVersion message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    int versionNum = message.versionNum;
    if (VeniceSystemStoreUtils.getSystemStoreType(storeName) == VeniceSystemStore.METADATA_STORE) {
      // Dematerialize a metadata store version.
      admin.dematerializeMetadataStoreVersion(clusterName,
          VeniceSystemStoreUtils.getStoreNameFromMetadataStoreName(storeName), versionNum, true);
    } else {
      // Delete an old version for a Venice store.
      admin.deleteOldVersionInStore(clusterName, storeName, versionNum);
    }
    logger.info("Deleted version: " + versionNum + " in store:" + storeName + " in cluster: " + clusterName);
  }

  private void handleSetStoreCurrentVersion(SetStoreCurrentVersion message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    int version = message.currentVersion;
    admin.setStoreCurrentVersion(clusterName, storeName, version);

    logger.info("Set store: " + storeName + " version to"  + version + " in cluster: " + clusterName);
  }

  private void handleSetStoreOwner(SetStoreOwner message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    String owner = message.owner.toString();
    admin.setStoreOwner(clusterName, storeName, owner);

    logger.info("Set store: " + storeName + " owner to " + owner + " in cluster: " + clusterName);
  }

  private void handleSetStorePartitionCount(SetStorePartitionCount message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    int partitionNum = message.partitionNum;
    admin.setStorePartitionCount(clusterName, storeName, partitionNum);

    logger.info("Set store: " + storeName + " partition number to " + partitionNum + " in cluster: " + clusterName);
  }

  private void handleSetStore(UpdateStore message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();

    UpdateStoreQueryParams params = new UpdateStoreQueryParams()
        .setOwner(message.owner.toString())
        .setEnableReads(message.enableReads)
        .setEnableWrites(message.enableWrites)
        .setPartitionCount(message.partitionNum);
    if (message.partitionerConfig != null) {
      params.setPartitionerClass(message.partitionerConfig.partitionerClass.toString())
          .setPartitionerParams(Utils.getStringMapFromCharSequenceMap(message.partitionerConfig.partitionerParams))
          .setAmplificationFactor(message.partitionerConfig.amplificationFactor);
    }
    params.setStorageQuotaInByte(message.storageQuotaInByte)
        .setHybridStoreOverheadBypass(message.hybridStoreOverheadBypass)
        .setReadQuotaInCU(message.readQuotaInCU);

    if (message.currentVersion != IGNORED_CURRENT_VERSION) {
      params.setCurrentVersion(message.currentVersion);
    }

    if (message.hybridStoreConfig != null) {
      params.setHybridRewindSeconds(message.hybridStoreConfig.rewindTimeInSeconds)
          .setHybridOffsetLagThreshold(message.hybridStoreConfig.offsetLagThresholdToGoOnline);
    }
    params.setAccessControlled(message.accessControlled)
        .setCompressionStrategy(CompressionStrategy.valueOf(message.compressionStrategy))
        .setClientDecompressionEnabled(message.clientDecompressionEnabled)
        .setChunkingEnabled(message.chunkingEnabled)
        .setSingleGetRouterCacheEnabled(message.singleGetRouterCacheEnabled)
        .setBatchGetRouterCacheEnabled(message.batchGetRouterCacheEnabled)
        .setBatchGetLimit(message.batchGetLimit)
        .setNumVersionsToPreserve(message.numVersionsToPreserve)
        .setIncrementalPushEnabled(message.incrementalPushEnabled)
        .setStoreMigration(message.isMigrating)
        .setWriteComputationEnabled(message.writeComputationEnabled)
        .setReadComputationEnabled(message.readComputationEnabled)
        .setBootstrapToOnlineTimeoutInHours(message.bootstrapToOnlineTimeoutInHours)
        .setLeaderFollowerModel(message.leaderFollowerModelEnabled)
        .setBackupStrategy(BackupStrategy.fromInt(message.backupStrategy))
        .setAutoSchemaPushJobEnabled(message.schemaAutoRegisterFromPushJobEnabled)
        .setHybridStoreDiskQuotaEnabled(message.hybridStoreDiskQuotaEnabled)
        .setReplicationFactor(message.replicationFactor);

    if (message.ETLStoreConfig != null) {
      params.setRegularVersionETLEnabled(message.ETLStoreConfig.regularVersionETLEnabled)
          .setFutureVersionETLEnabled(message.ETLStoreConfig.futureVersionETLEnabled)
          .setEtledProxyUserAccount(message.ETLStoreConfig.etledUserProxyAccount.toString());
    }

    if (message.largestUsedVersionNumber != null) {
      params.setLargestUsedVersionNumber(message.largestUsedVersionNumber);
    }

    params.setNativeReplicationEnabled(message.nativeReplicationEnabled)
        .setPushStreamSourceAddress(message.pushStreamSourceAddress == null ? null : message.pushStreamSourceAddress.toString())
        .setIncrementalPushPolicy(IncrementalPushPolicy.valueOf(message.incrementalPushPolicy))
        .setBackupVersionRetentionMs(message.backupVersionRetentionMs);

    if (checkPreConditionForReplicateUpdateStore(clusterName, storeName,
        message.isMigrating, message.enableReads, message.enableWrites)) {
      admin.replicateUpdateStore(clusterName, storeName, params);
    }

    admin.updateStore(clusterName, storeName, params);

    logger.info("Set store: " + storeName + " in cluster: " + clusterName);
  }

  private void handleDeleteStore(DeleteStore message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    int largestUsedVersionNumber = message.largestUsedVersionNumber;
    if (admin.hasStore(clusterName, storeName) && admin.getStore(clusterName, storeName).isMigrating()) {
      /** Ignore largest used version in original store in parent.
       * This is necessary if client pushes a new version during store migration, in which case the original store "largest used version"
       * in parent controller will not increase, but cloned store in parent and both original and cloned stores in child ontrollers will.
       * Without this change the "delete store" message will not be processed in child controller due to version mismatch.
       *
       * It will also allow Venice admin to issue delete store command to parent controller,
       * in case something goes wrong during store migration
       *
       * TODO: revise this logic when store migration is redesigned, new design should avoid this edge case altogether.
       */
      admin.deleteStore(clusterName, storeName, Store.IGNORE_VERSION);
    } else {
      admin.deleteStore(clusterName, storeName, largestUsedVersionNumber);
    }

    logger.info("Deleted store: " + storeName + " in cluster: " + clusterName);
  }

  private void handleStoreMigration(MigrateStore message) {
    String srcClusterName = message.srcClusterName.toString();
    String destClusterName = message.destClusterName.toString();
    String storeName = message.storeName.toString();
    if (this.isParentController) {
      // Src and dest parent controllers communicate
      admin.migrateStore(srcClusterName, destClusterName, storeName);
    } else {
      // Child controllers need to update migration src and dest cluster in storeConfig
      // Otherwise, storeConfig in the fabric won't have src and dest cluster info
      admin.setStoreConfigForMigration(storeName, srcClusterName, destClusterName);
    }
  }

  private void handleAbortMigration(AbortMigration message) {
    String srcClusterName = message.srcClusterName.toString();
    String destClusterName = message.destClusterName.toString();
    String storeName = message.storeName.toString();

    admin.abortMigration(srcClusterName, destClusterName, storeName);
  }

  private void handleAddVersion(AddVersion message) {
    String clusterName = message.clusterName.toString();
    String storeName = message.storeName.toString();
    String pushJobId = message.pushJobId.toString();
    int versionNumber = message.versionNum;
    int numberOfPartitions = message.numberOfPartitions;
    Version.PushType pushType = Version.PushType.valueOf(message.pushType);
    String remoteKafkaBootstrapServers = message.pushStreamSourceAddress == null ? null : message.pushStreamSourceAddress.toString();
    if (isParentController) {
      if (checkPreConditionForReplicateAddVersion(clusterName, storeName)) {
        // Parent controller mirrors new version to src or dest cluster if the store is migrating
        admin.replicateAddVersionAndStartIngestion(clusterName, storeName, pushJobId, versionNumber, numberOfPartitions,
            pushType, remoteKafkaBootstrapServers);
      }
    } else {
      if (VeniceSystemStoreUtils.getSharedZkNameForMetadataStore(clusterName).equals(storeName)) {
        // New version for the Zk shared metadata store.
        admin.newZkSharedStoreVersion(clusterName, storeName);
      } else if (VeniceSystemStoreUtils.getSystemStoreType(storeName) == VeniceSystemStore.METADATA_STORE) {
        // Materialize a metadata store for a specific Venice store.
        admin.materializeMetadataStoreVersion(clusterName,
            VeniceSystemStoreUtils.getStoreNameFromMetadataStoreName(storeName), versionNumber);
      } else {
        // New version for regular Venice store.
        admin.addVersionAndStartIngestion(clusterName, storeName, pushJobId, versionNumber, numberOfPartitions, pushType,
            remoteKafkaBootstrapServers);
      }
    }
  }

  private boolean checkPreConditionForReplicateAddVersion(String clusterName, String storeName) {
    return admin.getStore(clusterName, storeName).isMigrating();
  }

  private boolean checkPreConditionForReplicateUpdateStore(String clusterName, String storeName,
      boolean isMigrating, boolean isEnableReads, boolean isEnableWrites) {
    if (this.isParentController && admin.hasStore(clusterName, storeName)) {
      Store store = admin.getStore(clusterName, storeName);
      if (store.isMigrating()) {
        boolean storeMigrationUpdated = isMigrating != store.isMigrating();
        boolean readabilityUpdated =  isEnableReads != store.isEnableReads();
        boolean writeabilityUpdated = isEnableWrites != store.isEnableWrites();
        if (storeMigrationUpdated || readabilityUpdated || writeabilityUpdated) {
          // No need to mirror these updates
          return false;
        }
        return true;
      }
    }
    return false;
  }
}
