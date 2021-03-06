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

package com.facebook.buck.android;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;

import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.base.Optional;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.IOException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Set;

public class AndroidResourceDescriptionTest {

  @Rule
  public TemporaryFolder tmpFolder;

  @Before
  public void setUp() throws IOException {
    tmpFolder = new TemporaryFolder();
    tmpFolder.create();
  }

  @Test
  public void testNonAssetFilesAndDirsAreIgnored() throws IOException {
    tmpFolder.newFolder("res");

    tmpFolder.newFile("res/image.png");
    tmpFolder.newFile("res/layout.xml");
    tmpFolder.newFile("res/_file");

    tmpFolder.newFile("res/.svn");
    tmpFolder.newFile("res/.git");
    tmpFolder.newFile("res/.ds_store");
    tmpFolder.newFile("res/.scc");
    tmpFolder.newFile("res/CVS");
    tmpFolder.newFile("res/thumbs.db");
    tmpFolder.newFile("res/picasa.ini");
    tmpFolder.newFile("res/file.bak~");

    tmpFolder.newFolder("res/dirs");
    tmpFolder.newFolder("res/dirs/values");
    tmpFolder.newFile("res/dirs/values/strings.xml");
    tmpFolder.newFile("res/dirs/values/strings.xml.orig");

    tmpFolder.newFolder("res/dirs/.svn");
    tmpFolder.newFile("res/dirs/.svn/ignore");
    tmpFolder.newFolder("res/dirs/.git");
    tmpFolder.newFile("res/dirs/.git/ignore");
    tmpFolder.newFolder("res/dirs/.ds_store");
    tmpFolder.newFile("res/dirs/.ds_store/ignore");
    tmpFolder.newFolder("res/dirs/.scc");
    tmpFolder.newFile("res/dirs/.scc/ignore");
    tmpFolder.newFolder("res/dirs/CVS");
    tmpFolder.newFile("res/dirs/CVS/ignore");
    tmpFolder.newFolder("res/dirs/thumbs.db");
    tmpFolder.newFile("res/dirs/thumbs.db/ignore");
    tmpFolder.newFolder("res/dirs/picasa.ini");
    tmpFolder.newFile("res/dirs/picasa.ini/ignore");
    tmpFolder.newFolder("res/dirs/file.bak~");
    tmpFolder.newFile("res/dirs/file.bak~/ignore");
    tmpFolder.newFolder("res/dirs/_dir");
    tmpFolder.newFile("res/dirs/_dir/ignore");

    AndroidResourceDescription description = new AndroidResourceDescription();
    Set<Path> inputs = description.collectInputFiles(
            new ProjectFilesystem(tmpFolder.getRoot().toPath()),
            Optional.of(Paths.get("res")));

    assertThat(
        inputs,
        containsInAnyOrder(
            Paths.get("res/image.png"),
            Paths.get("res/layout.xml"),
            Paths.get("res/_file"),
            Paths.get("res/dirs/values/strings.xml")));
  }
}
