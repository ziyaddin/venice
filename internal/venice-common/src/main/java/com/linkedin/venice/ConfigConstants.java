package com.linkedin.venice;

import com.linkedin.venice.utils.Time;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;


public class ConfigConstants {
  private static final Logger LOGGER = LogManager.getLogger(ConfigConstants.class);
  /**
   * Start of controller config default value
   */

  /**
   * Default value of sleep interval for polling topic deletion status from ZK.
   */
  public static final int DEFAULT_TOPIC_DELETION_STATUS_POLL_INTERVAL_MS = 2 * Time.MS_PER_SECOND;

  public static final int UNSPECIFIED_REPLICATION_METADATA_VERSION = -1;

  /**
   * End of controller config default value
   */
}
