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

package org.apache.metron.dataloads.extractor.csv;

import org.apache.metron.hbase.converters.threatintel.ThreatIntelKey;
import org.apache.metron.hbase.converters.threatintel.ThreatIntelValue;
import org.apache.metron.reference.lookup.LookupKey;
import org.apache.metron.reference.lookup.LookupValue;

import java.util.Map;

public enum LookupConverters {

    THREAT_INTEL(new LookupConverter() {
        @Override
        public LookupKey toKey(String indicator) {
            return new ThreatIntelKey(indicator);
        }

        @Override
        public LookupValue toValue(Map<String, String> metadata) {
            return new ThreatIntelValue(metadata);
        }
    })
    ;
    LookupConverter converter;
    LookupConverters(LookupConverter converter) {
        this.converter = converter;
    }
    public LookupConverter getConverter() {
        return converter;
    }

    public static LookupConverter getConverter(String name) {
        try {
            return LookupConverters.valueOf(name).getConverter();
        }
        catch(Throwable t) {
            try {
                return (LookupConverter) Class.forName(name).newInstance();
            } catch (InstantiationException e) {
                throw new IllegalStateException("Unable to parse " + name, e);
            } catch (IllegalAccessException e) {
                throw new IllegalStateException("Unable to parse " + name, e);
            } catch (ClassNotFoundException e) {
                throw new IllegalStateException("Unable to parse " + name, e);
            }
        }
    }

}
