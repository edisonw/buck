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

package com.facebook.buck.apple;

import static org.junit.Assert.assertEquals;

import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.rules.BuildRuleResolver;
import com.facebook.buck.rules.FakeBuildRuleParamsBuilder;
import com.facebook.buck.rules.SourcePathResolver;
import com.facebook.buck.rules.TestSourcePath;
import com.google.common.base.Optional;

import org.junit.Test;

public class XcodeNativeTest {

  @Test
  public void shouldPopulateFieldsFromArg() {
    XcodeNativeDescription.Arg arg =
        new XcodeNativeDescription().createUnpopulatedConstructorArg();
    arg.projectContainerPath = new TestSourcePath("foo.xcodeproj");
    arg.targetName = Optional.absent();
    arg.buildableName = Optional.absent();
    XcodeNative xcodeNative = new XcodeNative(
        new FakeBuildRuleParamsBuilder(BuildTarget.builder("//test", "test").build()).build(),
        new SourcePathResolver(new BuildRuleResolver()),
        arg);

    assertEquals(new TestSourcePath("foo.xcodeproj"), xcodeNative.getProjectContainerPath());
  }
}
