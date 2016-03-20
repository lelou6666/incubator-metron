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

import com.fasterxml.jackson.core.type.TypeReference;
import com.google.common.base.*;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.client.HTableInterface;
import org.apache.metron.Constants;
import org.apache.metron.hbase.TableProvider;
import org.apache.metron.hbase.converters.threatintel.ThreatIntelKey;
import org.apache.metron.hbase.converters.threatintel.ThreatIntelValue;
import org.apache.metron.integration.util.TestUtils;
import org.apache.metron.integration.util.UnitTestHelper;
import org.apache.metron.integration.util.integration.ComponentRunner;
import org.apache.metron.integration.util.integration.Processor;
import org.apache.metron.integration.util.integration.ReadinessState;
import org.apache.metron.integration.util.integration.components.ElasticSearchComponent;
import org.apache.metron.integration.util.integration.components.FluxTopologyComponent;
import org.apache.metron.integration.util.integration.components.KafkaWithZKComponent;
import org.apache.metron.integration.util.mock.MockGeoAdapter;
import org.apache.metron.integration.util.mock.MockHTable;
import org.apache.metron.integration.util.threatintel.ThreatIntelHelper;
import org.apache.metron.reference.lookup.LookupKV;
import org.apache.metron.utils.SourceConfigUtils;
import org.junit.Assert;
import org.junit.Test;
import org.apache.metron.utils.JSONUtils;

import javax.annotation.Nullable;
import java.io.*;
import java.text.SimpleDateFormat;
import java.util.*;

public class EnrichmentIntegrationTest {
  private static final String SRC_IP = "ip_src_addr";
  private static final String DST_IP = "ip_dst_addr";
  private String fluxPath = "src/main/resources/Metron_Configs/topologies/enrichment/test.yaml";
  private String indexDir = "target/elasticsearch";
  private String hdfsDir = "target/enrichmentIntegrationTest/hdfs";
  private String sampleParsedPath = "src/main/resources/SampleParsed/YafExampleParsed";
  private String sampleIndexedPath = "src/main/resources/SampleIndexed/YafIndexed";
  private Map<String, String> sourceConfigs = new HashMap<>();

  public static class Provider implements TableProvider, Serializable {
    MockHTable.Provider  provider = new MockHTable.Provider();
    @Override
    public HTableInterface getTable(Configuration config, String tableName) throws IOException {
      return provider.getTable(config, tableName);
    }
  }

  public static void cleanHdfsDir(String hdfsDirStr) {
    File hdfsDir = new File(hdfsDirStr);
    Stack<File> fs = new Stack<>();
    if(hdfsDir.exists()) {
      fs.push(hdfsDir);
      while(!fs.empty()) {
        File f = fs.pop();
        if (f.isDirectory()) {
          for(File child : f.listFiles()) {
            fs.push(child);
          }
        }
        else {
          if (f.getName().startsWith("enrichment") || f.getName().endsWith(".json")) {
            f.delete();
          }
        }
      }
    }
  }

  public static List<Map<String, Object> > readDocsFromDisk(String hdfsDirStr) throws IOException {
    List<Map<String, Object>> ret = new ArrayList<>();
    File hdfsDir = new File(hdfsDirStr);
    Stack<File> fs = new Stack<>();
    if(hdfsDir.exists()) {
      fs.push(hdfsDir);
      while(!fs.empty()) {
        File f = fs.pop();
        if(f.isDirectory()) {
          for (File child : f.listFiles()) {
            fs.push(child);
          }
        }
        else {
          System.out.println("Processed " + f);
          if (f.getName().startsWith("enrichment") || f.getName().endsWith(".json")) {
            List<byte[]> data = TestUtils.readSampleData(f.getPath());
            Iterables.addAll(ret, Iterables.transform(data, new Function<byte[], Map<String, Object>>() {
              @Nullable
              @Override
              public Map<String, Object> apply(@Nullable byte[] bytes) {
                String s = new String(bytes);
                try {
                  return JSONUtils.INSTANCE.load(s, new TypeReference<Map<String, Object>>() {
                  });
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }
            }));
          }
        }
      }
    }
    return ret;
  }


  @Test
  public void test() throws Exception {
    cleanHdfsDir(hdfsDir);
    final String dateFormat = "yyyy.MM.dd.hh";
    final String index = "test_index_" + new SimpleDateFormat(dateFormat).format(new Date());
    String yafConfig = "{\n" +
            "  \"index\": \"test\",\n" +
            "  \"batchSize\": 5,\n" +
            "  \"enrichmentFieldMap\":\n" +
            "  {\n" +
            "    \"geo\": [\"" + SRC_IP + "\", \"" + DST_IP + "\"],\n" +
            "    \"host\": [\"" + SRC_IP + "\", \"" + DST_IP + "\"]\n" +
            "  },\n" +
            "  \"threatIntelFieldMap\":\n" +
            "  {\n" +
            "    \"ip\": [\"" + SRC_IP + "\", \"" + DST_IP + "\"]\n" +
            "  }\n" +
            "}";
    sourceConfigs.put("test", yafConfig);
    final List<byte[]> inputMessages = TestUtils.readSampleData(sampleParsedPath);
    final String cf = "cf";
    final String trackerHBaseTable = "tracker";
    final String ipThreatIntelTable = "ip_threat_intel";
    final Properties topologyProperties = new Properties() {{
      setProperty("org.apache.metron.enrichment.host.known_hosts", "[{\"ip\":\"10.1.128.236\", \"local\":\"YES\", \"type\":\"webserver\", \"asset_value\" : \"important\"},\n" +
              "{\"ip\":\"10.1.128.237\", \"local\":\"UNKNOWN\", \"type\":\"unknown\", \"asset_value\" : \"important\"},\n" +
              "{\"ip\":\"10.60.10.254\", \"local\":\"YES\", \"type\":\"printer\", \"asset_value\" : \"important\"},\n" +
              "{\"ip\":\"10.0.2.15\", \"local\":\"YES\", \"type\":\"printer\", \"asset_value\" : \"important\"}]");
      setProperty("hbase.provider.impl","" + Provider.class.getName());
      setProperty("threat.intel.tracker.table", trackerHBaseTable);
      setProperty("threat.intel.tracker.cf", cf);
      setProperty("threat.intel.ip.table", ipThreatIntelTable);
      setProperty("threat.intel.ip.cf", cf);
      setProperty("es.clustername", "metron");
      setProperty("es.port", "9300");
      setProperty("es.ip", "localhost");
      setProperty("index.date.format", dateFormat);
      setProperty("index.hdfs.output", hdfsDir);
    }};
    final KafkaWithZKComponent kafkaComponent = new KafkaWithZKComponent().withTopics(new ArrayList<KafkaWithZKComponent.Topic>() {{
      add(new KafkaWithZKComponent.Topic(Constants.ENRICHMENT_TOPIC, 1));
    }})
            .withPostStartCallback(new Function<KafkaWithZKComponent, Void>() {
              @Nullable
              @Override
              public Void apply(@Nullable KafkaWithZKComponent kafkaWithZKComponent) {
                topologyProperties.setProperty("kafka.zk", kafkaWithZKComponent.getZookeeperConnect());
                try {
                  for(String sourceType: sourceConfigs.keySet()) {
                    SourceConfigUtils.writeToZookeeper(sourceType, sourceConfigs.get(sourceType).getBytes(), kafkaWithZKComponent.getZookeeperConnect());
                  }
                } catch (Exception e) {
                  e.printStackTrace();
                }
                return null;
              }
            });

    ElasticSearchComponent esComponent = new ElasticSearchComponent.Builder()
            .withHttpPort(9211)
            .withIndexDir(new File(indexDir))
            .build();

    //create MockHBaseTables
    final MockHTable trackerTable = (MockHTable)MockHTable.Provider.addToCache(trackerHBaseTable, cf);
    final MockHTable ipTable = (MockHTable)MockHTable.Provider.addToCache(ipThreatIntelTable, cf);
    ThreatIntelHelper.INSTANCE.load(ipTable, cf, new ArrayList<LookupKV<ThreatIntelKey, ThreatIntelValue>>(){{
      add(new LookupKV<>(new ThreatIntelKey("10.0.2.3"), new ThreatIntelValue(new HashMap<String, String>())));
    }});

    FluxTopologyComponent fluxComponent = new FluxTopologyComponent.Builder()
            .withTopologyLocation(new File(fluxPath))
            .withTopologyName("test")
            .withTopologyProperties(topologyProperties)
            .build();

    UnitTestHelper.verboseLogging();
    ComponentRunner runner = new ComponentRunner.Builder()
            .withComponent("kafka", kafkaComponent)
            .withComponent("elasticsearch", esComponent)
            .withComponent("storm", fluxComponent)
            .withMillisecondsBetweenAttempts(10000)
            .withNumRetries(10)
            .build();
    runner.start();
    try {
      fluxComponent.submitTopology();
      kafkaComponent.writeMessages(Constants.ENRICHMENT_TOPIC, inputMessages);
      List<Map<String, Object>> docs =
              runner.process(new Processor<List<Map<String, Object>>>() {
                List<Map<String, Object>> docs = null;

                public ReadinessState process(ComponentRunner runner) {
                  ElasticSearchComponent elasticSearchComponent = runner.getComponent("elasticsearch", ElasticSearchComponent.class);
                  if (elasticSearchComponent.hasIndex(index)) {
                    List<Map<String, Object>> docsFromDisk;
                    try {
                      docs = elasticSearchComponent.getAllIndexedDocs(index, "test_doc");
                      docsFromDisk = readDocsFromDisk(hdfsDir);
                      System.out.println(docs.size() + " vs " + inputMessages.size() + " vs " + docsFromDisk.size());
                    } catch (IOException e) {
                      throw new IllegalStateException("Unable to retrieve indexed documents.", e);
                    }
                    if (docs.size() < inputMessages.size() || docs.size() != docsFromDisk.size()) {
                      return ReadinessState.NOT_READY;
                    } else {
                      return ReadinessState.READY;
                    }
                  } else {
                    return ReadinessState.NOT_READY;
                  }
                }

                public List<Map<String, Object>> getResult() {
                  return docs;
                }
              });


      Assert.assertEquals(inputMessages.size(), docs.size());

      for (Map<String, Object> doc : docs) {
        baseValidation(doc);
        hostEnrichmentValidation(doc);
        geoEnrichmentValidation(doc);
        threatIntelValidation(doc);

      }
      List<Map<String, Object>> docsFromDisk = readDocsFromDisk(hdfsDir);
      Assert.assertEquals(docsFromDisk.size(), docs.size()) ;

      Assert.assertEquals(new File(hdfsDir).list().length, 1);
      Assert.assertEquals(new File(hdfsDir).list()[0], "test_doc");
      for (Map<String, Object> doc : docsFromDisk) {
        baseValidation(doc);
        hostEnrichmentValidation(doc);
        geoEnrichmentValidation(doc);
        threatIntelValidation(doc);

      }
    }
    finally {
      cleanHdfsDir(hdfsDir);
      runner.stop();
    }
  }

  public static void baseValidation(Map<String, Object> jsonDoc) {
    assertEnrichmentsExists("threatintels.", setOf("ip"), jsonDoc.keySet());
    assertEnrichmentsExists("enrichments.", setOf("geo", "host"), jsonDoc.keySet());
    for(Map.Entry<String, Object> kv : jsonDoc.entrySet()) {
      //ensure no values are empty.
      Assert.assertTrue(kv.getValue().toString().length() > 0);
    }
    //ensure we always have a source ip and destination ip
    Assert.assertNotNull(jsonDoc.get(SRC_IP));
    Assert.assertNotNull(jsonDoc.get(DST_IP));
  }

  private static class EvaluationPayload {
    Map<String, Object> indexedDoc;
    String key;
    public EvaluationPayload(Map<String, Object> indexedDoc, String key) {
      this.indexedDoc = indexedDoc;
      this.key = key;
    }
  }

  private static enum HostEnrichments implements Predicate<EvaluationPayload>{
    LOCAL_LOCATION(new Predicate<EvaluationPayload>() {

      @Override
      public boolean apply(@Nullable EvaluationPayload evaluationPayload) {
        return evaluationPayload.indexedDoc.get("enrichments.host." + evaluationPayload.key + ".known_info.local").equals("YES");
      }
    })
    ,UNKNOWN_LOCATION(new Predicate<EvaluationPayload>() {

      @Override
      public boolean apply(@Nullable EvaluationPayload evaluationPayload) {
        return evaluationPayload.indexedDoc.get("enrichments.host." + evaluationPayload.key + ".known_info.local").equals("UNKNOWN");
      }
    })
    ,IMPORTANT(new Predicate<EvaluationPayload>() {
      @Override
      public boolean apply(@Nullable EvaluationPayload evaluationPayload) {
        return evaluationPayload.indexedDoc.get("enrichments.host." + evaluationPayload.key + ".known_info.asset_value").equals("important");
      }
    })
    ,PRINTER_TYPE(new Predicate<EvaluationPayload>() {
      @Override
      public boolean apply(@Nullable EvaluationPayload evaluationPayload) {
        return evaluationPayload.indexedDoc.get("enrichments.host." + evaluationPayload.key + ".known_info.type").equals("printer");
      }
    })
    ,WEBSERVER_TYPE(new Predicate<EvaluationPayload>() {
      @Override
      public boolean apply(@Nullable EvaluationPayload evaluationPayload) {
        return evaluationPayload.indexedDoc.get("enrichments.host." + evaluationPayload.key + ".known_info.type").equals("webserver");
      }
    })
    ,UNKNOWN_TYPE(new Predicate<EvaluationPayload>() {
      @Override
      public boolean apply(@Nullable EvaluationPayload evaluationPayload) {
        return evaluationPayload.indexedDoc.get("enrichments.host." + evaluationPayload.key + ".known_info.type").equals("unknown");
      }
    })
    ;

    Predicate<EvaluationPayload> _predicate;
    HostEnrichments(Predicate<EvaluationPayload> predicate) {
      this._predicate = predicate;
    }

    public boolean apply(EvaluationPayload payload) {
      return _predicate.apply(payload);
    }

  }

  private static void assertEnrichmentsExists(String topLevel, Set<String> expectedEnrichments, Set<String> keys) {
    for(String key : keys) {
      if(key.startsWith(topLevel)) {
        String secondLevel = Iterables.get(Splitter.on(".").split(key), 1);
        String message = "Found an enrichment/threat intel (" + secondLevel + ") that I didn't expect (expected enrichments :"
                       + Joiner.on(",").join(expectedEnrichments) + "), but it was not there.  If you've created a new"
                       + " enrichment, then please add a validation method to this unit test.  Otherwise, it's a solid error"
                       + " and should be investigated.";
        Assert.assertTrue( message, expectedEnrichments.contains(secondLevel));
      }
    }
  }
  private static void threatIntelValidation(Map<String, Object> indexedDoc) {
    if(keyPatternExists("threatintels.", indexedDoc)) {
      //if we have any threat intel messages, we want to tag is_alert to true
      Assert.assertEquals(indexedDoc.get("is_alert"), "true");
    }
    else {
      //For YAF this is the case, but if we do snort later on, this will be invalid.
      Assert.assertNull(indexedDoc.get("is_alert"));
    }
    //ip threat intels
    if(keyPatternExists("threatintels.ip.", indexedDoc)) {
      if(indexedDoc.get(SRC_IP).equals("10.0.2.3")) {
        Assert.assertEquals(indexedDoc.get("threatintels.ip." + SRC_IP + ".ip_threat_intel"), "alert");
      }
      else if(indexedDoc.get(DST_IP).equals("10.0.2.3")) {
        Assert.assertEquals(indexedDoc.get("threatintels.ip." + DST_IP + ".ip_threat_intel"), "alert");
      }
      else {
        Assert.fail("There was a threat intels that I did not expect.");
      }
    }

  }

  private static void geoEnrichmentValidation(Map<String, Object> indexedDoc) {
    //should have geo enrichment on every message due to mock geo adapter
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + DST_IP + ".location_point"), MockGeoAdapter.DEFAULT_LOCATION_POINT);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + SRC_IP +".location_point"), MockGeoAdapter.DEFAULT_LOCATION_POINT);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + DST_IP + ".longitude"), MockGeoAdapter.DEFAULT_LONGITUDE);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + SRC_IP + ".longitude"), MockGeoAdapter.DEFAULT_LONGITUDE);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + DST_IP + ".city"), MockGeoAdapter.DEFAULT_CITY);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + SRC_IP + ".city"), MockGeoAdapter.DEFAULT_CITY);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + DST_IP + ".latitude"), MockGeoAdapter.DEFAULT_LATITUDE);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + SRC_IP + ".latitude"), MockGeoAdapter.DEFAULT_LATITUDE);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + DST_IP + ".country"), MockGeoAdapter.DEFAULT_COUNTRY);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + SRC_IP + ".country"), MockGeoAdapter.DEFAULT_COUNTRY);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + DST_IP + ".dmaCode"), MockGeoAdapter.DEFAULT_DMACODE);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + SRC_IP + ".dmaCode"), MockGeoAdapter.DEFAULT_DMACODE);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + DST_IP + ".postalCode"), MockGeoAdapter.DEFAULT_POSTAL_CODE);
    Assert.assertEquals(indexedDoc.get("enrichments.geo." + SRC_IP + ".postalCode"), MockGeoAdapter.DEFAULT_POSTAL_CODE);
  }

  private static void hostEnrichmentValidation(Map<String, Object> indexedDoc) {
    boolean enriched = false;
    //important local printers
    {
      Set<String> ips = setOf("10.0.2.15", "10.60.10.254");
      if (ips.contains(indexedDoc.get(SRC_IP))) {
        //this is a local, important, printer
        Assert.assertTrue(Predicates.and(HostEnrichments.LOCAL_LOCATION
                ,HostEnrichments.IMPORTANT
                ,HostEnrichments.PRINTER_TYPE
                ).apply(new EvaluationPayload(indexedDoc, SRC_IP))
        );
        enriched = true;
      }
      if (ips.contains(indexedDoc.get(DST_IP))) {
        Assert.assertTrue(Predicates.and(HostEnrichments.LOCAL_LOCATION
                ,HostEnrichments.IMPORTANT
                ,HostEnrichments.PRINTER_TYPE
                ).apply(new EvaluationPayload(indexedDoc, DST_IP))
        );
        enriched = true;
      }
    }
    //important local webservers
    {
      Set<String> ips = setOf("10.1.128.236");
      if (ips.contains(indexedDoc.get(SRC_IP))) {
        //this is a local, important, printer
        Assert.assertTrue(Predicates.and(HostEnrichments.LOCAL_LOCATION
                ,HostEnrichments.IMPORTANT
                ,HostEnrichments.WEBSERVER_TYPE
                ).apply(new EvaluationPayload(indexedDoc, SRC_IP))
        );
        enriched = true;
      }
      if (ips.contains(indexedDoc.get(DST_IP))) {
        Assert.assertTrue(Predicates.and(HostEnrichments.LOCAL_LOCATION
                ,HostEnrichments.IMPORTANT
                ,HostEnrichments.WEBSERVER_TYPE
                ).apply(new EvaluationPayload(indexedDoc, DST_IP))
        );
        enriched = true;
      }
    }
    if(!enriched) {
      Assert.assertFalse(keyPatternExists("enrichments.host", indexedDoc));
    }
  }


  private static boolean keyPatternExists(String pattern, Map<String, Object> indexedObj) {
    for(String k : indexedObj.keySet()) {
      if(k.startsWith(pattern)) {
        return true;
      }
    }
    return false;
  }
  private static Set<String> setOf(String... items) {
    Set<String> ret = new HashSet<>();
    for(String item : items) {
      ret.add(item);
    }
    return ret;
  }

}
