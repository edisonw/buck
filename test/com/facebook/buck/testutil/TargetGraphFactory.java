/*
 * Copyright 2014-present Facebook, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may
 * not use this file except in compliance with the License. You may obtain
 * a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations
 * under the License.
 */

package com.facebook.buck.testutil;

import com.facebook.buck.graph.MutableDirectedGraph;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.TargetGraph;
import com.facebook.buck.rules.TargetNode;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.util.Map;

public class TargetGraphFactory {

  private TargetGraphFactory() {}

  public static TargetGraph newInstance(ImmutableSet<TargetNode<?>> nodes) {
    ImmutableMap.Builder<BuildTarget, TargetNode<?>> builder = ImmutableMap.builder();
    Map<BuildTarget, TargetNode<?>> unflavoredMap = Maps.newHashMap();
    for (TargetNode<?> node : nodes) {
      builder.put(node.getBuildTarget(), node);
      unflavoredMap.put(node.getBuildTarget().getUnflavoredTarget(), node);
    }
    ImmutableMap<BuildTarget, TargetNode<?>> map = builder.build();

    MutableDirectedGraph<TargetNode<?>> graph = new MutableDirectedGraph<>();
    for (TargetNode<?> node : map.values()) {
      for (BuildTarget dep : node.getDeps()) {
        graph.addEdge(node, Preconditions.checkNotNull(map.get(dep)));
      }
    }
    return new TargetGraph(graph, ImmutableMap.copyOf(unflavoredMap));
  }
}
