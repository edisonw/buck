/*
 * Copyright 2012-present Facebook, Inc.
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

package com.facebook.buck.rules;

import com.facebook.buck.android.AndroidBinaryDescription;
import com.facebook.buck.android.AndroidBuildConfigDescription;
import com.facebook.buck.android.AndroidInstrumentationApkDescription;
import com.facebook.buck.android.AndroidLibraryDescription;
import com.facebook.buck.android.AndroidManifestDescription;
import com.facebook.buck.android.AndroidPrebuiltAarDescription;
import com.facebook.buck.android.AndroidResourceDescription;
import com.facebook.buck.android.ApkGenruleDescription;
import com.facebook.buck.android.GenAidlDescription;
import com.facebook.buck.android.NdkLibraryDescription;
import com.facebook.buck.android.PrebuiltNativeLibraryDescription;
import com.facebook.buck.android.ProGuardConfig;
import com.facebook.buck.android.RobolectricTestDescription;
import com.facebook.buck.apple.AppleAssetCatalogDescription;
import com.facebook.buck.apple.AppleBinaryDescription;
import com.facebook.buck.apple.AppleBundleDescription;
import com.facebook.buck.apple.AppleConfig;
import com.facebook.buck.apple.AppleLibraryDescription;
import com.facebook.buck.apple.AppleResourceDescription;
import com.facebook.buck.apple.AppleTestDescription;
import com.facebook.buck.apple.CoreDataModelDescription;
import com.facebook.buck.apple.IosPostprocessResourcesDescription;
import com.facebook.buck.apple.XcodeNativeDescription;
import com.facebook.buck.apple.XcodeProjectConfigDescription;
import com.facebook.buck.apple.XcodeWorkspaceConfigDescription;
import com.facebook.buck.cli.BuckConfig;
import com.facebook.buck.cxx.CxxBinaryDescription;
import com.facebook.buck.cxx.CxxLibraryDescription;
import com.facebook.buck.cxx.CxxPythonExtensionDescription;
import com.facebook.buck.cxx.CxxTestDescription;
import com.facebook.buck.cxx.DefaultCxxPlatform;
import com.facebook.buck.cxx.PrebuiltCxxLibraryDescription;
import com.facebook.buck.extension.BuckExtensionDescription;
import com.facebook.buck.file.Downloader;
import com.facebook.buck.file.RemoteFileDescription;
import com.facebook.buck.gwt.GwtBinaryDescription;
import com.facebook.buck.java.JavaBinaryDescription;
import com.facebook.buck.java.JavaCompilerEnvironment;
import com.facebook.buck.java.JavaLibraryDescription;
import com.facebook.buck.java.JavaTestDescription;
import com.facebook.buck.java.JavacOptions;
import com.facebook.buck.java.KeystoreDescription;
import com.facebook.buck.java.PrebuiltJarDescription;
import com.facebook.buck.ocaml.OCamlBinaryDescription;
import com.facebook.buck.ocaml.OCamlBuckConfig;
import com.facebook.buck.ocaml.OCamlLibraryDescription;
import com.facebook.buck.ocaml.PrebuiltOCamlLibraryDescription;
import com.facebook.buck.parcelable.GenParcelableDescription;
import com.facebook.buck.python.PythonBinaryDescription;
import com.facebook.buck.python.PythonEnvironment;
import com.facebook.buck.python.PythonLibraryDescription;
import com.facebook.buck.python.PythonTestDescription;
import com.facebook.buck.shell.ExportFileDescription;
import com.facebook.buck.shell.GenruleDescription;
import com.facebook.buck.shell.ShBinaryDescription;
import com.facebook.buck.shell.ShTestDescription;
import com.facebook.buck.thrift.ThriftBuckConfig;
import com.facebook.buck.thrift.ThriftCxxEnhancer;
import com.facebook.buck.thrift.ThriftJavaEnhancer;
import com.facebook.buck.thrift.ThriftLibraryDescription;
import com.facebook.buck.thrift.ThriftPythonEnhancer;
import com.facebook.buck.util.AndroidDirectoryResolver;
import com.facebook.buck.util.HumanReadableException;
import com.facebook.buck.util.environment.Platform;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Maps;

import java.net.Proxy;
import java.nio.file.Path;
import java.util.Map;

/**
 * A registry of all the build rules types understood by Buck.
 */
public class KnownBuildRuleTypes {

  private final ImmutableMap<BuildRuleType, Description<?>> descriptions;
  private final ImmutableMap<String, BuildRuleType> types;

  private KnownBuildRuleTypes(
      Map<BuildRuleType, Description<?>> descriptions,
      Map<String, BuildRuleType> types) {
    this.descriptions = ImmutableMap.copyOf(descriptions);
    this.types = ImmutableMap.copyOf(types);
  }

  public BuildRuleType getBuildRuleType(String named) {
    BuildRuleType type = types.get(named);
    if (type == null) {
      throw new HumanReadableException("Unable to find build rule type: " + named);
    }
    return type;
  }

  public Description<?> getDescription(BuildRuleType buildRuleType) {
    Description<?> description = descriptions.get(buildRuleType);
    if (description == null) {
      throw new HumanReadableException(
          "Unable to find description for build rule type: " + buildRuleType);
    }
    return description;
  }

  public ImmutableSet<Description<?>> getAllDescriptions() {
    return ImmutableSet.copyOf(descriptions.values());
  }

  public static Builder builder() {
    return new Builder();
  }

  public static KnownBuildRuleTypes createInstance(
      BuckConfig config,
      AndroidDirectoryResolver androidDirectoryResolver,
      JavaCompilerEnvironment javacEnv,
      PythonEnvironment pythonEnv) {
    return createBuilder(config, androidDirectoryResolver, javacEnv, pythonEnv).build();
  }

  @VisibleForTesting
  static Builder createBuilder(
      BuckConfig config,
      AndroidDirectoryResolver androidDirectoryResolver,
      JavaCompilerEnvironment javacEnv,
      PythonEnvironment pythonEnv) {

    Platform platform = Platform.detect();

    Optional<String> ndkVersion = config.getNdkVersion();
    // If a NDK version isn't specified, we've got to reach into the runtime environment to find
    // out which one we will end up using.
    if (!ndkVersion.isPresent()) {
      ndkVersion = androidDirectoryResolver.getNdkVersion();
    }

    AppleConfig appleConfig = new AppleConfig(config);

    // Construct the thrift config wrapping the buck config.
    ThriftBuckConfig thriftBuckConfig = new ThriftBuckConfig(config);

    // Construct the OCaml config wrapping the buck config.
    OCamlBuckConfig ocamlBuckConfig = new OCamlBuckConfig(platform, config);

    // Construct the C/C++ config wrapping the buck config.
    DefaultCxxPlatform cxxPlatform = new DefaultCxxPlatform(platform, config);

    ProGuardConfig proGuardConfig = new ProGuardConfig(config);

    // Look up the path to the PEX builder script.
    Optional<Path> pythonPathToPex = config.getPath("python", "path_to_pex");

    // Look up the path to the main module we use for python tests.
    Optional<Path> pythonPathToPythonTestMain =
        config.getPath("python", "path_to_python_test_main");

    // Default maven repo, if set
    Optional<String> defaultMavenRepo = config.getValue("download", "maven_repo");
    Downloader downloader = new Downloader(Optional.<Proxy>absent(), defaultMavenRepo);
    boolean downloadAtRuntimeOk = config.getBooleanValue("download", "in_build", false);

    Builder builder = builder();

    JavacOptions androidBinaryOptions = JavacOptions.builder(JavacOptions.DEFAULTS)
        .setJavaCompilerEnvironment(javacEnv)
        .build();
    builder.register(new AndroidBinaryDescription(androidBinaryOptions, proGuardConfig));
    builder.register(new AndroidBuildConfigDescription());
    builder.register(new AndroidInstrumentationApkDescription(proGuardConfig));
    builder.register(new AndroidLibraryDescription(javacEnv));
    builder.register(new AndroidManifestDescription());
    builder.register(new AndroidPrebuiltAarDescription());
    builder.register(new AndroidResourceDescription());
    builder.register(new ApkGenruleDescription());
    builder.register(new AppleAssetCatalogDescription());
    builder.register(new AppleBinaryDescription(appleConfig));
    builder.register(new AppleBundleDescription());
    builder.register(new AppleLibraryDescription(appleConfig));
    builder.register(new AppleResourceDescription());
    builder.register(new AppleTestDescription());
    builder.register(new BuckExtensionDescription());
    builder.register(new CoreDataModelDescription());
    builder.register(new CxxBinaryDescription(cxxPlatform));
    builder.register(new CxxLibraryDescription(cxxPlatform));
    builder.register(new CxxPythonExtensionDescription(cxxPlatform));
    builder.register(new CxxTestDescription(cxxPlatform));
    builder.register(new ExportFileDescription());
    builder.register(new GenruleDescription());
    builder.register(new GenAidlDescription());
    builder.register(new GenParcelableDescription());
    builder.register(new GwtBinaryDescription());
    builder.register(new IosPostprocessResourcesDescription());
    builder.register(new JavaBinaryDescription());
    builder.register(new JavaLibraryDescription(javacEnv));
    builder.register(new JavaTestDescription(javacEnv));
    builder.register(new KeystoreDescription());
    builder.register(new NdkLibraryDescription(ndkVersion));
    builder.register(new OCamlBinaryDescription(ocamlBuckConfig));
    builder.register(new OCamlLibraryDescription(ocamlBuckConfig));
    builder.register(new PrebuiltCxxLibraryDescription(cxxPlatform));
    builder.register(new PrebuiltJarDescription());
    builder.register(new PrebuiltNativeLibraryDescription());
    builder.register(new PrebuiltOCamlLibraryDescription());
    builder.register(new ProjectConfigDescription());
    builder.register(
        new PythonBinaryDescription(
            pythonPathToPex.or(PythonBinaryDescription.DEFAULT_PATH_TO_PEX),
            pythonEnv));
    builder.register(new PythonLibraryDescription());
    builder.register(
        new PythonTestDescription(
            pythonPathToPex.or(PythonBinaryDescription.DEFAULT_PATH_TO_PEX),
            pythonPathToPythonTestMain.or(PythonTestDescription.PYTHON_PATH_TO_PYTHON_TEST_MAIN),
            pythonEnv));
    builder.register(new RemoteFileDescription(downloadAtRuntimeOk, downloader));
    builder.register(new RobolectricTestDescription(javacEnv));
    builder.register(new ShBinaryDescription());
    builder.register(new ShTestDescription());
    builder.register(
        new ThriftLibraryDescription(
            thriftBuckConfig,
            ImmutableList.of(
                new ThriftJavaEnhancer(thriftBuckConfig, javacEnv),
                new ThriftCxxEnhancer(thriftBuckConfig, cxxPlatform, /* cpp2 */ false),
                new ThriftCxxEnhancer(thriftBuckConfig, cxxPlatform, /* cpp2 */ true),
                new ThriftPythonEnhancer(thriftBuckConfig, ThriftPythonEnhancer.Type.NORMAL),
                new ThriftPythonEnhancer(thriftBuckConfig, ThriftPythonEnhancer.Type.TWISTED))));
    builder.register(new XcodeNativeDescription());
    builder.register(new XcodeProjectConfigDescription());
    builder.register(new XcodeWorkspaceConfigDescription());

    return builder;
  }

  public static class Builder {
    private final Map<BuildRuleType, Description<?>> descriptions;
    private final Map<String, BuildRuleType> types;

    protected Builder() {
      this.descriptions = Maps.newConcurrentMap();
      this.types = Maps.newConcurrentMap();
    }

    public void register(Description<?> description) {
      Preconditions.checkNotNull(description);
      BuildRuleType type = description.getBuildRuleType();
      types.put(type.getName(), type);
      descriptions.put(type, description);
    }

    public KnownBuildRuleTypes build() {
      return new KnownBuildRuleTypes(descriptions, types);
    }
  }
}
