/**
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * <p/>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p/>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.metron.integration;

public class YafIntegrationTest extends ParserIntegrationTest {

  @Override
  public String getFluxPath() {
    return "src/main/resources/Metron_Configs/topologies/yaf/test.yaml";
  }

  @Override
  public String getSampleInputPath() {
    return "src/main/resources/SampleInput/YafExampleOutput";
  }

  @Override
  public String getSampleParsedPath() {
    return "src/main/resources/SampleParsed/YafExampleParsed";
  }

  @Override
  public String getSourceType() {
    return "yaf";
  }

  @Override
  public String getSourceConfig() {
    return "{\"index\": \"yaf\"," +
            " \"batchSize\": 5," +
            " \"enrichmentFieldMap\":" +
            "  {" +
            "    \"geo\": [\"ip_src_addr\", \"ip_dst_addr\"]," +
            "    \"host\": [\"ip_src_addr\", \"ip_dst_addr\"]" +
            "  }," +
            "  \"threatIntelFieldMap\":" +
            "  {" +
            "    \"ip\": [\"ip_src_addr\", \"ip_dst_addr\"]" +
            "  }" +
            "}";
  }

  @Override
  public String getFluxTopicProperty() {
    return "spout.kafka.topic.yaf";
  }
}
