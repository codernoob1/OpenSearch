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

package org.opensearch.action.search;

import org.apache.lucene.util.BytesRef;
import org.opensearch.Version;
import org.opensearch.action.ActionListener;
import org.opensearch.action.OriginalIndices;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.node.DiscoveryNode;
import org.opensearch.cluster.routing.GroupShardsIterator;
import org.opensearch.common.Strings;
import org.opensearch.common.util.concurrent.OpenSearchExecutors;
import org.opensearch.index.shard.ShardId;
import org.opensearch.search.SearchPhaseResult;
import org.opensearch.search.SearchService;
import org.opensearch.search.SearchShardTarget;
import org.opensearch.search.builder.SearchSourceBuilder;
import org.opensearch.search.internal.AliasFilter;
import org.opensearch.search.internal.ShardSearchRequest;
import org.opensearch.search.sort.MinAndMax;
import org.opensearch.search.sort.SortBuilders;
import org.opensearch.search.sort.SortOrder;
import org.opensearch.test.OpenSearchTestCase;
import org.opensearch.transport.Transport;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.IntStream;

import static org.hamcrest.Matchers.equalTo;

public class CanMatchPreFilterSearchPhaseTests extends OpenSearchTestCase {

    public void testFilterShards() throws InterruptedException {

        final TransportSearchAction.SearchTimeProvider timeProvider = new TransportSearchAction.SearchTimeProvider(0, System.nanoTime(),
            System::nanoTime);

        Map<String, Transport.Connection> lookup = new ConcurrentHashMap<>();
        DiscoveryNode primaryNode = new DiscoveryNode("node_1", buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode replicaNode = new DiscoveryNode("node_2", buildNewFakeTransportAddress(), Version.CURRENT);
        lookup.put("node1", new SearchAsyncActionTests.MockConnection(primaryNode));
        lookup.put("node2", new SearchAsyncActionTests.MockConnection(replicaNode));
        final boolean shard1 = randomBoolean();
        final boolean shard2 = randomBoolean();

        SearchTransportService searchTransportService = new SearchTransportService(null, null) {
            @Override
            public void sendCanMatch(Transport.Connection connection, ShardSearchRequest request, SearchTask task,
                                     ActionListener<SearchService.CanMatchResponse> listener) {
                new Thread(() -> listener.onResponse(new SearchService.CanMatchResponse(request.shardId().id() == 0 ? shard1 :
                    shard2, null))).start();
            }
        };

        AtomicReference<GroupShardsIterator<SearchShardIterator>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        GroupShardsIterator<SearchShardIterator> shardsIter = SearchAsyncActionTests.getShardsIter("idx",
            new OriginalIndices(new String[]{"idx"}, SearchRequest.DEFAULT_INDICES_OPTIONS),
            2, randomBoolean(), primaryNode, replicaNode);
        final SearchRequest searchRequest = new SearchRequest();
        searchRequest.allowPartialSearchResults(true);

        CanMatchPreFilterSearchPhase canMatchPhase = new CanMatchPreFilterSearchPhase(logger,
            searchTransportService,
            (clusterAlias, node) -> lookup.get(node),
            Collections.singletonMap("_na_", new AliasFilter(null, Strings.EMPTY_ARRAY)),
            Collections.emptyMap(), Collections.emptyMap(), OpenSearchExecutors.newDirectExecutorService(),
            searchRequest, null, shardsIter, timeProvider, ClusterState.EMPTY_STATE, null,
            (iter) -> new SearchPhase("test") {
                    @Override
                    public void run() throws IOException {
                        result.set(iter);
                        latch.countDown();
                    }}, SearchResponse.Clusters.EMPTY);

        canMatchPhase.start();
        latch.await();

        if (shard1 && shard2) {
            for (SearchShardIterator i : result.get()) {
                assertFalse(i.skip());
            }
        } else if (shard1 == false &&  shard2 == false) {
            assertFalse(result.get().get(0).skip());
            assertTrue(result.get().get(1).skip());
        } else {
            assertEquals(0, result.get().get(0).shardId().id());
            assertEquals(1, result.get().get(1).shardId().id());
            assertEquals(shard1, !result.get().get(0).skip());
            assertEquals(shard2, !result.get().get(1).skip());
        }
    }

    public void testFilterWithFailure() throws InterruptedException {
        final TransportSearchAction.SearchTimeProvider timeProvider = new TransportSearchAction.SearchTimeProvider(0, System.nanoTime(),
            System::nanoTime);
        Map<String, Transport.Connection> lookup = new ConcurrentHashMap<>();
        DiscoveryNode primaryNode = new DiscoveryNode("node_1", buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode replicaNode = new DiscoveryNode("node_2", buildNewFakeTransportAddress(), Version.CURRENT);
        lookup.put("node1", new SearchAsyncActionTests.MockConnection(primaryNode));
        lookup.put("node2", new SearchAsyncActionTests.MockConnection(replicaNode));
        final boolean shard1 = randomBoolean();
        SearchTransportService searchTransportService = new SearchTransportService(null, null) {
            @Override
            public void sendCanMatch(Transport.Connection connection, ShardSearchRequest request, SearchTask task,
                                     ActionListener<SearchService.CanMatchResponse> listener) {
                boolean throwException = request.shardId().id() != 0;
                if (throwException && randomBoolean()) {
                    throw new IllegalArgumentException("boom");
                } else {
                    new Thread(() -> {
                        if (throwException == false) {
                            listener.onResponse(new SearchService.CanMatchResponse(shard1, null));
                        } else {
                            listener.onFailure(new NullPointerException());
                        }
                    }).start();
                }
            }
        };

        AtomicReference<GroupShardsIterator<SearchShardIterator>> result = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        GroupShardsIterator<SearchShardIterator> shardsIter = SearchAsyncActionTests.getShardsIter("idx",
            new OriginalIndices(new String[]{"idx"}, SearchRequest.DEFAULT_INDICES_OPTIONS),
            2, randomBoolean(), primaryNode, replicaNode);

        final SearchRequest searchRequest = new SearchRequest();
        searchRequest.allowPartialSearchResults(true);

        CanMatchPreFilterSearchPhase canMatchPhase = new CanMatchPreFilterSearchPhase(logger,
            searchTransportService,
            (clusterAlias, node) -> lookup.get(node),
            Collections.singletonMap("_na_", new AliasFilter(null, Strings.EMPTY_ARRAY)),
            Collections.emptyMap(), Collections.emptyMap(), OpenSearchExecutors.newDirectExecutorService(),
            searchRequest, null, shardsIter, timeProvider, ClusterState.EMPTY_STATE, null,
            (iter) -> new SearchPhase("test") {
                @Override
                public void run() throws IOException {
                    result.set(iter);
                    latch.countDown();
                }}, SearchResponse.Clusters.EMPTY);

        canMatchPhase.start();
        latch.await();

        assertEquals(0, result.get().get(0).shardId().id());
        assertEquals(1, result.get().get(1).shardId().id());
        assertEquals(shard1, !result.get().get(0).skip());
        assertFalse(result.get().get(1).skip()); // never skip the failure
    }

    /*
     * In cases that a query coordinating node held all the shards for a query, the can match phase would recurse and end in stack overflow
     * when subjected to max concurrent search requests. This test is a test for that situation.
     */
    public void testLotsOfShards() throws InterruptedException {
        final TransportSearchAction.SearchTimeProvider timeProvider =
            new TransportSearchAction.SearchTimeProvider(0, System.nanoTime(), System::nanoTime);

        final Map<String, Transport.Connection> lookup = new ConcurrentHashMap<>();
        final DiscoveryNode primaryNode = new DiscoveryNode("node_1", buildNewFakeTransportAddress(), Version.CURRENT);
        final DiscoveryNode replicaNode = new DiscoveryNode("node_2", buildNewFakeTransportAddress(), Version.CURRENT);
        lookup.put("node1", new SearchAsyncActionTests.MockConnection(primaryNode));
        lookup.put("node2", new SearchAsyncActionTests.MockConnection(replicaNode));


        final SearchTransportService searchTransportService =
            new SearchTransportService(null, null) {
                @Override
                public void sendCanMatch(
                    Transport.Connection connection,
                    ShardSearchRequest request,
                    SearchTask task,
                    ActionListener<SearchService.CanMatchResponse> listener) {
                    listener.onResponse(new SearchService.CanMatchResponse(randomBoolean(), null));
                }
            };

        final CountDownLatch latch = new CountDownLatch(1);
        final OriginalIndices originalIndices = new OriginalIndices(new String[]{"idx"}, SearchRequest.DEFAULT_INDICES_OPTIONS);
        final GroupShardsIterator<SearchShardIterator> shardsIter =
            SearchAsyncActionTests.getShardsIter("idx", originalIndices, 4096, randomBoolean(), primaryNode, replicaNode);
        final ExecutorService executor = Executors.newFixedThreadPool(randomIntBetween(1, Runtime.getRuntime().availableProcessors()));
        final SearchRequest searchRequest = new SearchRequest();
        searchRequest.allowPartialSearchResults(true);
        SearchTransportService transportService = new SearchTransportService(null, null);
        ActionListener<SearchResponse> responseListener = ActionListener.wrap(response -> {},
            (e) -> { throw new AssertionError("unexpected", e);});
        Map<String, AliasFilter> aliasFilters = Collections.singletonMap("_na_", new AliasFilter(null, Strings.EMPTY_ARRAY));
        final CanMatchPreFilterSearchPhase canMatchPhase = new CanMatchPreFilterSearchPhase(
            logger,
            searchTransportService,
            (clusterAlias, node) -> lookup.get(node),
            Collections.singletonMap("_na_", new AliasFilter(null, Strings.EMPTY_ARRAY)),
            Collections.emptyMap(),
            Collections.emptyMap(),
            OpenSearchExecutors.newDirectExecutorService(),
            searchRequest,
            null,
            shardsIter,
            timeProvider,
            ClusterState.EMPTY_STATE,
            null,
            (iter) -> new AbstractSearchAsyncAction<SearchPhaseResult>(
                "test",
                logger,
                transportService,
                (cluster, node) -> {
                        assert cluster == null : "cluster was not null: " + cluster;
                        return lookup.get(node);
                    },
                aliasFilters,
                Collections.emptyMap(),
                Collections.emptyMap(),
                executor,
                searchRequest,
                responseListener,
                iter,
                new TransportSearchAction.SearchTimeProvider(0, 0, () -> 0),
                ClusterState.EMPTY_STATE,
                null,
                new ArraySearchPhaseResults<>(iter.size()),
                randomIntBetween(1, 32),
                SearchResponse.Clusters.EMPTY) {

                @Override
                protected SearchPhase getNextPhase(SearchPhaseResults<SearchPhaseResult> results, SearchPhaseContext context) {
                    return new SearchPhase("test") {
                        @Override
                        public void run() {
                            latch.countDown();
                        }
                    };
                }

                @Override
                protected void executePhaseOnShard(
                    final SearchShardIterator shardIt,
                    final SearchShardTarget shard,
                    final SearchActionListener<SearchPhaseResult> listener) {
                    if (randomBoolean()) {
                        listener.onResponse(new SearchPhaseResult() {});
                    } else {
                        listener.onFailure(new Exception("failure"));
                    }
                }
            }, SearchResponse.Clusters.EMPTY);

        canMatchPhase.start();
        latch.await();
        executor.shutdown();
    }

    public void testSortShards() throws InterruptedException {
        final TransportSearchAction.SearchTimeProvider timeProvider = new TransportSearchAction.SearchTimeProvider(0, System.nanoTime(),
            System::nanoTime);

        Map<String, Transport.Connection> lookup = new ConcurrentHashMap<>();
        DiscoveryNode primaryNode = new DiscoveryNode("node_1", buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode replicaNode = new DiscoveryNode("node_2", buildNewFakeTransportAddress(), Version.CURRENT);
        lookup.put("node1", new SearchAsyncActionTests.MockConnection(primaryNode));
        lookup.put("node2", new SearchAsyncActionTests.MockConnection(replicaNode));

        for (SortOrder order : SortOrder.values()) {
            List<ShardId> shardIds = new ArrayList<>();
            List<MinAndMax<?>> minAndMaxes = new ArrayList<>();
            Set<ShardId> shardToSkip = new HashSet<>();

            SearchTransportService searchTransportService = new SearchTransportService(null, null) {
                @Override
                public void sendCanMatch(Transport.Connection connection, ShardSearchRequest request, SearchTask task,
                                         ActionListener<SearchService.CanMatchResponse> listener) {
                    Long min = rarely() ? null : randomLong();
                    Long max = min == null ? null  : randomLongBetween(min, Long.MAX_VALUE);
                    MinAndMax<?> minMax = min == null ? null : new MinAndMax<>(min, max);
                    boolean canMatch = frequently();
                    synchronized (shardIds) {
                        shardIds.add(request.shardId());
                        minAndMaxes.add(minMax);
                        if (canMatch == false) {
                            shardToSkip.add(request.shardId());
                        }
                    }
                    new Thread(() -> listener.onResponse(new SearchService.CanMatchResponse(canMatch, minMax))).start();
                }
            };

            AtomicReference<GroupShardsIterator<SearchShardIterator>> result = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            GroupShardsIterator<SearchShardIterator> shardsIter = SearchAsyncActionTests.getShardsIter("logs",
                new OriginalIndices(new String[]{"logs"}, SearchRequest.DEFAULT_INDICES_OPTIONS),
                randomIntBetween(2, 20), randomBoolean(), primaryNode, replicaNode);
            final SearchRequest searchRequest = new SearchRequest();
            searchRequest.source(new SearchSourceBuilder().sort(SortBuilders.fieldSort("timestamp").order(order)));
            searchRequest.allowPartialSearchResults(true);

            CanMatchPreFilterSearchPhase canMatchPhase = new CanMatchPreFilterSearchPhase(logger,
                searchTransportService,
                (clusterAlias, node) -> lookup.get(node),
                Collections.singletonMap("_na_", new AliasFilter(null, Strings.EMPTY_ARRAY)),
                Collections.emptyMap(), Collections.emptyMap(), OpenSearchExecutors.newDirectExecutorService(),
                searchRequest, null, shardsIter, timeProvider, ClusterState.EMPTY_STATE, null,
                (iter) -> new SearchPhase("test") {
                    @Override
                    public void run() {
                        result.set(iter);
                        latch.countDown();
                    }
                }, SearchResponse.Clusters.EMPTY);

            canMatchPhase.start();
            latch.await();
            ShardId[] expected = IntStream.range(0, shardIds.size())
                .boxed()
                .sorted(Comparator.comparing(minAndMaxes::get, MinAndMax.getComparator(order)).thenComparing(shardIds::get))
                .map(shardIds::get)
                .toArray(ShardId[]::new);

            int pos = 0;
            for (SearchShardIterator i : result.get()) {
                assertEquals(shardToSkip.contains(i.shardId()), i.skip());
                assertEquals(expected[pos++], i.shardId());
            }
        }
    }

    public void testInvalidSortShards() throws InterruptedException {
        final TransportSearchAction.SearchTimeProvider timeProvider =
            new TransportSearchAction.SearchTimeProvider(0, System.nanoTime(), System::nanoTime);

        Map<String, Transport.Connection> lookup = new ConcurrentHashMap<>();
        DiscoveryNode primaryNode = new DiscoveryNode("node_1", buildNewFakeTransportAddress(), Version.CURRENT);
        DiscoveryNode replicaNode = new DiscoveryNode("node_2", buildNewFakeTransportAddress(), Version.CURRENT);
        lookup.put("node1", new SearchAsyncActionTests.MockConnection(primaryNode));
        lookup.put("node2", new SearchAsyncActionTests.MockConnection(replicaNode));

        for (SortOrder order : SortOrder.values()) {
            int numShards = randomIntBetween(2, 20);
            List<ShardId> shardIds = new ArrayList<>();
            Set<ShardId> shardToSkip = new HashSet<>();

            SearchTransportService searchTransportService = new SearchTransportService(null, null) {
                @Override
                public void sendCanMatch(Transport.Connection connection, ShardSearchRequest request, SearchTask task,
                                         ActionListener<SearchService.CanMatchResponse> listener) {
                    final MinAndMax<?> minMax;
                    if (request.shardId().id() == numShards-1) {
                        minMax = new MinAndMax<>(new BytesRef("bar"), new BytesRef("baz"));
                    } else {
                        Long min = randomLong();
                        Long max = randomLongBetween(min, Long.MAX_VALUE);
                        minMax = new MinAndMax<>(min, max);
                    }
                    boolean canMatch = frequently();
                    synchronized (shardIds) {
                        shardIds.add(request.shardId());
                        if (canMatch == false) {
                            shardToSkip.add(request.shardId());
                        }
                    }
                    new Thread(() -> listener.onResponse(new SearchService.CanMatchResponse(canMatch, minMax))).start();
                }
            };

            AtomicReference<GroupShardsIterator<SearchShardIterator>> result = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);
            GroupShardsIterator<SearchShardIterator> shardsIter = SearchAsyncActionTests.getShardsIter("logs",
                new OriginalIndices(new String[]{"logs"}, SearchRequest.DEFAULT_INDICES_OPTIONS),
                numShards, randomBoolean(), primaryNode, replicaNode);
            final SearchRequest searchRequest = new SearchRequest();
            searchRequest.source(new SearchSourceBuilder().sort(SortBuilders.fieldSort("timestamp").order(order)));
            searchRequest.allowPartialSearchResults(true);

            CanMatchPreFilterSearchPhase canMatchPhase = new CanMatchPreFilterSearchPhase(logger,
                searchTransportService,
                (clusterAlias, node) -> lookup.get(node),
                Collections.singletonMap("_na_", new AliasFilter(null, Strings.EMPTY_ARRAY)),
                Collections.emptyMap(), Collections.emptyMap(), OpenSearchExecutors.newDirectExecutorService(),
                searchRequest, null, shardsIter, timeProvider, ClusterState.EMPTY_STATE, null,
                (iter) -> new SearchPhase("test") {
                    @Override
                    public void run() {
                        result.set(iter);
                        latch.countDown();
                    }
                }, SearchResponse.Clusters.EMPTY);

            canMatchPhase.start();
            latch.await();
            int shardId = 0;
            for (SearchShardIterator i : result.get()) {
                assertThat(i.shardId().id(), equalTo(shardId++));
                assertEquals(shardToSkip.contains(i.shardId()), i.skip());
            }
            assertThat(result.get().size(), equalTo(numShards));
        }
    }
}
