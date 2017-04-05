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
package org.apache.hadoop.yarn.server.resourcemanager.scheduler;

import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.api.records.ResourceRequest;
import org.apache.hadoop.yarn.server.resourcemanager.MockNodes;
import org.apache.hadoop.yarn.server.resourcemanager.rmnode.RMNode;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSSchedulerNode;
import org.apache.hadoop.yarn.util.resource.DefaultResourceCalculator;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.junit.Before;
import org.junit.Test;

import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

/**
 * Test class to verify ClusterNodeTracker. Using FSSchedulerNode without
 * loss of generality.
 */
public class TestClusterNodeTracker {
  private ClusterNodeTracker<FSSchedulerNode> nodeTracker =
      new ClusterNodeTracker<>();

  @Before
  public void setup() {
    List<RMNode> rmNodes =
        MockNodes.newNodes(2, 4000, Resource.newInstance(4096, 4));
    for (RMNode rmNode : rmNodes) {
      nodeTracker.addNode(new FSSchedulerNode(rmNode, false));
    }
  }

  @Test
  public void testGetNodeCount() {
    assertEquals("Incorrect number of nodes in the cluster",
        8, nodeTracker.nodeCount());

    assertEquals("Incorrect number of nodes in each rack",
        4, nodeTracker.nodeCount("rack0"));
  }

  @Test
  public void testGetNodesForResourceName() throws Exception {
    assertEquals("Incorrect number of nodes matching ANY",
        8, nodeTracker.getNodesByResourceName(ResourceRequest.ANY).size());

    assertEquals("Incorrect number of nodes matching rack",
        4, nodeTracker.getNodesByResourceName("rack0").size());

    assertEquals("Incorrect number of nodes matching node",
        1, nodeTracker.getNodesByResourceName("host0").size());
  }

  @Test
  public void testSortedNodeList() throws InterruptedException {
    int i = -4;
    for(FSSchedulerNode node : nodeTracker.getAllNodes()) {
      node.deductUnallocatedResource(Resource.newInstance(i * 1024, i));
      i++;
    }

    new Thread() {
      @Override
      public void run() {
        for (int j = 0; j < 100; j++ ) {
          for (FSSchedulerNode node : nodeTracker.getAllNodes()) {
            int i = ThreadLocalRandom.current().nextInt(-4, 30);
            node.deductUnallocatedResource(Resource.newInstance(i * 1024, i));
          }
        }
      }
    }.start();

    Thread.sleep(10);

    try {
      List<FSSchedulerNode> nodeIdList =
          nodeTracker.sortedNodeList(new NodeAvailableResourceComparator());

      for(FSSchedulerNode node : nodeIdList) {
        System.out.println(node.toString());
      }

    } catch (Exception e) {
      fail(e.getMessage());
    }
  }

  private class NodeAvailableResourceComparator
      implements Comparator<FSSchedulerNode> {

    private final ResourceCalculator RESOURCE_CALCULATOR =
        new DefaultResourceCalculator();

    @Override
    public int compare(FSSchedulerNode n1, FSSchedulerNode n2) {
      return RESOURCE_CALCULATOR.compare(nodeTracker.getClusterCapacity(),
          n2.getUnallocatedResource(),
          n1.getUnallocatedResource());
    }
  }
}