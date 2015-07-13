package com.linkedin.venice.kafka.producer;

import com.linkedin.venice.exceptions.VeniceException;
import com.linkedin.venice.message.KafkaKey;
import com.linkedin.venice.message.KafkaValue;
import com.linkedin.venice.utils.Props;

import java.util.Iterator;
import java.util.Properties;
import java.util.concurrent.Future;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;


/**
 * Implementation of the Kafka Producer.
 * Receives and sends messages to Kafka.
 */
public class KafkaProducerWrapper {

  private final KafkaProducer<KafkaKey, KafkaValue> producer;
  private final String PROPERTIES_KAFKA_PREFIX = "kafka.";
  private final String PROPERTIES_KAFKA_BOOTSTRAP_KEY = "bootstrap.servers";

  public KafkaProducerWrapper(Props props) {
    Properties properties = setPropertiesFromProp(props);
    if (!properties.containsKey(PROPERTIES_KAFKA_BOOTSTRAP_KEY)) {
      throw new VeniceException("Props key not found: " + PROPERTIES_KAFKA_PREFIX + PROPERTIES_KAFKA_BOOTSTRAP_KEY);
    }
    producer = new KafkaProducer<>(properties);
  }

  /**
   * Sends a message to the Kafka Producer. If everything is set up correctly, it will show up in Kafka log.
   * @param topic - The topic to be sent to.
   * @param key - The key of the message to be sent.
   * @param value - The KafkaValue, which acts as the Kafka payload.
   * */
  public Future<RecordMetadata> sendMessage(String topic, KafkaKey key, KafkaValue value) {
    ProducerRecord<KafkaKey, KafkaValue> kafkaRecord = new ProducerRecord<>(topic, key, value);
    return producer.send(kafkaRecord);
  }

  public void close() {
    producer.close();
  }

  /**
   * This class takes in all properties that begin with "kafka." and emits the rest of the property
   *
   * It omits those properties that do not begin with "kafka."
  */
  public Properties setPropertiesFromProp(Props props) {
    Properties properties = new Properties();
    for (Iterator<String> iterator = props.keySet().iterator(); iterator.hasNext(); ) {
      String keyStr = iterator.next();
      if (keyStr.startsWith(PROPERTIES_KAFKA_PREFIX)) {
        properties.put(keyStr.split(PROPERTIES_KAFKA_PREFIX)[1], props.getString(keyStr));
      }
    }
    return properties;
  }
}
