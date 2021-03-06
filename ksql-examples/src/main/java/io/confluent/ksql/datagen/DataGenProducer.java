/**
 * Copyright 2017 Confluent Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 **/

package io.confluent.ksql.datagen;

import io.confluent.avro.random.generator.Generator;
import io.confluent.connect.avro.AvroData;
import io.confluent.ksql.GenericRow;
import io.confluent.ksql.util.KsqlException;
import io.confluent.ksql.util.Pair;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.stream.Collectors;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData.Record;
import org.apache.avro.generic.GenericRecord;
import org.apache.kafka.clients.producer.Callback;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.serialization.Serializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.apache.kafka.connect.data.Field;
import org.apache.kafka.connect.data.SchemaBuilder;
import org.apache.kafka.connect.data.Struct;

public abstract class DataGenProducer {

  // Max 100 ms between messsages.
  public static final long INTER_MESSAGE_MAX_INTERVAL = 500;

  public void populateTopic(
      final Properties props,
      final Generator generator,
      final String kafkaTopicName,
      final String key,
      final int messageCount,
      final long maxInterval
  ) {
    final Schema avroSchema = generator.schema();
    if (avroSchema.getField(key) == null) {
      throw new IllegalArgumentException("Key field does not exist:" + key);
    }

    final AvroData avroData = new AvroData(1);
    org.apache.kafka.connect.data.Schema ksqlSchema = avroData.toConnectSchema(avroSchema);
    ksqlSchema = getOptionalSchema(ksqlSchema);

    final Serializer<GenericRow> serializer = getSerializer(avroSchema, ksqlSchema, kafkaTopicName);

    final KafkaProducer<String, GenericRow> producer = new KafkaProducer<>(
        props,
        new StringSerializer(),
        serializer
    );

    final SessionManager sessionManager = new SessionManager();

    for (int i = 0; i < messageCount; i++) {

      final Pair<String, GenericRow> genericRowPair = generateOneGenericRow(
          generator, avroData, avroSchema, ksqlSchema, sessionManager, key);

      final ProducerRecord<String, GenericRow> producerRecord = new ProducerRecord<>(
          kafkaTopicName,
          genericRowPair.getLeft(),
          genericRowPair.getRight()
      );
      producer.send(producerRecord,
          new ErrorLoggingCallback(kafkaTopicName,
              genericRowPair.getLeft(),
              genericRowPair.getRight()));

      try {
        final long interval = maxInterval < 0 ? INTER_MESSAGE_MAX_INTERVAL : maxInterval;

        Thread.sleep((long) (interval * Math.random()));
      } catch (final InterruptedException e) {
        // Ignore the exception.
      }
    }
    producer.flush();
    producer.close();
  }


  // For test purpose.
  protected Pair<String, GenericRow> generateOneGenericRow(
      final Generator generator,
      final AvroData avroData,
      final Schema avroSchema,
      final org.apache.kafka.connect.data.Schema ksqlSchema,
      final SessionManager sessionManager,
      final String key) {

    final Object generatedObject = generator.generate();

    if (!(generatedObject instanceof GenericRecord)) {
      throw new RuntimeException(String.format(
          "Expected Avro Random Generator to return instance of GenericRecord, found %s instead",
          generatedObject.getClass().getName()
      ));
    }
    final GenericRecord randomAvroMessage = (GenericRecord) generatedObject;

    final List<Object> genericRowValues = new ArrayList<>();

    SimpleDateFormat timeformatter = null;

    /**
     * Populate the record entries
     */
    String sessionisationValue = null;
    for (final Schema.Field field : avroSchema.getFields()) {

      final boolean isSession = field.schema().getProp("session") != null;
      final boolean isSessionSiblingIntHash =
          field.schema().getProp("session-sibling-int-hash") != null;
      final String timeFormatFromLong = field.schema().getProp("format_as_time");

      if (isSession) {
        final String currentValue = (String) randomAvroMessage.get(field.name());
        final String newCurrentValue = handleSessionisationOfValue(sessionManager, currentValue);
        sessionisationValue = newCurrentValue;

        genericRowValues.add(newCurrentValue);
      } else if (isSessionSiblingIntHash && sessionisationValue != null) {

        // super cheeky hack to link int-ids to session-values - if anything fails then we use
        // the 'avro-gen' randomised version
        handleSessionSiblingField(
            randomAvroMessage,
            genericRowValues,
            sessionisationValue,
            field
        );

      } else if (timeFormatFromLong != null) {
        final Date date = new Date(System.currentTimeMillis());
        if (timeFormatFromLong.equals("unix_long")) {
          genericRowValues.add(date.getTime());
        } else {
          if (timeformatter == null) {
            timeformatter = new SimpleDateFormat(timeFormatFromLong);
          }
          genericRowValues.add(timeformatter.format(date));
        }
      } else {
        final Object value = randomAvroMessage.get(field.name());
        if (value instanceof Record) {
          final Record record = (Record) value;
          final Object ksqlValue = avroData.toConnectData(record.getSchema(), record).value();
          genericRowValues.add(
              getOptionalValue(ksqlSchema.field(field.name()).schema(), ksqlValue));
        } else {
          genericRowValues.add(value);
        }
      }
    }

    final String keyString = avroData.toConnectData(
        randomAvroMessage.getSchema().getField(key).schema(),
        randomAvroMessage.get(key)).value().toString();

    return new Pair<>(keyString, new GenericRow(genericRowValues));
  }

  private static class ErrorLoggingCallback implements Callback {

    private final String topic;
    private final String key;
    private final GenericRow value;

    ErrorLoggingCallback(final String topic, final String key, final GenericRow value) {
      this.topic = topic;
      this.key = key;
      this.value = value;
    }

    @Override
    public void onCompletion(final RecordMetadata metadata, final Exception e) {
      final String keyString = Objects.toString(key);
      final String valueString = Objects.toString(value);

      if (e != null) {
        System.err.println("Error when sending message to topic: '" + topic + "', with key: '"
            + keyString + "', and value: '" + valueString + "'");
        e.printStackTrace(System.err);
      } else {
        System.out.println(keyString + " --> (" + valueString + ") ts:" + metadata.timestamp());
      }
    }
  }

  private void handleSessionSiblingField(
      final GenericRecord randomAvroMessage,
      final List<Object> genericRowValues,
      final String sessionisationValue,
      final Schema.Field field
  ) {
    try {
      final Schema.Type type = field.schema().getType();
      if (type == Schema.Type.INT) {
        genericRowValues.add(mapSessionValueToSibling(sessionisationValue, field));
      } else {
        genericRowValues.add(randomAvroMessage.get(field.name()));
      }
    } catch (final Exception err) {
      genericRowValues.add(randomAvroMessage.get(field.name()));
    }
  }

  Map<String, Integer> sessionMap = new HashMap<>();
  Set<Integer> allocatedIds = new HashSet<>();

  private int mapSessionValueToSibling(final String sessionisationValue, final Schema.Field field) {

    if (!sessionMap.containsKey(sessionisationValue)) {

      final LinkedHashMap properties =
          (LinkedHashMap) field.schema().getObjectProps().get("arg.properties");
      final Integer max = (Integer) ((LinkedHashMap) properties.get("range")).get("max");

      int vvalue = Math.abs(sessionisationValue.hashCode() % max);

      int foundValue = -1;
      // used - search for another
      if (allocatedIds.contains(vvalue)) {
        for (int i = 0; i < max; i++) {
          if (!allocatedIds.contains(i)) {
            foundValue = i;
          }
        }
        if (foundValue == -1) {
          System.out.println(
              "Failed to allocate Id :"
                  + sessionisationValue
                  + ", reusing "
                  + vvalue
          );
          foundValue = vvalue;
        }
        vvalue = foundValue;
      }
      allocatedIds.add(vvalue);
      sessionMap.put(sessionisationValue, vvalue);
    }
    return sessionMap.get(sessionisationValue);

  }

  Set<String> allTokens = new HashSet<String>();

  /**
   * If the sessionId is new Create a Session
   * If the sessionId is active - return the value
   * If the sessionId has expired - use a known token that is not expired
   *
   * @param sessionManager a SessionManager
   * @param currentValue current token
   * @return session token
   */
  private String handleSessionisationOfValue(
      final SessionManager sessionManager,
      final String currentValue) {

    // superset of all values
    allTokens.add(currentValue);

    /**
     * handle known sessions
     */
    if (sessionManager.isActive(currentValue)) {
      if (sessionManager.isExpired(currentValue)) {
        sessionManager.isActiveAndExpire(currentValue);
        return currentValue;
      } else {
        return currentValue;
      }
    }
    /**
     * If session count maxed out - reuse session tokens
     */
    if (sessionManager.getActiveSessionCount() > sessionManager.getMaxSessions()) {
      return sessionManager.getRandomActiveToken();
    }

    /**
     * Force expiring tokens to expire
     */
    final String expired = sessionManager.getActiveSessionThatHasExpired();
    if (expired != null) {
      return expired;
    }

    /**
     * Use accummulated SessionTokens-tokens, or recycle old tokens or blow-up
     */
    String value = null;
    for (final String token : allTokens) {
      if (value == null) {
        if (!sessionManager.isActive(token) && !sessionManager.isExpired(token)) {
          value = token;
        }
      }
    }

    if (value != null) {
      sessionManager.newSession(value);
    } else {
      value = sessionManager.recycleOldestExpired();
      if (value == null) {
        throw new RuntimeException(
            "Ran out of tokens to rejuice - increase session-duration (300s), reduce-number of "
                + "sessions(5), number of tokens in the avro template");
      }
      sessionManager.newSession(value);
      return value;
    }
    return currentValue;
  }

  private String getRandomToken(final Set<String> collected) {
    if (collected.size() == 0) {
      return null;
    }
    final List<String> values = new ArrayList<>(collected);
    final int index = (int) (Math.random() * values.size());
    final String value = values.remove(index);
    collected.remove(value);
    return value;
  }

  protected abstract Serializer<GenericRow> getSerializer(
      Schema avroSchema,
      org.apache.kafka.connect.data.Schema kafkaSchema,
      String topicName
  );

  private org.apache.kafka.connect.data.Schema getOptionalSchema(
      final org.apache.kafka.connect.data.Schema schema) {
    switch (schema.type()) {
      case BOOLEAN:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_BOOLEAN_SCHEMA;
      case INT32:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_INT32_SCHEMA;
      case INT64:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_INT64_SCHEMA;
      case FLOAT64:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_FLOAT64_SCHEMA;
      case STRING:
        return org.apache.kafka.connect.data.Schema.OPTIONAL_STRING_SCHEMA;
      case ARRAY:
        return SchemaBuilder.array(getOptionalSchema(schema.valueSchema())).optional().build();
      case MAP:
        return SchemaBuilder.map(
            getOptionalSchema(schema.keySchema()),
            getOptionalSchema(schema.valueSchema()))
            .optional().build();
      case STRUCT:
        final SchemaBuilder schemaBuilder = SchemaBuilder.struct();
        for (final Field field : schema.fields()) {
          schemaBuilder.field(field.name(), getOptionalSchema(field.schema()));
        }
        return schemaBuilder.optional().build();
      default:
        throw new KsqlException("Unsupported type: " + schema);
    }
  }

  private Object getOptionalValue(
      final org.apache.kafka.connect.data.Schema schema,
      final Object value) {
    switch (schema.type()) {
      case BOOLEAN:
      case INT32:
      case INT64:
      case FLOAT64:
      case STRING:
        return value;
      case ARRAY:
        final List<?> list = (List<?>) value;
        return list.stream().map(listItem -> getOptionalValue(schema.valueSchema(), listItem))
            .collect(Collectors.toList());
      case MAP:
        final Map<?, ?> map = (Map<?, ?>) value;
        return map.entrySet().stream()
            .collect(Collectors.toMap(
                k -> getOptionalValue(schema.keySchema(), k),
                v -> getOptionalValue(schema.valueSchema(), v)
            ));
      case STRUCT:
        final Struct struct = (Struct) value;
        final Struct optionalStruct = new Struct(getOptionalSchema(schema));
        for (final Field field : schema.fields()) {
          optionalStruct
              .put(field.name(), getOptionalValue(field.schema(), struct.get(field.name())));
        }
        return optionalStruct;

      default:
        throw new KsqlException("Invalid value schema: " + schema + ", value = " + value);
    }
  }
}
