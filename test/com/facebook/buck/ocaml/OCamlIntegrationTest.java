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

package com.facebook.buck.ocaml;

import static com.facebook.buck.ocaml.OCamlRuleBuilder.createStaticLibraryBuildTarget;
import static com.facebook.buck.ocaml.OCamlRuleBuilder.createOCamlLinkTarget;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.facebook.buck.cxx.CxxCompilableEnhancer;
import com.facebook.buck.cxx.CxxDescriptionEnhancer;
import com.facebook.buck.model.BuildTarget;
import com.facebook.buck.model.BuildTargetFactory;
import com.facebook.buck.testutil.integration.BuckBuildLog;
import com.facebook.buck.testutil.integration.DebuggableTemporaryFolder;
import com.facebook.buck.testutil.integration.ProjectWorkspace;
import com.facebook.buck.testutil.integration.TestDataHelper;
import com.facebook.buck.util.environment.Platform;
import com.google.common.collect.ImmutableSet;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.io.IOException;
import java.nio.file.Files;

public class OCamlIntegrationTest {

  @Rule
  public DebuggableTemporaryFolder tmp = new DebuggableTemporaryFolder();

  @Before
  public void beforeMethod() {
    org.junit.Assume.assumeTrue(Files.exists(OCamlBuildContext.DEFAULT_OCAML_COMPILER));
    org.junit.Assume.assumeTrue(Files.exists(OCamlBuildContext.DEFAULT_OCAML_BYTECODE_COMPILER));
    org.junit.Assume.assumeTrue(Files.exists(OCamlBuildContext.DEFAULT_OCAML_DEP_TOOL));
    org.junit.Assume.assumeTrue(Files.exists(OCamlBuildContext.DEFAULT_OCAML_YACC_COMPILER));
    org.junit.Assume.assumeTrue(Files.exists(OCamlBuildContext.DEFAULT_OCAML_LEX_COMPILER));
  }

  @Test
  public void testHelloOcamlBuild() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this, "ocaml", tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//hello_ocaml:hello_ocaml");
    BuildTarget binary = createOCamlLinkTarget(target);
    BuildTarget lib = BuildTargetFactory.newInstance("//hello_ocaml:ocamllib");
    BuildTarget staticLib = createStaticLibraryBuildTarget(lib);
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary, lib, staticLib);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(staticLib.toString());

    workspace.resetBuildLogFile();

    // Check that running a build again results in no builds since everything is up to
    // date.
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetHadMatchingRuleKey(target.toString());
    buildLog.assertTargetHadMatchingRuleKey(staticLib.toString());

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("hello_ocaml/amodule.ml", "v2", "v3");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetHadMatchingRuleKey(staticLib.toString());

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("ocamllib/m1.ml", "print me", "print Me");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(staticLib.toString());

    workspace.resetBuildLogFile();

    // Update the source file.
    workspace.replaceFileContents("hello_ocaml/BUCK", "#INSERT_POINT", "'../ocamllib/dummy.ml',");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(staticLib.toString());

    workspace.resetBuildLogFile();

    BuildTarget lib1 = BuildTargetFactory.newInstance("//hello_ocaml:ocamllib1");
    BuildTarget staticLib1 = createStaticLibraryBuildTarget(lib1);
    ImmutableSet<BuildTarget> targets1 = ImmutableSet.of(target, binary, lib1, staticLib1);
    // We rebuild if lib name changes
    workspace.replaceFileContents("hello_ocaml/BUCK", "name = 'ocamllib'", "name = 'ocamllib1'");
    workspace.replaceFileContents(
        "hello_ocaml/BUCK",
        ":ocamllib",
        ":ocamllib1");

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets1));

    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(staticLib1.toString());
  }

  @Test
  public void testLexAndYaccBuild() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "ocaml",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//calc:calc");
    BuildTarget binary = createOCamlLinkTarget(target);

    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertEquals(
        targets,
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        targets,
        buildLog.getAllTargets());
    buildLog.assertTargetHadMatchingRuleKey(target.toString());
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("calc/lexer.mll", "The type token", "the type token");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        targets,
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();

    workspace.replaceFileContents("calc/parser.mly", "the entry point", "The entry point");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertEquals(
        targets,
        buildLog.getAllTargets());
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());
  }

  @Test
  public void testCInteropBuild() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "ocaml",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//ctest:ctest");
    BuildTarget binary = createOCamlLinkTarget(target);
    ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();

    BuckBuildLog buildLog = workspace.getBuildLog();

    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetBuiltLocally(target.toString());

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetHadMatchingRuleKey(target.toString());
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/ctest.c", "NATIVE PLUS", "Native Plus");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/BUCK", "#INSERTION_POINT", "compiler_flags=['-noassert']");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents(
        "ctest/BUCK",
        "compiler_flags=['-noassert']",
        "compiler_flags=[]");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("ctest/BUCK", "compiler_flags=[]", "compiler_flags=[]");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetHadMatchingRuleKey(target.toString());
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());
  }

  @Test
  public void testSimpleBuildWithLib() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "ocaml",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:plus");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
  }

  @Test
  public void testRootBuildTarget() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "ocaml",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//:main");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
  }

  @Test
  public void testPrebuiltLibrary() throws IOException {
    if (Platform.detect() == Platform.MACOS) {
      ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
          this,
          "ocaml",
          tmp);
      workspace.setUp();

      BuildTarget target = BuildTargetFactory.newInstance("//ocaml_ext_mac:ocaml_ext");
      BuildTarget binary = createOCamlLinkTarget(target);
      BuildTarget libplus = BuildTargetFactory.newInstance("//ocaml_ext_mac:plus");
      ImmutableSet<BuildTarget> targets = ImmutableSet.of(target, binary, libplus);

      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      BuckBuildLog buildLog = workspace.getBuildLog();
      assertTrue(buildLog.getAllTargets().containsAll(targets));
      buildLog.assertTargetBuiltLocally(target.toString());
      buildLog.assertTargetBuiltLocally(binary.toString());

      workspace.resetBuildLogFile();
      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      buildLog = workspace.getBuildLog();
      assertTrue(buildLog.getAllTargets().containsAll(targets));
      buildLog.assertTargetHadMatchingRuleKey(target.toString());
      buildLog.assertTargetHadMatchingRuleKey(binary.toString());

      workspace.resetBuildLogFile();
      workspace.replaceFileContents(
          "ocaml_ext_mac/BUCK",
          "libplus_lib",
          "libplus_lib1");
      workspace.runBuckCommand("build", target.toString()).assertSuccess();
      buildLog = workspace.getBuildLog();
      assertTrue(buildLog.getAllTargets().containsAll(targets));
      buildLog.assertTargetBuiltLocally(target.toString());
      buildLog.assertTargetBuiltLocally(binary.toString());
    }
  }

  @Test
  public void testCppLibraryDependency() throws IOException {
    ProjectWorkspace workspace = TestDataHelper.createProjectWorkspaceForScenario(
        this,
        "ocaml",
        tmp);
    workspace.setUp();

    BuildTarget target = BuildTargetFactory.newInstance("//clib:clib");
    BuildTarget binary = createOCamlLinkTarget(target);
    BuildTarget libplus = BuildTargetFactory.newInstance("//clib:plus");
    BuildTarget libplusStatic = createStaticLibraryBuildTarget(libplus);
    BuildTarget cclib = BuildTargetFactory.newInstance("//clib:cc");

    BuildTarget cclibbin = CxxDescriptionEnhancer.createStaticLibraryBuildTarget(cclib);
    String sourceName = "cc.cpp";
    BuildTarget ccObj = CxxCompilableEnhancer.createCompileBuildTarget(
        cclib,
        sourceName,
        /* pic */ false);
    BuildTarget headerSymlinkTreeTarget =
        CxxDescriptionEnhancer.createHeaderSymlinkTreeTarget(cclib);

    ImmutableSet<BuildTarget> targets = ImmutableSet.of(
        target,
        binary,
        libplus,
        libplusStatic,
        cclib,
        cclibbin,
        ccObj,
        headerSymlinkTreeTarget);

    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    BuckBuildLog buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());
    buildLog.assertTargetBuiltLocally(libplus.toString());
    buildLog.assertTargetBuiltLocally(libplusStatic.toString());
    buildLog.assertTargetBuiltLocally(cclibbin.toString());
    buildLog.assertTargetBuiltLocally(ccObj.toString());
    buildLog.assertTargetBuiltLocally(headerSymlinkTreeTarget.toString());

    workspace.resetBuildLogFile();
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));

    buildLog.assertTargetHadMatchingRuleKey(target.toString());
    buildLog.assertTargetHadMatchingRuleKey(binary.toString());
    buildLog.assertTargetHadMatchingRuleKey(libplus.toString());
    buildLog.assertTargetHadMatchingRuleKey(libplusStatic.toString());
    buildLog.assertTargetHadMatchingRuleKey(cclibbin.toString());
    buildLog.assertTargetHadMatchingRuleKey(ccObj.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());

    workspace.resetBuildLogFile();
    workspace.replaceFileContents("clib/cc/cc.cpp", "Hi there", "hi there");
    workspace.runBuckCommand("build", target.toString()).assertSuccess();
    buildLog = workspace.getBuildLog();
    assertTrue(buildLog.getAllTargets().containsAll(targets));
    buildLog.assertTargetBuiltLocally(target.toString());
    buildLog.assertTargetBuiltLocally(binary.toString());
    buildLog.assertTargetBuiltLocally(libplus.toString());
    buildLog.assertTargetBuiltLocally(libplusStatic.toString());
    buildLog.assertTargetBuiltLocally(cclibbin.toString());
    buildLog.assertTargetBuiltLocally(ccObj.toString());
    buildLog.assertTargetHadMatchingRuleKey(headerSymlinkTreeTarget.toString());
  }
}
