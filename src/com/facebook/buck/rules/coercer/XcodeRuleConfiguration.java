/*
 * Copyright 2013-present Facebook, Inc.
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

package com.facebook.buck.rules.coercer;

import com.facebook.buck.rules.SourcePath;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSortedMap;

import java.util.Objects;

/**
 * Represents a layered Xcode build configuration.
 *
 * The first layer is the lowest layer on the list, that is, the latter layers are visited first
 * when resolving variables.
 */
public class XcodeRuleConfiguration {
  private final ImmutableList<XcodeRuleConfigurationLayer> layers;

  public XcodeRuleConfiguration(ImmutableList<XcodeRuleConfigurationLayer> layers) {
    this.layers = Preconditions.checkNotNull(layers);
  }

  public ImmutableList<XcodeRuleConfigurationLayer> getLayers() {
    return layers;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof XcodeRuleConfiguration)) {
      return false;
    }

    XcodeRuleConfiguration that = (XcodeRuleConfiguration) o;
    return Objects.equals(layers, that.layers);
  }

  @Override
  public int hashCode() {
    return Objects.hash(layers);
  }

  /**
   * Convert from a raw json representation of the Configuration to an actual Configuration object.
   *
   * @param configurations
   *    A map of configuration names to lists, where each each element is a layer of the
   *    configuration. Each layer can be specified as a path to a .xcconfig file, or a dictionary of
   *    xcode build settings.
   */
  public static ImmutableSortedMap<String, XcodeRuleConfiguration> fromRawJsonStructure(
      ImmutableMap<
          String,
          ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>> configurations) {
    ImmutableSortedMap.Builder<String, XcodeRuleConfiguration> builder = ImmutableSortedMap
        .naturalOrder();
    for (ImmutableMap.Entry<
        String,
        ImmutableList<Either<SourcePath, ImmutableMap<String, String>>>> entry
        : configurations.entrySet()) {
      ImmutableList.Builder<XcodeRuleConfigurationLayer> layers = ImmutableList.builder();
      for (Either<SourcePath, ImmutableMap<String, String>> value : entry.getValue()) {
        if (value.isLeft()) {
          layers.add(new XcodeRuleConfigurationLayer(value.getLeft()));
        } else if (value.isRight()) {
          layers.add(new XcodeRuleConfigurationLayer(value.getRight()));
        }
      }
      builder.put(entry.getKey(), new XcodeRuleConfiguration(layers.build()));
    }
    return builder.build();
  }
}
