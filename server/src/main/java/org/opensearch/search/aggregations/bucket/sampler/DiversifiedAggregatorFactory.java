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

package org.opensearch.search.aggregations.bucket.sampler;

import org.opensearch.index.query.QueryShardContext;
import org.opensearch.search.aggregations.Aggregator;
import org.opensearch.search.aggregations.AggregatorFactories;
import org.opensearch.search.aggregations.AggregatorFactory;
import org.opensearch.search.aggregations.CardinalityUpperBound;
import org.opensearch.search.aggregations.InternalAggregation;
import org.opensearch.search.aggregations.NonCollectingAggregator;
import org.opensearch.search.aggregations.bucket.sampler.SamplerAggregator.ExecutionMode;
import org.opensearch.search.aggregations.support.CoreValuesSourceType;
import org.opensearch.search.aggregations.support.ValuesSourceAggregatorFactory;
import org.opensearch.search.aggregations.support.ValuesSourceConfig;
import org.opensearch.search.aggregations.support.ValuesSourceRegistry;
import org.opensearch.search.internal.SearchContext;

import java.io.IOException;
import java.util.Map;

public class DiversifiedAggregatorFactory extends ValuesSourceAggregatorFactory {

    public static void registerAggregators(ValuesSourceRegistry.Builder builder) {
        builder.register(
            DiversifiedAggregationBuilder.REGISTRY_KEY,
            org.opensearch.common.collect.List.of(
                CoreValuesSourceType.NUMERIC,
                CoreValuesSourceType.DATE,
                CoreValuesSourceType.BOOLEAN),
            (
                String name,
                int shardSize,
                AggregatorFactories factories,
                SearchContext context,
                Aggregator parent,
                Map<String, Object> metadata,
                ValuesSourceConfig valuesSourceConfig,
                int maxDocsPerValue,
                String executionHint) -> new DiversifiedNumericSamplerAggregator(
                    name,
                    shardSize,
                    factories,
                    context,
                    parent,
                    metadata,
                    valuesSourceConfig,
                    maxDocsPerValue
                ),
                true);

        builder.register(
            DiversifiedAggregationBuilder.REGISTRY_KEY,
            CoreValuesSourceType.BYTES,
            (
                String name,
                int shardSize,
                AggregatorFactories factories,
                SearchContext context,
                Aggregator parent,
                Map<String, Object> metadata,
                ValuesSourceConfig valuesSourceConfig,
                int maxDocsPerValue,
                String executionHint) -> {
                ExecutionMode execution = null;
                if (executionHint != null) {
                    execution = ExecutionMode.fromString(executionHint);
                }

                // In some cases using ordinals is just not supported: override it
                if (execution == null) {
                    execution = ExecutionMode.GLOBAL_ORDINALS;
                }
                if ((execution.needsGlobalOrdinals()) && (valuesSourceConfig.hasGlobalOrdinals() == false)) {
                    execution = ExecutionMode.MAP;
                }
                return execution.create(name, factories, shardSize, maxDocsPerValue, valuesSourceConfig, context, parent, metadata);
            },
            true);
    }

    private final int shardSize;
    private final int maxDocsPerValue;
    private final String executionHint;

    DiversifiedAggregatorFactory(String name, ValuesSourceConfig config, int shardSize, int maxDocsPerValue,
                                 String executionHint, QueryShardContext queryShardContext, AggregatorFactory parent,
                                 AggregatorFactories.Builder subFactoriesBuilder, Map<String, Object> metadata) throws IOException {
        super(name, config, queryShardContext, parent, subFactoriesBuilder, metadata);
        this.shardSize = shardSize;
        this.maxDocsPerValue = maxDocsPerValue;
        this.executionHint = executionHint;
    }

    @Override
    protected Aggregator doCreateInternal(SearchContext searchContext,
                                          Aggregator parent,
                                          CardinalityUpperBound cardinality,
                                          Map<String, Object> metadata) throws IOException {

        return queryShardContext.getValuesSourceRegistry()
            .getAggregator(DiversifiedAggregationBuilder.REGISTRY_KEY, config)
            .build(name, shardSize, factories, searchContext, parent, metadata, config, maxDocsPerValue, executionHint);
    }

    @Override
    protected Aggregator createUnmapped(SearchContext searchContext,
                                            Aggregator parent,
                                            Map<String, Object> metadata) throws IOException {
        final UnmappedSampler aggregation = new UnmappedSampler(name, metadata);

        return new NonCollectingAggregator(name, searchContext, parent, factories, metadata) {
            @Override
            public InternalAggregation buildEmptyAggregation() {
                return aggregation;
            }
        };
    }
}
