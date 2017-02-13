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
package org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.policies;

import java.io.Serializable;
import java.util.Collection;
import java.util.Comparator;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.hadoop.classification.InterfaceAudience.Private;
import org.apache.hadoop.classification.InterfaceStability.Unstable;
import org.apache.hadoop.yarn.api.records.Resource;
import org.apache.hadoop.yarn.server.resourcemanager.resource.ResourceType;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.FSQueue;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.Schedulable;
import org.apache.hadoop.yarn.server.resourcemanager.scheduler.fair.SchedulingPolicy;
import org.apache.hadoop.yarn.util.resource.DefaultResourceCalculator;
import org.apache.hadoop.yarn.util.resource.ResourceCalculator;
import org.apache.hadoop.yarn.util.resource.Resources;

import com.google.common.annotations.VisibleForTesting;

/**
 * Makes scheduling decisions by trying to equalize shares of memory.
 */
@Private
@Unstable
public class FairSharePolicy extends SchedulingPolicy {
  private static final Log LOG = LogFactory.getLog(FifoPolicy.class);
  @VisibleForTesting
  public static final String NAME = "fair";
  private static final DefaultResourceCalculator RESOURCE_CALCULATOR =
      new DefaultResourceCalculator();
  private static final FairShareComparator COMPARATOR =
          new FairShareComparator();

  @Override
  public String getName() {
    return NAME;
  }

  /**
   * Compare Schedulables via weighted fair sharing. In addition, Schedulables
   * below their min share get priority over those whose min share is met.
   * 
   * Schedulables below their min share are compared by how far below it they
   * are as a ratio. For example, if job A has 8 out of a min share of 10 tasks
   * and job B has 50 out of a min share of 100, then job B is scheduled next,
   * because B is at 50% of its min share and A is at 80% of its min share.
   * 
   * Schedulables above their min share are compared by (runningTasks / weight).
   * If all weights are equal, slots are given to the job with the fewest tasks;
   * otherwise, jobs with more weight get proportionally more slots. If weight
   * equals to 0, we can't compare Schedulables by (resource usage/weight).
   * There are two situations: 1)All weights equal to 0, slots are given
   * to one with less resource usage. 2)Only one of weight equals to 0, slots
   * are given to the one with non-zero weight.
   */
  private static class FairShareComparator implements Comparator<Schedulable>,
      Serializable {
    private static final long serialVersionUID = 5564969375856699313L;
    private static final Resource ONE = Resources.createResource(1);

    @Override
    public int compare(Schedulable s1, Schedulable s2) {
      double minShareRatio1, minShareRatio2;
      double useToWeightRatio1, useToWeightRatio2;
      double weight1, weight2;
      //Do not repeat the getResourceUsage calculation
      Resource resourceUsage1 = s1.getResourceUsage();
      Resource resourceUsage2 = s2.getResourceUsage();
      Resource minShare1 = Resources.min(RESOURCE_CALCULATOR, null,
          s1.getMinShare(), s1.getDemand());
      Resource minShare2 = Resources.min(RESOURCE_CALCULATOR, null,
          s2.getMinShare(), s2.getDemand());
      boolean s1Needy = Resources.lessThan(RESOURCE_CALCULATOR, null,
          resourceUsage1, minShare1);
      boolean s2Needy = Resources.lessThan(RESOURCE_CALCULATOR, null,
          resourceUsage2, minShare2);
      minShareRatio1 = (double) resourceUsage1.getMemorySize()
          / Resources.max(RESOURCE_CALCULATOR, null, minShare1, ONE).getMemorySize();
      minShareRatio2 = (double) resourceUsage2.getMemorySize()
          / Resources.max(RESOURCE_CALCULATOR, null, minShare2, ONE).getMemorySize();

      weight1 = s1.getWeights().getWeight(ResourceType.MEMORY);
      weight2 = s2.getWeights().getWeight(ResourceType.MEMORY);
      if (weight1 > 0.0 && weight2 > 0.0) {
        useToWeightRatio1 = resourceUsage1.getMemorySize() / weight1;
        useToWeightRatio2 = resourceUsage2.getMemorySize() / weight2;
      } else { // Either weight1 or weight2 equals to 0
        if (weight1 == weight2) {
          // If they have same weight, just compare usage
          useToWeightRatio1 = resourceUsage1.getMemorySize();
          useToWeightRatio2 = resourceUsage2.getMemorySize();
        } else {
          // By setting useToWeightRatios to negative weights, we give the
          // zero-weight one less priority, so the non-zero weight one will
          // be given slots.
          useToWeightRatio1 = -weight1;
          useToWeightRatio2 = -weight2;
        }
      }

      int res = 0;
      if (s1Needy && !s2Needy)
        res = -1;
      else if (s2Needy && !s1Needy)
        res = 1;
      else if (s1Needy && s2Needy)
        res = (int) Math.signum(minShareRatio1 - minShareRatio2);
      else
        // Neither schedulable is needy
        res = (int) Math.signum(useToWeightRatio1 - useToWeightRatio2);
      if (res == 0) {
        // Apps are tied in fairness ratio. Break the tie by submit time and job
        // name to get a deterministic ordering, which is useful for unit tests.
        res = (int) Math.signum(s1.getStartTime() - s2.getStartTime());
        if (res == 0)
          res = s1.getName().compareTo(s2.getName());
      }
      return res;
    }
  }

  @Override
  public Comparator<Schedulable> getComparator() {
    return COMPARATOR;
  }

  @Override
  public ResourceCalculator getResourceCalculator() {
    return RESOURCE_CALCULATOR;
  }

  @Override
  public Resource getHeadroom(Resource queueFairShare,
                              Resource queueUsage, Resource maxAvailable) {
    long queueAvailableMemory = Math.max(
        queueFairShare.getMemorySize() - queueUsage.getMemorySize(), 0);
    Resource headroom = Resources.createResource(
        Math.min(maxAvailable.getMemorySize(), queueAvailableMemory),
        maxAvailable.getVirtualCores());
    return headroom;
  }

  @Override
  public void computeShares(Collection<? extends Schedulable> schedulables,
      Resource totalResources) {
    ComputeFairShares.computeShares(schedulables, totalResources, ResourceType.MEMORY);
  }

  @Override
  public void computeSteadyShares(Collection<? extends FSQueue> queues,
      Resource totalResources) {
    ComputeFairShares.computeSteadyShares(queues, totalResources,
        ResourceType.MEMORY);
  }

  @Override
  public boolean checkIfUsageOverFairShare(Resource usage, Resource fairShare) {
    return Resources.greaterThan(RESOURCE_CALCULATOR, null, usage, fairShare);
  }

  @Override
  public boolean isChildPolicyAllowed(SchedulingPolicy childPolicy) {
    if (childPolicy instanceof DominantResourceFairnessPolicy) {
      LOG.error("Queue policy can't be " + DominantResourceFairnessPolicy.NAME
          + " if the parent policy is " + getName() + ". Choose " +
          getName() + " or " + FifoPolicy.NAME + " for child queues instead."
          + " Please note that " + FifoPolicy.NAME
          + " is only for leaf queues.");
      return false;
    }
    return true;
  }
}
