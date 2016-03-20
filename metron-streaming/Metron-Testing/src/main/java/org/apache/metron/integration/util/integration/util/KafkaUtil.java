/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.metron.integration.util.integration.util;


import kafka.consumer.ConsumerIterator;
import kafka.consumer.KafkaStream;
import kafka.javaapi.producer.Producer;
import kafka.producer.KeyedMessage;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class KafkaUtil {
  public static <K,V> void send(Producer<K,V> producer, K key, V value, String topic) {
    producer.send(new KeyedMessage<>(topic, key,value));
  }

  public static <K,V> void send(Producer<K,V> producer, Iterable<Map.Entry<K,V>> messages, String topic) {
    for(Map.Entry<K,V> kv : messages) {
      send(producer, kv.getKey(), kv.getValue(), topic);
    }
  }

}