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

package com.facebook.buck.cli;

import com.android.ddmlib.AdbCommandRejectedException;
import com.android.ddmlib.CollectingOutputReceiver;
import com.android.ddmlib.IDevice;
import com.android.ddmlib.InstallException;
import com.facebook.buck.android.agent.util.AgentUtil;
import com.facebook.buck.event.BuckEventBus;
import com.facebook.buck.event.ConsoleEvent;
import com.facebook.buck.event.TraceEventLogger;
import com.facebook.buck.log.Logger;
import com.facebook.buck.rules.InstallableApk;
import com.facebook.buck.step.ExecutionContext;
import com.facebook.buck.util.NamedTemporaryFile;
import com.facebook.buck.util.ProjectFilesystem;
import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Optional;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Sets;

import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/**
 * ExopackageInstaller manages the installation of apps with the "exopackage" flag set to true.
 */
public class ExopackageInstaller {

  private static final Logger LOG = Logger.get(ExopackageInstaller.class);

  /**
   * Prefix of the path to the agent apk on the device.
   */
  private static final String AGENT_DEVICE_PATH = "/data/app/" + AgentUtil.AGENT_PACKAGE_NAME;

  /**
   * Command line to invoke the agent on the device.
   */
  private static final String JAVA_AGENT_COMMAND =
      "dalvikvm -classpath " +
      AGENT_DEVICE_PATH + "-1.apk:" + AGENT_DEVICE_PATH + "-2.apk:" +
      AGENT_DEVICE_PATH + "-1/base.apk:" + AGENT_DEVICE_PATH + "-2/base.apk " +
      "com.facebook.buck.android.agent.AgentMain ";

  /**
   * Maximum length of commands that can be passed to "adb shell".
   */
  private static final int MAX_ADB_COMMAND_SIZE = 1019;

  private static final Path SECONDARY_DEX_DIR = Paths.get("secondary-dex");

  private final ProjectFilesystem projectFilesystem;
  private final BuckEventBus eventBus;
  private final AdbHelper adbHelper;
  private final InstallableApk apkRule;
  private final String packageName;
  private final Path dataRoot;

  private final InstallableApk.ExopackageInfo exopackageInfo;

  /**
   * The next port number to use for communicating with the agent on a device.
   * This resets for every instance of ExopackageInstaller,
   * but is incremented for every device we are installing on when using "-x".
   */
  private final AtomicInteger nextAgentPort = new AtomicInteger(2828);

  @VisibleForTesting
  static class PackageInfo {
    final String apkPath;
    final String nativeLibPath;
    final String versionCode;
    private PackageInfo(String apkPath, String nativeLibPath, String versionCode) {
      this.nativeLibPath = nativeLibPath;
      this.apkPath = apkPath;
      this.versionCode = versionCode;
    }
  }

  public ExopackageInstaller(
      ExecutionContext context,
      AdbHelper adbHelper,
      InstallableApk apkRule) {
    this.adbHelper = adbHelper;
    this.projectFilesystem = context.getProjectFilesystem();
    this.eventBus = context.getBuckEventBus();
    this.apkRule = apkRule;
    this.packageName = AdbHelper.tryToExtractPackageNameFromManifest(apkRule, context);
    this.dataRoot = Paths.get("/data/local/tmp/exopackage/").resolve(packageName);

    Preconditions.checkArgument(AdbHelper.PACKAGE_NAME_PATTERN.matcher(packageName).matches());

    Optional<InstallableApk.ExopackageInfo> exopackageInfo = apkRule.getExopackageInfo();
    Preconditions.checkArgument(exopackageInfo.isPresent());
    this.exopackageInfo = exopackageInfo.get();
  }

  /**
   * Installs the app specified in the constructor.  This object should be discarded afterward.
   */
  public synchronized boolean install() throws InterruptedException {
    eventBus.post(InstallEvent.started(apkRule.getBuildTarget()));

    boolean success = adbHelper.adbCall(
        new AdbHelper.AdbCallable() {
          @Override
          public boolean call(IDevice device) throws Exception {
            try {
              return new SingleDeviceInstaller(device, nextAgentPort.getAndIncrement()).doInstall();
            } catch (Exception e) {
              throw new RuntimeException("Failed to install exopackage on " + device, e);
            }
          }

          @Override
          public String toString() {
            return "install exopackage";
          }
        });

    eventBus.post(InstallEvent.finished(apkRule.getBuildTarget(), success));
    return success;
  }

  /**
   * Helper class to manage the state required to install on a single device.
   */
  private class SingleDeviceInstaller {

    /**
     * Device that we are installing onto.
     */
    private final IDevice device;

    /**
     * Port to use for sending files to the agent.
     */
    private final int agentPort;

    /**
     * True iff we should use the native agent.
     */
    private boolean useNativeAgent = true;

    /**
     * Set after the agent is installed.
     */
    @Nullable
    private String nativeAgentPath;

    private SingleDeviceInstaller(IDevice device, int agentPort) {
      this.device = device;
      this.agentPort = agentPort;
    }

    boolean doInstall() throws Exception {
      Optional<PackageInfo> agentInfo = installAgentIfNecessary();
      if (!agentInfo.isPresent()) {
        return false;
      }

      nativeAgentPath = agentInfo.get().nativeLibPath;
      determineBestAgent();

      final File apk = apkRule.getApkPath().toFile();
      // TODO(user): Support SD installation.
      final boolean installViaSd = false;

      if (shouldAppBeInstalled()) {
        try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "install_exo_apk")) {
          Preconditions.checkNotNull(device);
          boolean success = adbHelper.installApkOnDevice(device, apk, installViaSd);
          if (!success) {
            return false;
          }
        }
      }

      final ImmutableMap<String, String> hashToBasename = getRequiredDexFiles();
      final ImmutableSet<String> requiredHashes = hashToBasename.keySet();
      final ImmutableSet<String> presentHashes = prepareSecondaryDexDir(requiredHashes);
      final Set<String> hashesToInstall = Sets.difference(requiredHashes, presentHashes);

      installSecondaryDexFiles(hashesToInstall, hashToBasename);

      // TODO(user): Make this work on Gingerbread.
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "kill_app")) {
        AdbHelper.executeCommandWithErrorChecking(device, "am force-stop " + packageName);
      }

      return true;
    }

    /**
     * Sets {@link #useNativeAgent} to true on pre-L devices, because our native agent is built
     * without -fPIC.  The java agent works fine on L as long as we don't use it for mkdir.
     */
    private void determineBestAgent() throws Exception {
      Preconditions.checkNotNull(device);
      String value = AdbHelper.executeCommandWithErrorChecking(
          device, "getprop ro.build.version.sdk");
      try {
        if (Integer.valueOf(value.trim()) > 19) {
          useNativeAgent = false;
        }
      } catch (NumberFormatException exn) {
        useNativeAgent = false;
      }
    }

    private String getAgentCommand() {
      if (useNativeAgent) {
        return nativeAgentPath + "/libagent.so ";
      } else {
        return JAVA_AGENT_COMMAND;
      }
    }

    private Optional<PackageInfo> getPackageInfo(final String packageName) throws Exception {
      try (TraceEventLogger ignored = TraceEventLogger.start(
          eventBus,
          "get_package_info",
          ImmutableMap.of("package", packageName))) {

        Preconditions.checkNotNull(device);

        /* This produces output that looks like

          Package [com.facebook.katana] (4229ce68):
            userId=10145 gids=[1028, 1015, 3003]
            pkg=Package{42690b80 com.facebook.katana}
            codePath=/data/app/com.facebook.katana-1.apk
            resourcePath=/data/app/com.facebook.katana-1.apk
            nativeLibraryPath=/data/app-lib/com.facebook.katana-1
            versionCode=1640376 targetSdk=14
            versionName=8.0.0.0.23

            ...

         */
        String lines = AdbHelper.executeCommandWithErrorChecking(
            device, "dumpsys package " + packageName);

        return parsePackageInfo(packageName, lines);
      }
    }

    /**
     * @return PackageInfo for the agent, or absent if installation failed.
     */
    private Optional<PackageInfo> installAgentIfNecessary() throws Exception {
      Optional<PackageInfo> agentInfo = getPackageInfo(AgentUtil.AGENT_PACKAGE_NAME);
      if (!agentInfo.isPresent()) {
        LOG.debug("Agent not installed.  Installing.");
        return installAgentApk();
      }
      LOG.debug("Agent version: %s", agentInfo.get().versionCode);
      if (!agentInfo.get().versionCode.equals(AgentUtil.AGENT_VERSION_CODE)) {
        // Always uninstall before installing.  We might be downgrading, which requires
        // an uninstall, or we might just want a clean installation.
        uninstallAgent();
        return installAgentApk();
      }
      return agentInfo;
    }

    private void uninstallAgent() throws InstallException {
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "uninstall_old_agent")) {
        device.uninstallPackage(AgentUtil.AGENT_PACKAGE_NAME);
      }
    }

    private Optional<PackageInfo> installAgentApk() throws Exception {
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "install_agent_apk")) {
        String apkFileName = System.getProperty("buck.android_agent_path");
        if (apkFileName == null) {
          throw new RuntimeException("Android agent apk path not specified in properties");
        }
        File apkPath = new File(apkFileName);
        Preconditions.checkNotNull(device);
        boolean success = adbHelper.installApkOnDevice(device, apkPath, /* installViaSd */ false);
        if (!success) {
          return Optional.absent();
        }
        return getPackageInfo(AgentUtil.AGENT_PACKAGE_NAME);
      }
    }

    private boolean shouldAppBeInstalled() throws Exception {
      Optional<PackageInfo> appPackageInfo = getPackageInfo(packageName);
      if (!appPackageInfo.isPresent()) {
        eventBus.post(ConsoleEvent.info("App not installed.  Installing now."));
        return true;
      }

      LOG.debug("App path: %s", appPackageInfo.get().apkPath);
      String installedAppSignature = getInstalledAppSignature(appPackageInfo.get().apkPath);
      String localAppSignature = AgentUtil.getJarSignature(apkRule.getApkPath().toString());
      LOG.debug("Local app signature: %s", localAppSignature);
      LOG.debug("Remote app signature: %s", installedAppSignature);

      if (!installedAppSignature.equals(localAppSignature)) {
        LOG.debug("App signatures do not match.  Must re-install.");
        return true;
      }

      LOG.debug("App signatures match.  No need to install.");
      return false;
    }

    private String getInstalledAppSignature(final String packagePath) throws Exception {
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "get_app_signature")) {
        String command = getAgentCommand() + "get-signature " + packagePath;
        LOG.debug("Executing %s", command);
        Preconditions.checkNotNull(device);
        String output = AdbHelper.executeCommandWithErrorChecking(device, command);

        String result = output.trim();
        if (result.contains("\n") || result.contains("\r")) {
          throw new IllegalStateException("Unexpected return from get-signature:\n" + output);
        }

        return result;
      }
    }

    private ImmutableMap<String, String> getRequiredDexFiles() throws IOException {
      ImmutableMap.Builder<String, String> hashToBasenameBuilder = ImmutableMap.builder();
      for (String line : projectFilesystem.readLines(exopackageInfo.dexMetadata)) {
        List<String> parts = Splitter.on(' ').splitToList(line);
        if (parts.size() < 2) {
          throw new RuntimeException("Illegal line in metadata file: " + line);
        }
        hashToBasenameBuilder.put(parts.get(1), parts.get(0));
      }
      return hashToBasenameBuilder.build();
    }

    private ImmutableSet<String> prepareSecondaryDexDir(ImmutableSet<String> requiredHashes)
        throws Exception {
      try (TraceEventLogger ignored = TraceEventLogger.start(eventBus, "prepare_dex_dir")) {
        final ImmutableSet.Builder<String> foundHashes = ImmutableSet.builder();

        // Kind of a hack here.  The java agent can't force the proper permissions on the
        // directories it creates, so we use the command-line "mkdir -p" instead of the java agent.
        // Fortunately, "mkdir -p" seems to work on all devices where we use use the java agent.
        String mkdirP = useNativeAgent ? getAgentCommand() + "mkdir-p" : "mkdir -p";

        Preconditions.checkNotNull(device);
      String secondaryDexDir = dataRoot.resolve(SECONDARY_DEX_DIR).toString();
        AdbHelper.executeCommandWithErrorChecking(
            device, "umask 022 && " + mkdirP + " " + secondaryDexDir);
        String output = AdbHelper.executeCommandWithErrorChecking(device, "ls " + secondaryDexDir);

        ImmutableSet.Builder<String> toDeleteBuilder = ImmutableSet.builder();

        scanSecondaryDexDir(output, requiredHashes, foundHashes, toDeleteBuilder);

        Iterable<String> filesToDelete = toDeleteBuilder.build();

        String commandPrefix = "cd " + secondaryDexDir + " && rm ";
        // Add a fudge factor for separators and error checking.
        final int overhead = commandPrefix.length() + 100;
        for (List<String> rmArgs : chunkArgs(filesToDelete, MAX_ADB_COMMAND_SIZE - overhead)) {
          String command = commandPrefix + Joiner.on(' ').join(rmArgs);
          LOG.debug("Executing %s", command);
          AdbHelper.executeCommandWithErrorChecking(device, command);
        }

        return foundHashes.build();
      }
    }

    private void installSecondaryDexFiles(
        Set<String> hashesToInstall,
        ImmutableMap<String, String> hashToBasename)
        throws Exception {
      Preconditions.checkNotNull(device);

      try (TraceEventLogger ignored1 = TraceEventLogger.start(
          eventBus,
          "install_secondary_dexes")) {
        device.createForward(agentPort, agentPort);
        try {
          for (String hash : hashesToInstall) {
            String basename = hashToBasename.get(hash);
            try (TraceEventLogger ignored2 = TraceEventLogger.start(
                eventBus,
                "install_secondary_dex",
                ImmutableMap.of("basename", basename))) {
              installSecondaryDex(
                  device,
                  agentPort,
                  hash,
                  exopackageInfo.dexDirectory.resolve(basename));
            }
          }
          try (TraceEventLogger ignored2 = TraceEventLogger.start(
              eventBus,
              "install_secondary_dex_metadata")) {

            // This is a bit gross.  It was a late addition.  Ideally, we could eliminate this, but
            // it wouldn't be terrible if we don't.  We store the dexed jars on the device
            // with the full SHA-1 hashes in their names.  This is the format that the loader uses
            // internally, so ideally we would just load them in place.  However, the code currently
            // expects to be able to copy the jars from a directory that matches the name in the
            // metadata file, like "secondary-1.dex.jar".  We don't want to give up putting the
            // hashes in the file names (because we use that to skip re-uploads), so just hack
            // the metadata file to have hash-like names.
            try (NamedTemporaryFile temp = new NamedTemporaryFile("metadata", "tmp")) {
              com.google.common.io.Files.write(
                  com.google.common.io.Files.toString(
                      exopackageInfo.dexMetadata.toFile(),
                      Charsets.UTF_8)
                      .replaceAll(
                          "secondary-(\\d+)\\.dex\\.jar (\\p{XDigit}{40}) ",
                          "secondary-$2.dex.jar $2 "),
                  temp.get().toFile(), Charsets.UTF_8);

              installFile(
                  device,
                  agentPort,
                  SECONDARY_DEX_DIR.resolve("metadata.txt"),
                  temp.get());
            }
          }
        } finally {
          try {
            device.removeForward(agentPort, agentPort);
          } catch (AdbCommandRejectedException e) {
            LOG.warn(e, "Failed to remove adb forward on port %d for device %s", agentPort, device);
            eventBus.post(
                ConsoleEvent.warning(
                    "Failed to remove adb forward %d. This is not necessarily a problem\n" +
                    "because it will be recreated during the next exopackage installation.\n" +
                    "See the log for the full exception.",
                    agentPort));
          }
        }
      }
    }

    private void installSecondaryDex(
        final IDevice device,
        final int port,
        String hash,
        final Path source)
        throws Exception {
      installFile(
          device,
          port,
          SECONDARY_DEX_DIR.resolve("secondary-" + hash + ".dex.jar"),
          source);
    }

    private void installFile(
        IDevice device,
        final int port,
        Path pathRelativeToDataRoot,
        final Path source) throws Exception {
      CollectingOutputReceiver receiver = new CollectingOutputReceiver() {

        private boolean sentPayload = false;

        @Override
        public void addOutput(byte[] data, int offset, int length) {
          super.addOutput(data, offset, length);
          if (!sentPayload && getOutput().length() >= AgentUtil.TEXT_SECRET_KEY_SIZE) {
            LOG.verbose("Got key: %s", getOutput().trim());

            sentPayload = true;
            try (Socket clientSocket = new Socket("localhost", port)) {
              LOG.verbose("Connected");
              OutputStream outToDevice = clientSocket.getOutputStream();
              outToDevice.write(
                  getOutput().substring(
                      0,
                      AgentUtil.TEXT_SECRET_KEY_SIZE).getBytes());
              LOG.verbose("Wrote key");
              com.google.common.io.Files.asByteSource(source.toFile()).copyTo(outToDevice);
              LOG.verbose("Wrote file");
            } catch (IOException e) {
              throw new RuntimeException(e);
            }
          }
        }
      };

      String targetFileName = dataRoot.resolve(pathRelativeToDataRoot).toString();
      String command =
          "umask 022 && " +
              getAgentCommand() +
              "receive-file " + port + " " + Files.size(source) + " " +
              targetFileName +
              " ; echo -n :$?";
      LOG.debug("Executing %s", command);

      // If we fail to execute the command, stash the exception.  My experience during development
      // has been that the exception from checkReceiverOutput is more actionable.
      Exception shellException = null;
      try {
        device.executeShellCommand(command, receiver);
      } catch (Exception e) {
        shellException = e;
      }

      try {
        AdbHelper.checkReceiverOutput(command, receiver);
      } catch (Exception e) {
        if (shellException != null) {
          e.addSuppressed(shellException);
        }
        throw e;
      }

      if (shellException != null) {
        throw shellException;
      }

      // The standard Java libraries on Android always create new files un-readable by other users.
      // We use the shell user or root to create these files, so we need to explicitly set the mode
      // to allow the app to read them.  Ideally, the agent would do this automatically, but
      // there's no easy way to do this in Java.  We can drop this if we drop support for the
      // Java agent.
      AdbHelper.executeCommandWithErrorChecking(device, "chmod 644 " + targetFileName);
    }
  }

  @VisibleForTesting
  static Optional<PackageInfo> parsePackageInfo(String packageName, String lines) {
    final String packagePrefix = "  Package [" + packageName + "] (";
    final String otherPrefix = "  Package [";
    boolean sawPackageLine = false;
    final Splitter splitter = Splitter.on('=').limit(2);

    String codePath = null;
    String resourcePath = null;
    String nativeLibPath = null;
    String versionCode = null;

    for (String line : Splitter.on("\r\n").split(lines)) {
      // Just ignore everything until we see the line that says we are in the right package.
      if (line.startsWith(packagePrefix)) {
        sawPackageLine = true;
        continue;
      }
      // This should never happen, but if we do see a different package, stop parsing.
      if (line.startsWith(otherPrefix)) {
        break;
      }
      // Ignore lines before our package.
      if (!sawPackageLine) {
        continue;
      }
      // Parse key-value pairs.
      List<String> parts = splitter.splitToList(line.trim());
      if (parts.size() != 2) {
        continue;
      }
      switch (parts.get(0)) {
        case "codePath":
          codePath = parts.get(1);
          break;
        case "resourcePath":
          resourcePath = parts.get(1);
          break;
        case "nativeLibraryPath":
          nativeLibPath = parts.get(1);
          break;
        // Lollipop uses this name.  Not sure what's "legacy" about it yet.
        // Maybe something to do with 64-bit?
        // Might need to update if people report failures.
        case "legacyNativeLibraryDir":
          nativeLibPath = parts.get(1);
          break;
        case "versionCode":
          // Extra split to get rid of the SDK thing.
          versionCode = parts.get(1).split(" ", 2)[0];
          break;
        default:
          break;
      }
    }

    if (!sawPackageLine) {
      return Optional.absent();
    }

    Preconditions.checkNotNull(codePath, "Could not find codePath");
    Preconditions.checkNotNull(resourcePath, "Could not find resourcePath");
    Preconditions.checkNotNull(nativeLibPath, "Could not find nativeLibraryPath");
    Preconditions.checkNotNull(versionCode, "Could not find versionCode");
    if (!codePath.equals(resourcePath)) {
      throw new IllegalStateException("Code and resource path do not match");
    }

    // Lollipop doesn't give the full path to the apk anymore.  Not sure why it's "base.apk".
    if (!codePath.endsWith(".apk")) {
      codePath += "/base.apk";
    }

    return Optional.of(new PackageInfo(codePath, nativeLibPath, versionCode));
  }

  /**
   * @param output  Output of "ls" command.
   * @param requiredHashes  Hashes of dex files required for this apk.
   * @param foundHashesBuilder  Builder to receive hashes that we need and were found.
   * @param toDeleteBuilder  Builder to receive files that we need to delete.
   */
  @VisibleForTesting
  static void scanSecondaryDexDir(
      String output,
      ImmutableSet<String> requiredHashes,
      ImmutableSet.Builder<String> foundHashesBuilder,
      ImmutableSet.Builder<String> toDeleteBuilder) {
    Pattern dexFilePattern = Pattern.compile("secondary-([0-9a-f]+)\\.[\\w.-]*");

    for (String line : Splitter.on("\r\n").split(output)) {
      if (line.equals("metadata.txt") || line.startsWith(AgentUtil.TEMP_PREFIX)) {
        toDeleteBuilder.add(line);
        continue;
      }

      Matcher m = dexFilePattern.matcher(line);
      if (m.matches()) {
        if (requiredHashes.contains(m.group(1))) {
          foundHashesBuilder.add(m.group(1));
        } else {
          toDeleteBuilder.add(line);
        }
      }
    }
  }

  /**
   * Breaks a list of strings into groups whose total size is within some limit.
   * Kind of like the xargs command that groups arguments to avoid maximum argument length limits.
   * Except that the limit in adb is about 1k instead of 512k or 2M on Linux.
   */
  @VisibleForTesting
  static ImmutableList<ImmutableList<String>> chunkArgs(Iterable<String> args, int sizeLimit) {
    ImmutableList.Builder<ImmutableList<String>> topLevelBuilder = ImmutableList.builder();
    ImmutableList.Builder<String> chunkBuilder = ImmutableList.builder();
    int chunkSize = 0;
    for (String arg : args) {
      if (chunkSize + arg.length() > sizeLimit) {
        topLevelBuilder.add(chunkBuilder.build());
        chunkBuilder = ImmutableList.builder();
        chunkSize = 0;
      }
      // We don't check for an individual arg greater than the limit.
      // We just put it in its own chunk and hope for the best.
      chunkBuilder.add(arg);
      chunkSize += arg.length();
    }
    ImmutableList<String> tail = chunkBuilder.build();
    if (!tail.isEmpty()) {
      topLevelBuilder.add(tail);
    }
    return topLevelBuilder.build();
  }
}
