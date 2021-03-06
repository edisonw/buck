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

package com.facebook.buck.cxx;

import com.facebook.buck.rules.BuildRuleType;

/**
 * Interface for {@link com.facebook.buck.rules.BuildRule} objects (e.g. C++ libraries) which can
 * contribute to the top-level link of a native binary (e.g. C++ binary).
 */
public interface NativeLinkable {

  // The style of linking for which this native linkable should provide input for.
  public static enum Type {

    // Provide input suitable for statically linking this linkable (e.g. return references to
    // static libraries, libfoo.a).
    STATIC,

    // Provide input suitable for dynamically linking this linkable (e.g. return references to
    // shared libraries, libfoo.so).
    SHARED

  }

  final BuildRuleType NATIVE_LINKABLE_TYPE = new BuildRuleType("link");

  NativeLinkableInput getNativeLinkableInput(Linker linker, Type type);

}
