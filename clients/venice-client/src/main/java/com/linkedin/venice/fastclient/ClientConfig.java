package com.linkedin.venice.fastclient;

import com.linkedin.d2.balancer.D2Client;
import com.linkedin.davinci.client.DaVinciClient;
import com.linkedin.r2.transport.common.Client;
import com.linkedin.venice.client.exceptions.VeniceClientException;
import com.linkedin.venice.client.store.AvroGenericStoreClient;
import com.linkedin.venice.client.store.AvroSpecificStoreClient;
import com.linkedin.venice.fastclient.meta.ClientRoutingStrategyType;
import com.linkedin.venice.fastclient.meta.StoreMetadataFetchMode;
import com.linkedin.venice.fastclient.stats.ClusterStats;
import com.linkedin.venice.fastclient.stats.FastClientStats;
import com.linkedin.venice.read.RequestType;
import com.linkedin.venice.systemstore.schemas.StoreMetaKey;
import com.linkedin.venice.systemstore.schemas.StoreMetaValue;
import com.linkedin.venice.utils.concurrent.VeniceConcurrentHashMap;
import io.tehuti.metrics.MetricsRepository;
import java.util.Map;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import org.apache.avro.specific.SpecificRecord;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class ClientConfig<K, V, T extends SpecificRecord> {
  private static final Logger LOGGER = LogManager.getLogger(ClientConfig.class);
  private final Client r2Client;
  private final String statsPrefix;
  private final boolean speculativeQueryEnabled;
  private final Class<T> specificValueClass;
  private final String storeName;
  private final Map<RequestType, FastClientStats> clientStatsMap = new VeniceConcurrentHashMap<>();
  private final Executor deserializationExecutor;
  private final ClientRoutingStrategyType clientRoutingStrategyType;
  /**
   * For dual-read support.
   */
  private final boolean dualReadEnabled;
  private final AvroGenericStoreClient<K, V> genericThinClient;
  private final AvroSpecificStoreClient<K, T> specificThinClient;

  /**
   * For Client Routing.
   * Please check {@link com.linkedin.venice.fastclient.meta.InstanceHealthMonitor} to find more details.
   */
  private final long routingLeakedRequestCleanupThresholdMS;
  private final long routingQuotaExceededRequestCounterResetDelayMS;
  private final long routingErrorRequestCounterResetDelayMS;
  private final long routingUnavailableRequestCounterResetDelayMS;
  private final int routingPendingRequestCounterInstanceBlockThreshold;

  // Max allowed key count in batch-get request
  private final int maxAllowedKeyCntInBatchGetReq;
  protected static final int MAX_ALLOWED_KEY_COUNT_IN_BATCHGET = 150;
  private final DaVinciClient<StoreMetaKey, StoreMetaValue> daVinciClientForMetaStore;
  private final AvroSpecificStoreClient<StoreMetaKey, StoreMetaValue> thinClientForMetaStore;
  /**
   * Config to enable/disable warm up connection to instances from fetched metadata.
   */
  private final boolean isMetadataConnWarmupEnabled;
  /**
   * The interval in seconds to refresh metadata. If not configured, it will be set to
   * {@link com.linkedin.venice.fastclient.meta.RequestBasedMetadata#DEFAULT_REFRESH_INTERVAL_IN_SECONDS} by default.
   */
  private final long metadataRefreshIntervalInSeconds;
  /**
   * The timeout in seconds to wait to warm up connection to metadata instances. If not configured, it will be set to
   * {@link com.linkedin.venice.fastclient.meta.RequestBasedMetadata#DEFAULT_CONN_WARMUP_TIMEOUT_IN_SECONDS_DEFAULT} by default.
   */
  private final long metadataConnWarmupTimeoutInSeconds;
  private final boolean longTailRetryEnabledForSingleGet;
  private final boolean longTailRetryEnabledForBatchGet;
  private final boolean longTailRetryEnabledForCompute;
  private final int longTailRetryThresholdForSingleGetInMicroSeconds;
  private final int longTailRetryThresholdForBatchGetInMicroSeconds;
  private final int longTailRetryThresholdForComputeInMicroSeconds;
  private final ClusterStats clusterStats;
  private final boolean isVsonStore;
  private final StoreMetadataFetchMode storeMetadataFetchMode;
  private final D2Client d2Client;
  private final String clusterDiscoveryD2Service;
  private final boolean useGrpc;
  /**
   * This is a temporary solution to support gRPC with Venice, we will replace this with retrieving information about
   * gRPC servers when we make a request to receive Metadata from a server to obtain information in order to successfully
   * route requests to the correct server/partition
   */
  private final GrpcClientConfig grpcClientConfig;

  private boolean projectionFieldValidation;

  private ClientConfig(
      String storeName,
      Client r2Client,
      MetricsRepository metricsRepository,
      String statsPrefix,
      boolean speculativeQueryEnabled,
      Class<T> specificValueClass,
      Executor deserializationExecutor,
      ClientRoutingStrategyType clientRoutingStrategyType,
      boolean dualReadEnabled,
      AvroGenericStoreClient<K, V> genericThinClient,
      AvroSpecificStoreClient<K, T> specificThinClient,
      long routingLeakedRequestCleanupThresholdMS,
      long routingQuotaExceededRequestCounterResetDelayMS,
      long routingErrorRequestCounterResetDelayMS,
      long routingUnavailableRequestCounterResetDelayMS,
      int routingPendingRequestCounterInstanceBlockThreshold,
      int maxAllowedKeyCntInBatchGetReq,
      DaVinciClient<StoreMetaKey, StoreMetaValue> daVinciClientForMetaStore,
      AvroSpecificStoreClient<StoreMetaKey, StoreMetaValue> thinClientForMetaStore,
      boolean isMetadataConnWarmupEnabled,
      long metadataRefreshIntervalInSeconds,
      long metadataConnWarmupTimeoutInSeconds,
      boolean longTailRetryEnabledForSingleGet,
      int longTailRetryThresholdForSingleGetInMicroSeconds,
      boolean longTailRetryEnabledForBatchGet,
      int longTailRetryThresholdForBatchGetInMicroSeconds,
      boolean longTailRetryEnabledForCompute,
      int longTailRetryThresholdForComputeInMicroSeconds,
      boolean isVsonStore,
      StoreMetadataFetchMode storeMetadataFetchMode,
      D2Client d2Client,
      String clusterDiscoveryD2Service,
      boolean useGrpc,
      GrpcClientConfig grpcClientConfig,
      boolean projectionFieldValidation) {
    if (storeName == null || storeName.isEmpty()) {
      throw new VeniceClientException("storeName param shouldn't be empty");
    }
    if (r2Client == null && !useGrpc) {
      throw new VeniceClientException("r2Client param shouldn't be null");
    }
    if (useGrpc && grpcClientConfig == null) {
      throw new UnsupportedOperationException(
          "we require additional gRPC related configs when we create a gRPC enabled client");
    }

    this.r2Client = r2Client;
    this.storeName = storeName;
    this.statsPrefix = (statsPrefix == null ? "" : statsPrefix);
    if (metricsRepository == null) {
      metricsRepository = new MetricsRepository();
    }
    // TODO consider changing the implementation or make it explicit that the config builder can only build once with
    // the same metricsRepository
    for (RequestType requestType: RequestType.values()) {
      clientStatsMap.put(
          requestType,
          FastClientStats.getClientStats(metricsRepository, this.statsPrefix, storeName, requestType));
    }
    this.clusterStats = new ClusterStats(metricsRepository, storeName);
    this.speculativeQueryEnabled = speculativeQueryEnabled;
    this.specificValueClass = specificValueClass;
    this.deserializationExecutor = deserializationExecutor;
    this.clientRoutingStrategyType =
        clientRoutingStrategyType == null ? ClientRoutingStrategyType.LEAST_LOADED : clientRoutingStrategyType;
    this.dualReadEnabled = dualReadEnabled;
    this.genericThinClient = genericThinClient;
    this.specificThinClient = specificThinClient;
    if (this.dualReadEnabled) {
      if (this.specificThinClient == null && this.genericThinClient == null) {
        throw new VeniceClientException(
            "Either param: specificThinClient or param: genericThinClient"
                + " should be specified when dual read is enabled");
      }
    } else {
      if (this.specificThinClient != null || this.genericThinClient != null) {
        throw new VeniceClientException(
            "Both param: specificThinClient and param: genericThinClient"
                + " should not be specified when dual read is not enabled");
      }
    }

    this.routingLeakedRequestCleanupThresholdMS = routingLeakedRequestCleanupThresholdMS > 0
        ? routingLeakedRequestCleanupThresholdMS
        : TimeUnit.SECONDS.toMillis(30); // 30 seconds by default
    this.routingQuotaExceededRequestCounterResetDelayMS =
        routingQuotaExceededRequestCounterResetDelayMS > 0 ? routingQuotaExceededRequestCounterResetDelayMS : 50; // 50
                                                                                                                  // ms
                                                                                                                  // by
                                                                                                                  // default
    this.routingErrorRequestCounterResetDelayMS = routingErrorRequestCounterResetDelayMS > 0
        ? routingErrorRequestCounterResetDelayMS
        : TimeUnit.SECONDS.toMillis(10); // 10 seconds
    this.routingUnavailableRequestCounterResetDelayMS = routingUnavailableRequestCounterResetDelayMS > 0
        ? routingUnavailableRequestCounterResetDelayMS
        : TimeUnit.SECONDS.toMillis(10); // 10 seconds
    this.routingPendingRequestCounterInstanceBlockThreshold = routingPendingRequestCounterInstanceBlockThreshold > 0
        ? routingPendingRequestCounterInstanceBlockThreshold
        : 50;

    this.maxAllowedKeyCntInBatchGetReq = maxAllowedKeyCntInBatchGetReq;

    this.daVinciClientForMetaStore = daVinciClientForMetaStore;
    this.thinClientForMetaStore = thinClientForMetaStore;
    this.isMetadataConnWarmupEnabled = isMetadataConnWarmupEnabled;
    this.metadataRefreshIntervalInSeconds = metadataRefreshIntervalInSeconds;
    this.metadataConnWarmupTimeoutInSeconds = metadataConnWarmupTimeoutInSeconds;

    this.longTailRetryEnabledForSingleGet = longTailRetryEnabledForSingleGet;
    this.longTailRetryThresholdForSingleGetInMicroSeconds = longTailRetryThresholdForSingleGetInMicroSeconds;

    this.longTailRetryEnabledForBatchGet = longTailRetryEnabledForBatchGet;
    this.longTailRetryThresholdForBatchGetInMicroSeconds = longTailRetryThresholdForBatchGetInMicroSeconds;

    this.longTailRetryEnabledForCompute = longTailRetryEnabledForCompute;
    this.longTailRetryThresholdForComputeInMicroSeconds = longTailRetryThresholdForComputeInMicroSeconds;

    if (this.longTailRetryEnabledForSingleGet) {
      if (this.longTailRetryThresholdForSingleGetInMicroSeconds <= 0) {
        throw new VeniceClientException(
            "longTailRetryThresholdForSingleGetInMicroSeconds must be positive, but got: "
                + this.longTailRetryThresholdForSingleGetInMicroSeconds);
      }
    }

    if (this.longTailRetryEnabledForBatchGet) {
      if (this.longTailRetryThresholdForBatchGetInMicroSeconds <= 0) {
        throw new VeniceClientException(
            "longTailRetryThresholdForBatchGetInMicroSeconds must be positive, but got: "
                + this.longTailRetryThresholdForBatchGetInMicroSeconds);
      }
    }

    if (this.longTailRetryEnabledForCompute) {
      if (this.longTailRetryThresholdForComputeInMicroSeconds <= 0) {
        throw new VeniceClientException(
            "longTailRetryThresholdForComputeInMicroSeconds must be positive, but got: "
                + this.longTailRetryThresholdForComputeInMicroSeconds);
      }
    }

    // TODO: Need to check whether this case applies for BatchGet
    if (this.speculativeQueryEnabled && this.longTailRetryEnabledForSingleGet) {
      throw new VeniceClientException(
          "Speculative query feature can't be enabled together with long-tail retry for single-get");
    }

    this.isVsonStore = isVsonStore;

    this.storeMetadataFetchMode = storeMetadataFetchMode;
    this.d2Client = d2Client;
    this.clusterDiscoveryD2Service = clusterDiscoveryD2Service;
    if (this.storeMetadataFetchMode == StoreMetadataFetchMode.SERVER_BASED_METADATA) {
      if (this.d2Client == null || this.clusterDiscoveryD2Service == null) {
        throw new VeniceClientException(
            "Both param: d2Client and param: clusterDiscoveryD2Service must be set for request based metadata");
      }
    }
    if (clientRoutingStrategyType == ClientRoutingStrategyType.HELIX_ASSISTED
        && this.storeMetadataFetchMode != StoreMetadataFetchMode.SERVER_BASED_METADATA) {
      throw new VeniceClientException("Helix assisted routing is only available with server based metadata enabled");
    }

    this.useGrpc = useGrpc;
    this.grpcClientConfig = grpcClientConfig;

    this.projectionFieldValidation = projectionFieldValidation;
  }

  public String getStoreName() {
    return storeName;
  }

  public Client getR2Client() {
    return r2Client;
  }

  public FastClientStats getStats(RequestType requestType) {
    return clientStatsMap.get(requestType);
  }

  public boolean isSpeculativeQueryEnabled() {
    return speculativeQueryEnabled;
  }

  public Class<T> getSpecificValueClass() {
    return specificValueClass;
  }

  public Executor getDeserializationExecutor() {
    return deserializationExecutor;
  }

  public boolean isDualReadEnabled() {
    return dualReadEnabled;
  }

  public AvroGenericStoreClient<K, V> getGenericThinClient() {
    return genericThinClient;
  }

  public AvroSpecificStoreClient<K, T> getSpecificThinClient() {
    return specificThinClient;
  }

  public long getRoutingLeakedRequestCleanupThresholdMS() {
    return routingLeakedRequestCleanupThresholdMS;
  }

  public long getRoutingQuotaExceededRequestCounterResetDelayMS() {
    return routingQuotaExceededRequestCounterResetDelayMS;
  }

  public long getRoutingErrorRequestCounterResetDelayMS() {
    return routingErrorRequestCounterResetDelayMS;
  }

  public long getRoutingUnavailableRequestCounterResetDelayMS() {
    return routingUnavailableRequestCounterResetDelayMS;
  }

  public int getRoutingPendingRequestCounterInstanceBlockThreshold() {
    return routingPendingRequestCounterInstanceBlockThreshold;
  }

  public int getMaxAllowedKeyCntInBatchGetReq() {
    return maxAllowedKeyCntInBatchGetReq;
  }

  public DaVinciClient<StoreMetaKey, StoreMetaValue> getDaVinciClientForMetaStore() {
    return daVinciClientForMetaStore;
  }

  public AvroSpecificStoreClient<StoreMetaKey, StoreMetaValue> getThinClientForMetaStore() {
    return thinClientForMetaStore;
  }

  public boolean isMetadataConnWarmupEnabled() {
    return isMetadataConnWarmupEnabled;
  }

  public long getMetadataRefreshIntervalInSeconds() {
    return metadataRefreshIntervalInSeconds;
  }

  public long getMetadataConnWarmupTimeoutInSeconds() {
    return metadataConnWarmupTimeoutInSeconds;
  }

  public boolean isLongTailRetryEnabledForSingleGet() {
    return longTailRetryEnabledForSingleGet;
  }

  public int getLongTailRetryThresholdForSingleGetInMicroSeconds() {
    return longTailRetryThresholdForSingleGetInMicroSeconds;
  }

  public boolean isLongTailRetryEnabledForBatchGet() {
    return longTailRetryEnabledForBatchGet;
  }

  public int getLongTailRetryThresholdForBatchGetInMicroSeconds() {
    return longTailRetryThresholdForBatchGetInMicroSeconds;
  }

  public boolean isLongTailRetryEnabledForCompute() {
    return longTailRetryEnabledForCompute;
  }

  public int getLongTailRetryThresholdForComputeInMicroSeconds() {
    return longTailRetryThresholdForComputeInMicroSeconds;
  }

  @Deprecated
  public boolean isVsonStore() {
    return isVsonStore;
  }

  public ClientRoutingStrategyType getClientRoutingStrategyType() {
    return clientRoutingStrategyType;
  }

  public ClusterStats getClusterStats() {
    return this.clusterStats;
  }

  public StoreMetadataFetchMode getStoreMetadataFetchMode() {
    return this.storeMetadataFetchMode;
  }

  public D2Client getD2Client() {
    return this.d2Client;
  }

  public String getClusterDiscoveryD2Service() {
    return this.clusterDiscoveryD2Service;
  }

  public boolean useGrpc() {
    return useGrpc;
  }

  public GrpcClientConfig getGrpcClientConfig() {
    return grpcClientConfig;
  }

  public boolean isProjectionFieldValidationEnabled() {
    return projectionFieldValidation;
  }

  public ClientConfig setProjectionFieldValidationEnabled(boolean projectionFieldValidation) {
    this.projectionFieldValidation = projectionFieldValidation;
    return this;
  }

  public static class ClientConfigBuilder<K, V, T extends SpecificRecord> {
    private MetricsRepository metricsRepository;
    private String statsPrefix = "";
    private boolean speculativeQueryEnabled = false;
    private Class<T> specificValueClass;
    private String storeName;
    private Executor deserializationExecutor;
    private ClientRoutingStrategyType clientRoutingStrategyType;
    private Client r2Client;
    private boolean dualReadEnabled = false;
    private AvroGenericStoreClient<K, V> genericThinClient;
    private AvroSpecificStoreClient<K, T> specificThinClient;

    private long routingLeakedRequestCleanupThresholdMS = -1;
    private long routingQuotaExceededRequestCounterResetDelayMS = -1;
    private long routingErrorRequestCounterResetDelayMS = -1;
    private long routingUnavailableRequestCounterResetDelayMS = -1;
    private int routingPendingRequestCounterInstanceBlockThreshold = -1;
    /**
     * maxAllowedKeyCntInBatchGetReq is set to {@link #MAX_ALLOWED_KEY_COUNT_IN_BATCHGET}
     * for fast-client and can be overridden by client config.
     */
    private int maxAllowedKeyCntInBatchGetReq = MAX_ALLOWED_KEY_COUNT_IN_BATCHGET;

    private DaVinciClient<StoreMetaKey, StoreMetaValue> daVinciClientForMetaStore;

    private AvroSpecificStoreClient<StoreMetaKey, StoreMetaValue> thinClientForMetaStore;

    private boolean isMetadataConnWarmupEnabled = true;
    private long metadataRefreshIntervalInSeconds = -1;
    private long metadataConnWarmupTimeoutInSeconds = -1;

    private boolean longTailRetryEnabledForSingleGet = false;
    private int longTailRetryThresholdForSingleGetInMicroSeconds = 1000; // 1ms.

    private boolean longTailRetryEnabledForBatchGet = false;
    private int longTailRetryThresholdForBatchGetInMicroSeconds = 10000; // 10ms.

    private boolean longTailRetryEnabledForCompute = false;
    private int longTailRetryThresholdForComputeInMicroSeconds = 10000; // 10ms.

    private boolean isVsonStore = false;
    private StoreMetadataFetchMode storeMetadataFetchMode = StoreMetadataFetchMode.DA_VINCI_CLIENT_BASED_METADATA;
    private D2Client d2Client;
    private String clusterDiscoveryD2Service;
    private boolean useGrpc = false;
    private GrpcClientConfig grpcClientConfig = null;

    private boolean projectionFieldValidation = true;

    public ClientConfigBuilder<K, V, T> setStoreName(String storeName) {
      this.storeName = storeName;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setMetricsRepository(MetricsRepository metricsRepository) {
      this.metricsRepository = metricsRepository;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setStatsPrefix(String statsPrefix) {
      this.statsPrefix = statsPrefix;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setSpeculativeQueryEnabled(boolean speculativeQueryEnabled) {
      this.speculativeQueryEnabled = speculativeQueryEnabled;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setSpecificValueClass(Class<T> specificValueClass) {
      this.specificValueClass = specificValueClass;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setDeserializationExecutor(Executor deserializationExecutor) {
      this.deserializationExecutor = deserializationExecutor;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setClientRoutingStrategyType(
        ClientRoutingStrategyType clientRoutingStrategyType) {
      this.clientRoutingStrategyType = clientRoutingStrategyType;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setR2Client(Client r2Client) {
      this.r2Client = r2Client;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setDualReadEnabled(boolean dualReadEnabled) {
      this.dualReadEnabled = dualReadEnabled;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setGenericThinClient(AvroGenericStoreClient<K, V> genericThinClient) {
      this.genericThinClient = genericThinClient;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setSpecificThinClient(AvroSpecificStoreClient<K, T> specificThinClient) {
      this.specificThinClient = specificThinClient;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingLeakedRequestCleanupThresholdMS(
        long routingLeakedRequestCleanupThresholdMS) {
      this.routingLeakedRequestCleanupThresholdMS = routingLeakedRequestCleanupThresholdMS;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingQuotaExceededRequestCounterResetDelayMS(
        long routingQuotaExceededRequestCounterResetDelayMS) {
      this.routingQuotaExceededRequestCounterResetDelayMS = routingQuotaExceededRequestCounterResetDelayMS;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingErrorRequestCounterResetDelayMS(
        long routingErrorRequestCounterResetDelayMS) {
      this.routingErrorRequestCounterResetDelayMS = routingErrorRequestCounterResetDelayMS;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingUnavailableRequestCounterResetDelayMS(
        long routingUnavailableRequestCounterResetDelayMS) {
      this.routingUnavailableRequestCounterResetDelayMS = routingUnavailableRequestCounterResetDelayMS;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setRoutingPendingRequestCounterInstanceBlockThreshold(
        int routingPendingRequestCounterInstanceBlockThreshold) {
      this.routingPendingRequestCounterInstanceBlockThreshold = routingPendingRequestCounterInstanceBlockThreshold;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setDaVinciClientForMetaStore(
        DaVinciClient<StoreMetaKey, StoreMetaValue> daVinciClientForMetaStore) {
      this.daVinciClientForMetaStore = daVinciClientForMetaStore;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setThinClientForMetaStore(
        AvroSpecificStoreClient<StoreMetaKey, StoreMetaValue> thinClientForMetaStore) {
      this.thinClientForMetaStore = thinClientForMetaStore;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setIsMetadataConnWarmupEnabled(boolean isMetadataConnWarmupEnabled) {
      this.isMetadataConnWarmupEnabled = isMetadataConnWarmupEnabled;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setMetadataRefreshIntervalInSeconds(long metadataRefreshIntervalInSeconds) {
      this.metadataRefreshIntervalInSeconds = metadataRefreshIntervalInSeconds;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setMetadataConnWarmupTimeoutInSeconds(long metadataConnWarmupTimeoutInSeconds) {
      this.metadataConnWarmupTimeoutInSeconds = metadataConnWarmupTimeoutInSeconds;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setMaxAllowedKeyCntInBatchGetReq(int maxAllowedKeyCntInBatchGetReq) {
      this.maxAllowedKeyCntInBatchGetReq = maxAllowedKeyCntInBatchGetReq;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setLongTailRetryEnabledForSingleGet(boolean longTailRetryEnabledForSingleGet) {
      this.longTailRetryEnabledForSingleGet = longTailRetryEnabledForSingleGet;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setLongTailRetryThresholdForSingleGetInMicroSeconds(
        int longTailRetryThresholdForSingleGetInMicroSeconds) {
      this.longTailRetryThresholdForSingleGetInMicroSeconds = longTailRetryThresholdForSingleGetInMicroSeconds;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setLongTailRetryEnabledForBatchGet(boolean longTailRetryEnabledForBatchGet) {
      this.longTailRetryEnabledForBatchGet = longTailRetryEnabledForBatchGet;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setLongTailRetryThresholdForBatchGetInMicroSeconds(
        int longTailRetryThresholdForBatchGetInMicroSeconds) {
      this.longTailRetryThresholdForBatchGetInMicroSeconds = longTailRetryThresholdForBatchGetInMicroSeconds;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setLongTailRetryEnabledForCompute(boolean longTailRetryEnabledForCompute) {
      this.longTailRetryEnabledForCompute = longTailRetryEnabledForCompute;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setLongTailRetryThresholdForComputeInMicroSeconds(
        int longTailRetryThresholdForComputeInMicroSeconds) {
      this.longTailRetryThresholdForComputeInMicroSeconds = longTailRetryThresholdForComputeInMicroSeconds;
      return this;
    }

    @Deprecated
    public ClientConfigBuilder<K, V, T> setVsonStore(boolean vsonStore) {
      isVsonStore = vsonStore;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setStoreMetadataFetchMode(StoreMetadataFetchMode storeMetadataFetchMode) {
      this.storeMetadataFetchMode = storeMetadataFetchMode;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setD2Client(D2Client d2Client) {
      this.d2Client = d2Client;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setClusterDiscoveryD2Service(String clusterDiscoveryD2Service) {
      this.clusterDiscoveryD2Service = clusterDiscoveryD2Service;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setUseGrpc(boolean useGrpc) {
      this.useGrpc = useGrpc;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setGrpcClientConfig(GrpcClientConfig grpcClientConfig) {
      this.grpcClientConfig = grpcClientConfig;
      return this;
    }

    public ClientConfigBuilder<K, V, T> setProjectionFieldValidationEnabled(boolean projectionFieldValidation) {
      this.projectionFieldValidation = projectionFieldValidation;
      return this;
    }

    public ClientConfigBuilder<K, V, T> clone() {
      return new ClientConfigBuilder().setStoreName(storeName)
          .setR2Client(r2Client)
          .setMetricsRepository(metricsRepository)
          .setStatsPrefix(statsPrefix)
          .setSpeculativeQueryEnabled(speculativeQueryEnabled)
          .setSpecificValueClass(specificValueClass)
          .setDeserializationExecutor(deserializationExecutor)
          .setClientRoutingStrategyType(clientRoutingStrategyType)
          .setDualReadEnabled(dualReadEnabled)
          .setGenericThinClient(genericThinClient)
          .setSpecificThinClient(specificThinClient)
          .setRoutingLeakedRequestCleanupThresholdMS(routingLeakedRequestCleanupThresholdMS)
          .setRoutingQuotaExceededRequestCounterResetDelayMS(routingQuotaExceededRequestCounterResetDelayMS)
          .setRoutingErrorRequestCounterResetDelayMS(routingErrorRequestCounterResetDelayMS)
          .setRoutingUnavailableRequestCounterResetDelayMS(routingUnavailableRequestCounterResetDelayMS)
          .setRoutingPendingRequestCounterInstanceBlockThreshold(routingPendingRequestCounterInstanceBlockThreshold)
          .setMaxAllowedKeyCntInBatchGetReq(maxAllowedKeyCntInBatchGetReq)
          .setDaVinciClientForMetaStore(daVinciClientForMetaStore)
          .setThinClientForMetaStore(thinClientForMetaStore)
          .setIsMetadataConnWarmupEnabled(isMetadataConnWarmupEnabled)
          .setMetadataRefreshIntervalInSeconds(metadataRefreshIntervalInSeconds)
          .setMetadataConnWarmupTimeoutInSeconds(metadataConnWarmupTimeoutInSeconds)
          .setLongTailRetryEnabledForSingleGet(longTailRetryEnabledForSingleGet)
          .setLongTailRetryThresholdForSingleGetInMicroSeconds(longTailRetryThresholdForSingleGetInMicroSeconds)
          .setLongTailRetryEnabledForBatchGet(longTailRetryEnabledForBatchGet)
          .setLongTailRetryThresholdForBatchGetInMicroSeconds(longTailRetryThresholdForBatchGetInMicroSeconds)
          .setLongTailRetryEnabledForCompute(longTailRetryEnabledForCompute)
          .setLongTailRetryThresholdForComputeInMicroSeconds(longTailRetryThresholdForComputeInMicroSeconds)
          .setVsonStore(isVsonStore)
          .setStoreMetadataFetchMode(storeMetadataFetchMode)
          .setD2Client(d2Client)
          .setClusterDiscoveryD2Service(clusterDiscoveryD2Service)
          .setUseGrpc(useGrpc)
          .setGrpcClientConfig(grpcClientConfig)
          .setProjectionFieldValidationEnabled(projectionFieldValidation);
    }

    public ClientConfig<K, V, T> build() {
      return new ClientConfig<>(
          storeName,
          r2Client,
          metricsRepository,
          statsPrefix,
          speculativeQueryEnabled,
          specificValueClass,
          deserializationExecutor,
          clientRoutingStrategyType,
          dualReadEnabled,
          genericThinClient,
          specificThinClient,
          routingLeakedRequestCleanupThresholdMS,
          routingQuotaExceededRequestCounterResetDelayMS,
          routingErrorRequestCounterResetDelayMS,
          routingUnavailableRequestCounterResetDelayMS,
          routingPendingRequestCounterInstanceBlockThreshold,
          maxAllowedKeyCntInBatchGetReq,
          daVinciClientForMetaStore,
          thinClientForMetaStore,
          isMetadataConnWarmupEnabled,
          metadataRefreshIntervalInSeconds,
          metadataConnWarmupTimeoutInSeconds,
          longTailRetryEnabledForSingleGet,
          longTailRetryThresholdForSingleGetInMicroSeconds,
          longTailRetryEnabledForBatchGet,
          longTailRetryThresholdForBatchGetInMicroSeconds,
          longTailRetryEnabledForCompute,
          longTailRetryThresholdForComputeInMicroSeconds,
          isVsonStore,
          storeMetadataFetchMode,
          d2Client,
          clusterDiscoveryD2Service,
          useGrpc,
          grpcClientConfig,
          projectionFieldValidation);
    }
  }
}
