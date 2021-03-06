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

package com.facebook.buck.step.fs;

import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.step.Step;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Preconditions;

import java.io.IOException;
import java.nio.file.CopyOption;
import java.nio.file.Path;

public class MoveStep implements Step {

  private final Path source;
  private final Path destination;
  private final CopyOption[] options;

  public MoveStep(Path source, Path destination, CopyOption... options) {
    this.source = Preconditions.checkNotNull(source);
    this.destination = Preconditions.checkNotNull(destination);
    this.options = Preconditions.checkNotNull(options);
  }

  @Override
  public int execute(ExecutionContext context) throws InterruptedException {
    ProjectFilesystem filesystem = context.getProjectFilesystem();
    try {
      filesystem.move(source, destination, options);
    } catch (IOException e) {
      context.logError(e, "error moving %s -> %s", source, destination);
      return 1;
    }
    return 0;
  }

  @Override
  public String getShortName() {
    return "mv";
  }

  @Override
  public String getDescription(ExecutionContext context) {
    return String.format("mv %s %s", source, destination);
  }

}
