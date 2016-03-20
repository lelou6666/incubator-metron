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
package org.apache.metron.dataloads.hbase.mr;

import com.google.common.collect.Iterables;
import org.apache.hadoop.conf.Configuration;
import org.apache.hadoop.hbase.HBaseTestingUtility;
import org.apache.hadoop.hbase.client.HTable;
import org.apache.hadoop.hbase.util.Bytes;
import org.apache.hadoop.mapreduce.Job;
import org.apache.metron.dataloads.bulk.LeastRecentlyUsedPruner;
import org.apache.metron.hbase.converters.threatintel.ThreatIntelValue;
import org.apache.metron.reference.lookup.LookupKey;
import org.apache.metron.reference.lookup.accesstracker.BloomAccessTracker;
import org.apache.metron.reference.lookup.accesstracker.PersistentAccessTracker;
import org.apache.metron.hbase.converters.threatintel.ThreatIntelKey;
import org.apache.metron.hbase.converters.threatintel.ThreatIntelConverter;
import org.apache.metron.threatintel.hbase.ThreatIntelLookup;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class LeastRecentlyUsedPrunerIntegrationTest {
    /** The test util. */
    private HBaseTestingUtility testUtil;

    /** The test table. */
    private HTable testTable;
    private HTable atTable;
    String tableName = "malicious_domains";
    String cf = "cf";
    String atTableName = "access_trackers";
    String atCF= "cf";
    Configuration config = null;
    @Before
    public void setup() throws Exception {
        Map.Entry<HBaseTestingUtility, Configuration> kv = HBaseUtil.INSTANCE.create(true);
        config = kv.getValue();
        testUtil = kv.getKey();
        testTable = testUtil.createTable(Bytes.toBytes(tableName), Bytes.toBytes(cf));
        atTable = testUtil.createTable(Bytes.toBytes(atTableName), Bytes.toBytes(atCF));
    }
    @After
    public void teardown() throws Exception {
        HBaseUtil.INSTANCE.teardown(testUtil);
    }
    public List<LookupKey> getKeys(int start, int end) {
        List<LookupKey> keys = new ArrayList<>();
        for(int i = start;i < end;++i) {
            keys.add(new ThreatIntelKey("key-" + i));
        }
        return keys;
    }
    @Test
    public void test() throws Exception {
        long ts = System.currentTimeMillis();
        BloomAccessTracker bat = new BloomAccessTracker("tracker1", 100, 0.03);
        PersistentAccessTracker pat = new PersistentAccessTracker(tableName, "0", atTable, atCF, bat, 0L);
        ThreatIntelLookup lookup = new ThreatIntelLookup(testTable, cf, pat);
        List<LookupKey> goodKeysHalf = getKeys(0, 5);
        List<LookupKey> goodKeysOtherHalf = getKeys(5, 10);
        Iterable<LookupKey> goodKeys = Iterables.concat(goodKeysHalf, goodKeysOtherHalf);
        List<LookupKey> badKey = getKeys(10, 11);
        ThreatIntelConverter converter = new ThreatIntelConverter();
        for(LookupKey k : goodKeysHalf) {
            testTable.put(converter.toPut(cf, (ThreatIntelKey) k
                                            , new ThreatIntelValue(
                                                  new HashMap<String, String>() {{
                                                    put("k", "dummy");
                                                    }}
                                                  )
                                          )
                         );
            Assert.assertTrue(lookup.exists((ThreatIntelKey)k, testTable, true));
        }
        pat.persist(true);
        for(LookupKey k : goodKeysOtherHalf) {
            testTable.put(converter.toPut(cf, (ThreatIntelKey) k
                                            , new ThreatIntelValue(new HashMap<String, String>() {{
                                                    put("k", "dummy");
                                                    }}
                                                                  )
                                         )
                         );
            Assert.assertTrue(lookup.exists((ThreatIntelKey)k, testTable, true));
        }
        testUtil.flush();
        Assert.assertFalse(lookup.getAccessTracker().hasSeen(goodKeysHalf.get(0)));
        for(LookupKey k : goodKeysOtherHalf) {
            Assert.assertTrue(lookup.getAccessTracker().hasSeen(k));
        }
        pat.persist(true);
        {
            testTable.put(converter.toPut(cf, (ThreatIntelKey) badKey.get(0)
                    , new ThreatIntelValue(new HashMap<String, String>() {{
                        put("k", "dummy");
                    }}
                    )
                    )
            );
        }
        testUtil.flush();
        Assert.assertFalse(lookup.getAccessTracker().hasSeen(badKey.get(0)));


        Job job = LeastRecentlyUsedPruner.createJob(config, tableName, cf, atTableName, atCF, ts);
        Assert.assertTrue(job.waitForCompletion(true));
        for(LookupKey k : goodKeys) {
            Assert.assertTrue(lookup.exists((ThreatIntelKey)k, testTable, true));
        }
        for(LookupKey k : badKey) {
            Assert.assertFalse(lookup.exists((ThreatIntelKey)k, testTable, true));
        }

    }

}
