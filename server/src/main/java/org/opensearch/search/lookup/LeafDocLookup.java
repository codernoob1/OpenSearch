/*
 * SPDX-License-Identifier: Apache-2.0
 *
 * The OpenSearch Contributors require contributions made to
 * this file be licensed under the Apache-2.0 license or a
 * compatible open source license.
 */

/*
 * Licensed to Elasticsearch under one or more contributor
 * license agreements. See the NOTICE file distributed with
 * this work for additional information regarding copyright
 * ownership. Elasticsearch licenses this file to you under
 * the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
/*
 * Modifications Copyright OpenSearch Contributors. See
 * GitHub history for details.
 */

package org.opensearch.search.lookup;

import org.apache.lucene.index.LeafReaderContext;
import org.opensearch.ExceptionsHelper;
import org.opensearch.common.Nullable;
import org.opensearch.common.logging.DeprecationLogger;
import org.opensearch.index.fielddata.IndexFieldData;
import org.opensearch.index.fielddata.ScriptDocValues;
import org.opensearch.index.mapper.MappedFieldType;
import org.opensearch.index.mapper.MapperService;

import java.io.IOException;
import java.security.AccessController;
import java.security.PrivilegedAction;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

public class LeafDocLookup implements Map<String, ScriptDocValues<?>> {

    private static final DeprecationLogger DEPRECATION_LOGGER =  DeprecationLogger.getLogger(LeafDocLookup.class);
    static final String TYPES_DEPRECATION_KEY = "type-field-doc-lookup";
    static final String TYPES_DEPRECATION_MESSAGE =
            "[types removal] Looking up doc types [_type] in scripts is deprecated.";

    private final Map<String, ScriptDocValues<?>> localCacheFieldData = new HashMap<>(4);

    private final MapperService mapperService;
    private final Function<MappedFieldType, IndexFieldData<?>> fieldDataLookup;

    @Nullable
    private final String[] types;

    private final LeafReaderContext reader;

    private int docId = -1;

    LeafDocLookup(MapperService mapperService, Function<MappedFieldType, IndexFieldData<?>> fieldDataLookup, @Nullable String[] types,
                  LeafReaderContext reader) {
        this.mapperService = mapperService;
        this.fieldDataLookup = fieldDataLookup;
        this.types = types;
        this.reader = reader;
    }

    public MapperService mapperService() {
        return this.mapperService;
    }

    public void setDocument(int docId) {
        this.docId = docId;
    }

    @Override
    public ScriptDocValues<?> get(Object key) {
        // deprecate _type
        if ("_type".equals(key)) {
            DEPRECATION_LOGGER.deprecate(TYPES_DEPRECATION_KEY, TYPES_DEPRECATION_MESSAGE);
        }
        // assume its a string...
        String fieldName = key.toString();
        ScriptDocValues<?> scriptValues = localCacheFieldData.get(fieldName);
        if (scriptValues == null) {
            final MappedFieldType fieldType = mapperService.fieldType(fieldName);
            if (fieldType == null) {
                throw new IllegalArgumentException("No field found for [" + fieldName + "] in mapping with types " +
                        Arrays.toString(types));
            }
            // load fielddata on behalf of the script: otherwise it would need additional permissions
            // to deal with pagedbytes/ramusagestimator/etc
            scriptValues = AccessController.doPrivileged(new PrivilegedAction<ScriptDocValues<?>>() {
                @Override
                public ScriptDocValues<?> run() {
                    return fieldDataLookup.apply(fieldType).load(reader).getScriptValues();
                }
            });
            localCacheFieldData.put(fieldName, scriptValues);
        }
        try {
            scriptValues.setNextDocId(docId);
        } catch (IOException e) {
            throw ExceptionsHelper.convertToOpenSearchException(e);
        }
        return scriptValues;
    }

    @Override
    public boolean containsKey(Object key) {
        // assume its a string...
        String fieldName = key.toString();
        ScriptDocValues<?> scriptValues = localCacheFieldData.get(fieldName);
        if (scriptValues == null) {
            MappedFieldType fieldType = mapperService.fieldType(fieldName);
            if (fieldType == null) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int size() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isEmpty() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean containsValue(Object value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScriptDocValues<?> put(String key, ScriptDocValues<?> value) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ScriptDocValues<?> remove(Object key) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void putAll(Map<? extends String, ? extends ScriptDocValues<?>> m) {
        throw new UnsupportedOperationException();
    }

    @Override
    public void clear() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<String> keySet() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Collection<ScriptDocValues<?>> values() {
        throw new UnsupportedOperationException();
    }

    @Override
    public Set<Map.Entry<String, ScriptDocValues<?>>> entrySet() {
        throw new UnsupportedOperationException();
    }
}
