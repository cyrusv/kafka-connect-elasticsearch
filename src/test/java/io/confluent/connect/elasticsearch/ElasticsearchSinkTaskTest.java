/*
 * Copyright 2018 Confluent Inc.
 *
 * Licensed under the Confluent Community License; you may not use this file
 * except in compliance with the License.  You may obtain a copy of the License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.connect.elasticsearch;

import com.carrotsearch.randomizedtesting.annotations.ThreadLeakScope;

import io.confluent.connect.elasticsearch.jest.JestElasticsearchClient;
import io.searchbox.client.JestClient;
import io.searchbox.client.JestClientFactory;
import org.apache.kafka.common.network.Mode;
import org.apache.kafka.test.TestSslUtils;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.config.SslConfigs;
import org.apache.kafka.connect.data.Schema;
import org.apache.kafka.connect.data.Struct;
import org.apache.kafka.connect.sink.SinkRecord;
import org.elasticsearch.test.ESIntegTestCase;
import org.elasticsearch.test.InternalTestCluster;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Collection;
import java.util.ArrayList;
import java.util.Collections;

import static io.confluent.connect.elasticsearch.ElasticsearchSinkConnectorConfig.CONNECTION_SSL_CONFIG;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;


@ThreadLeakScope(ThreadLeakScope.Scope.NONE)
public class ElasticsearchSinkTaskTest extends ElasticsearchSinkTestBase {

  private JestClient jestClient;
  private JestClientFactory jestClientFactory;


  private static final String TOPIC_IN_CAPS = "AnotherTopicInCaps";
  private static final int PARTITION_113 = 113;
  private static final TopicPartition TOPIC_IN_CAPS_PARTITION = new TopicPartition(TOPIC_IN_CAPS, PARTITION_113);

  private Map<String, String> createProps() {
    Map<String, String> props = new HashMap<>();
    props.put(ElasticsearchSinkConnectorConfig.TYPE_NAME_CONFIG, TYPE);
    props.put(ElasticsearchSinkConnectorConfig.CONNECTION_URL_CONFIG, "localhost");
    props.put(ElasticsearchSinkConnectorConfig.KEY_IGNORE_CONFIG, "true");
    return props;
  }

  @Test
  public void testPutAndFlush() throws Exception {
    InternalTestCluster cluster = ESIntegTestCase.internalCluster();
    cluster.ensureAtLeastNumDataNodes(3);
    Map<String, String> props = createProps();

    ElasticsearchSinkTask task = new ElasticsearchSinkTask();
    task.start(props, client);
    task.open(new HashSet<>(Arrays.asList(TOPIC_PARTITION, TOPIC_PARTITION2, TOPIC_PARTITION3)));

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    Collection<SinkRecord> records = new ArrayList<>();
    SinkRecord sinkRecord = new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, 0);
    records.add(sinkRecord);

    sinkRecord = new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, 1);
    records.add(sinkRecord);

    task.put(records);
    task.flush(null);

    refresh();

    verifySearchResults(records, true, false);
  }

  @Test
  public void testSsl() throws Exception {

    InternalTestCluster cluster = ESIntegTestCase.internalCluster();
    cluster.ensureAtLeastNumDataNodes(3);

    Map<String, String> props = createProps();
    props.put(ElasticsearchSinkConnectorConfig.CONNECTION_URL_CONFIG, "https://localhost:" + getPort());
    Map<String, Object> newMap = new HashMap<>();
    for (Map.Entry<String, Object> e : TestSslUtils.createSslConfig(true,
        true,
        Mode.SERVER,
        trustStoreFile,
        "certalias").entrySet()) {
      newMap.put(CONNECTION_SSL_CONFIG + e.getKey(), e.getValue());
    }
    props.putAll(new HashMap(newMap));

    jestClient = mock(JestClient.class);
    jestClientFactory = mock(JestClientFactory.class);
    when(jestClientFactory.getObject()).thenReturn(jestClient);

    ElasticsearchSinkTask task = new ElasticsearchSinkTask();

    client = new JestElasticsearchClient(props);
    task.start(props, client);
    task.open(new HashSet<>(Arrays.asList(TOPIC_PARTITION, TOPIC_PARTITION2, TOPIC_PARTITION3)));

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    Collection<SinkRecord> records = new ArrayList<>();
    SinkRecord sinkRecord =
        new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, 0);
    records.add(sinkRecord);

    sinkRecord = new SinkRecord(TOPIC, PARTITION, Schema.STRING_SCHEMA, key, schema, record, 1);
    records.add(sinkRecord);

    task.put(records);
    task.flush(null);

    refresh();

    verifySearchResults(records, true, false);

  }

  @Test
  public void testCreateAndWriteToIndexForTopicWithUppercaseCharacters() {
    // We should as well test that writing a record with a previously un seen record will create
    // an index following the required elasticsearch requirements of lowercasing.
    InternalTestCluster cluster = ESIntegTestCase.internalCluster();
    cluster.ensureAtLeastNumDataNodes(3);
    Map<String, String> props = createProps();

    ElasticsearchSinkTask task = new ElasticsearchSinkTask();

    String key = "key";
    Schema schema = createSchema();
    Struct record = createRecord(schema);

    SinkRecord sinkRecord = new SinkRecord(TOPIC_IN_CAPS,
            PARTITION_113,
            Schema.STRING_SCHEMA,
            key,
            schema,
            record,
            0 );

    try {
      task.start(props, client);
      task.open(new HashSet<>(Collections.singletonList(TOPIC_IN_CAPS_PARTITION)));
      task.put(Collections.singleton(sinkRecord));
    } catch (Exception ex) {
      fail("A topic name not in lowercase can not be used as index name in Elasticsearch");
    } finally {
      task.stop();
    }
  }
}
