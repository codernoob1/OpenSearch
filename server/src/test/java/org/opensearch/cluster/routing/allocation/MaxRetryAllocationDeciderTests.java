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
 *     http://www.apache.org/licenses/LICENSE-2.0
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

package org.opensearch.cluster.routing.allocation;

import org.opensearch.Version;
import org.opensearch.cluster.ClusterName;
import org.opensearch.cluster.ClusterState;
import org.opensearch.cluster.OpenSearchAllocationTestCase;
import org.opensearch.cluster.EmptyClusterInfoService;
import org.opensearch.cluster.metadata.IndexMetadata;
import org.opensearch.cluster.metadata.Metadata;
import org.opensearch.cluster.node.DiscoveryNodes;
import org.opensearch.cluster.routing.RoutingTable;
import org.opensearch.cluster.routing.ShardRouting;
import org.opensearch.cluster.routing.allocation.AllocationService;
import org.opensearch.cluster.routing.allocation.FailedShard;
import org.opensearch.cluster.routing.allocation.RoutingAllocation;
import org.opensearch.cluster.routing.allocation.allocator.BalancedShardsAllocator;
import org.opensearch.cluster.routing.allocation.command.AllocationCommands;
import org.opensearch.cluster.routing.allocation.decider.AllocationDeciders;
import org.opensearch.cluster.routing.allocation.decider.Decision;
import org.opensearch.cluster.routing.allocation.decider.MaxRetryAllocationDecider;
import org.opensearch.common.settings.Settings;
import org.opensearch.snapshots.EmptySnapshotsInfoService;
import org.opensearch.test.gateway.TestGatewayAllocator;

import java.util.Collections;
import java.util.List;

import static org.opensearch.cluster.routing.ShardRoutingState.INITIALIZING;
import static org.opensearch.cluster.routing.ShardRoutingState.STARTED;
import static org.opensearch.cluster.routing.ShardRoutingState.UNASSIGNED;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.not;

public class MaxRetryAllocationDeciderTests extends OpenSearchAllocationTestCase {

    private AllocationService strategy;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        strategy = new AllocationService(new AllocationDeciders(
            Collections.singleton(new MaxRetryAllocationDecider())),
            new TestGatewayAllocator(), new BalancedShardsAllocator(Settings.EMPTY), EmptyClusterInfoService.INSTANCE,
            EmptySnapshotsInfoService.INSTANCE);
    }

    private ClusterState createInitialClusterState() {
        Metadata.Builder metaBuilder = Metadata.builder();
        metaBuilder.put(IndexMetadata.builder("idx").settings(settings(Version.CURRENT)).numberOfShards(1).numberOfReplicas(0));
        Metadata metadata = metaBuilder.build();
        RoutingTable.Builder routingTableBuilder = RoutingTable.builder();
        routingTableBuilder.addAsNew(metadata.index("idx"));

        RoutingTable routingTable = routingTableBuilder.build();
        ClusterState clusterState = ClusterState.builder(ClusterName.CLUSTER_NAME_SETTING.getDefault(Settings.EMPTY))
            .metadata(metadata).routingTable(routingTable).build();
        clusterState = ClusterState.builder(clusterState).nodes(DiscoveryNodes.builder().add(newNode("node1")).add(newNode("node2")))
            .build();
        RoutingTable prevRoutingTable = routingTable;
        routingTable = strategy.reroute(clusterState, "reroute").routingTable();
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();

        assertEquals(prevRoutingTable.index("idx").shards().size(), 1);
        assertEquals(prevRoutingTable.index("idx").shard(0).shards().get(0).state(), UNASSIGNED);

        assertEquals(routingTable.index("idx").shards().size(), 1);
        assertEquals(routingTable.index("idx").shard(0).shards().get(0).state(), INITIALIZING);
        return clusterState;
    }

    public void testSingleRetryOnIgnore() {
        ClusterState clusterState = createInitialClusterState();
        RoutingTable routingTable = clusterState.routingTable();
        final int retries = MaxRetryAllocationDecider.SETTING_ALLOCATION_MAX_RETRY.get(Settings.EMPTY);
        // now fail it N-1 times
        for (int i = 0; i < retries-1; i++) {
            List<FailedShard> failedShards = Collections.singletonList(
                new FailedShard(routingTable.index("idx").shard(0).shards().get(0), "boom" + i,
                    new UnsupportedOperationException(), randomBoolean()));
            ClusterState newState = strategy.applyFailedShards(clusterState, failedShards);
            assertThat(newState, not(equalTo(clusterState)));
            clusterState = newState;
            routingTable = newState.routingTable();
            assertEquals(routingTable.index("idx").shards().size(), 1);
            assertEquals(routingTable.index("idx").shard(0).shards().get(0).state(), INITIALIZING);
            assertEquals(routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getNumFailedAllocations(), i+1);
            assertThat(routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getMessage(), containsString("boom" + i));
        }
        // now we go and check that we are actually stick to unassigned on the next failure
        List<FailedShard> failedShards = Collections.singletonList(
            new FailedShard(routingTable.index("idx").shard(0).shards().get(0), "boom",
                new UnsupportedOperationException(), randomBoolean()));
        ClusterState newState = strategy.applyFailedShards(clusterState, failedShards);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingTable = newState.routingTable();
        assertEquals(routingTable.index("idx").shards().size(), 1);
        assertEquals(routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getNumFailedAllocations(), retries);
        assertEquals(routingTable.index("idx").shard(0).shards().get(0).state(), UNASSIGNED);
        assertThat(routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getMessage(), containsString("boom"));

        // manual resetting of retry count
        newState = strategy.reroute(clusterState, new AllocationCommands(), false, true).getClusterState();
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingTable = newState.routingTable();

        clusterState = ClusterState.builder(clusterState).routingTable(routingTable).build();
        assertEquals(routingTable.index("idx").shards().size(), 1);
        assertEquals(0, routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getNumFailedAllocations());
        assertEquals(INITIALIZING, routingTable.index("idx").shard(0).shards().get(0).state());
        assertThat(routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getMessage(), containsString("boom"));

        // again fail it N-1 times
        for (int i = 0; i < retries-1; i++) {
        failedShards = Collections.singletonList(
            new FailedShard(routingTable.index("idx").shard(0).shards().get(0), "boom",
                new UnsupportedOperationException(), randomBoolean()));

        newState = strategy.applyFailedShards(clusterState, failedShards);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingTable = newState.routingTable();
        assertEquals(routingTable.index("idx").shards().size(), 1);
        assertEquals(i + 1, routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getNumFailedAllocations());
        assertEquals(INITIALIZING, routingTable.index("idx").shard(0).shards().get(0).state());
        assertThat(routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getMessage(), containsString("boom"));
        }

        // now we go and check that we are actually stick to unassigned on the next failure
        failedShards = Collections.singletonList(
            new FailedShard(routingTable.index("idx").shard(0).shards().get(0), "boom",
                new UnsupportedOperationException(), randomBoolean()));
        newState = strategy.applyFailedShards(clusterState, failedShards);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingTable = newState.routingTable();
        assertEquals(routingTable.index("idx").shards().size(), 1);
        assertEquals(retries, routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getNumFailedAllocations());
        assertEquals(UNASSIGNED, routingTable.index("idx").shard(0).shards().get(0).state());
        assertThat(routingTable.index("idx").shard(0).shards().get(0).unassignedInfo().getMessage(), containsString("boom"));
    }

    public void testFailedAllocation() {
        ClusterState clusterState = createInitialClusterState();
        RoutingTable routingTable = clusterState.routingTable();
        final int retries = MaxRetryAllocationDecider.SETTING_ALLOCATION_MAX_RETRY.get(Settings.EMPTY);
        // now fail it N-1 times
        for (int i = 0; i < retries-1; i++) {
            List<FailedShard> failedShards = Collections.singletonList(
                new FailedShard(routingTable.index("idx").shard(0).shards().get(0), "boom" + i,
                    new UnsupportedOperationException(), randomBoolean()));
            ClusterState newState = strategy.applyFailedShards(clusterState, failedShards);
            assertThat(newState, not(equalTo(clusterState)));
            clusterState = newState;
            routingTable = newState.routingTable();
            assertEquals(routingTable.index("idx").shards().size(), 1);
            ShardRouting unassignedPrimary = routingTable.index("idx").shard(0).shards().get(0);
            assertEquals(unassignedPrimary.state(), INITIALIZING);
            assertEquals(unassignedPrimary.unassignedInfo().getNumFailedAllocations(), i+1);
            assertThat(unassignedPrimary.unassignedInfo().getMessage(), containsString("boom" + i));
            // MaxRetryAllocationDecider#canForceAllocatePrimary should return YES decisions because canAllocate returns YES here
            assertEquals(Decision.YES, new MaxRetryAllocationDecider().canForceAllocatePrimary(
                unassignedPrimary, null, new RoutingAllocation(null, null, clusterState, null, null,0)));
        }
        // now we go and check that we are actually stick to unassigned on the next failure
        {
            List<FailedShard> failedShards = Collections.singletonList(
                new FailedShard(routingTable.index("idx").shard(0).shards().get(0), "boom",
                    new UnsupportedOperationException(), randomBoolean()));
            ClusterState newState = strategy.applyFailedShards(clusterState, failedShards);
            assertThat(newState, not(equalTo(clusterState)));
            clusterState = newState;
            routingTable = newState.routingTable();
            assertEquals(routingTable.index("idx").shards().size(), 1);
            ShardRouting unassignedPrimary = routingTable.index("idx").shard(0).shards().get(0);
            assertEquals(unassignedPrimary.unassignedInfo().getNumFailedAllocations(), retries);
            assertEquals(unassignedPrimary.state(), UNASSIGNED);
            assertThat(unassignedPrimary.unassignedInfo().getMessage(), containsString("boom"));
            // MaxRetryAllocationDecider#canForceAllocatePrimary should return a NO decision because canAllocate returns NO here
            assertEquals(Decision.NO, new MaxRetryAllocationDecider().canForceAllocatePrimary(
                unassignedPrimary, null, new RoutingAllocation(null, null, clusterState, null, null,0)));
        }

        // change the settings and ensure we can do another round of allocation for that index.
        clusterState = ClusterState.builder(clusterState).routingTable(routingTable)
            .metadata(Metadata.builder(clusterState.metadata())
                .put(IndexMetadata.builder(clusterState.metadata().index("idx")).settings(
                    Settings.builder().put(clusterState.metadata().index("idx").getSettings()).put("index.allocation.max_retries",
                        retries+1).build()
                ).build(), true).build()).build();
        ClusterState newState = strategy.reroute(clusterState, "settings changed");
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingTable = newState.routingTable();
        // good we are initializing and we are maintaining failure information
        assertEquals(routingTable.index("idx").shards().size(), 1);
        ShardRouting unassignedPrimary = routingTable.index("idx").shard(0).shards().get(0);
        assertEquals(unassignedPrimary.unassignedInfo().getNumFailedAllocations(), retries);
        assertEquals(unassignedPrimary.state(), INITIALIZING);
        assertThat(unassignedPrimary.unassignedInfo().getMessage(), containsString("boom"));
        // bumped up the max retry count, so canForceAllocatePrimary should return a YES decision
        assertEquals(Decision.YES, new MaxRetryAllocationDecider().canForceAllocatePrimary(
            routingTable.index("idx").shard(0).shards().get(0), null, new RoutingAllocation(null, null, clusterState, null, null,0)));

        // now we start the shard
        clusterState = startShardsAndReroute(strategy, clusterState, routingTable.index("idx").shard(0).shards().get(0));
        routingTable = clusterState.routingTable();

        // all counters have been reset to 0 ie. no unassigned info
        assertEquals(routingTable.index("idx").shards().size(), 1);
        assertNull(routingTable.index("idx").shard(0).shards().get(0).unassignedInfo());
        assertEquals(routingTable.index("idx").shard(0).shards().get(0).state(), STARTED);

        // now fail again and see if it has a new counter
        List<FailedShard> failedShards = Collections.singletonList(
            new FailedShard(routingTable.index("idx").shard(0).shards().get(0), "ZOOOMG",
                new UnsupportedOperationException(), randomBoolean()));
        newState = strategy.applyFailedShards(clusterState, failedShards);
        assertThat(newState, not(equalTo(clusterState)));
        clusterState = newState;
        routingTable = newState.routingTable();
        assertEquals(routingTable.index("idx").shards().size(), 1);
        unassignedPrimary = routingTable.index("idx").shard(0).shards().get(0);
        assertEquals(unassignedPrimary.unassignedInfo().getNumFailedAllocations(), 1);
        assertEquals(unassignedPrimary.state(), UNASSIGNED);
        assertThat(unassignedPrimary.unassignedInfo().getMessage(), containsString("ZOOOMG"));
        // Counter reset, so MaxRetryAllocationDecider#canForceAllocatePrimary should return a YES decision
        assertEquals(Decision.YES, new MaxRetryAllocationDecider().canForceAllocatePrimary(
            unassignedPrimary, null, new RoutingAllocation(null, null, clusterState, null, null,0)));
    }

}
