/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2019 The ZAP Development Team
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.zaproxy.gradle.addon.misc;

import com.github.zafarkhaja.semver.ParseException;
import com.github.zafarkhaja.semver.Version;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.zaproxy.gradle.addon.internal.tasks.UpdateChangelogNextDevIter;

/**
 * A task that prepares the next development iteration of an add-on.
 *
 * <p>Adds the Unreleased section and (optionally) the unreleased link to the changelog and bumps
 * the version in the build file.
 *
 * <p>The unreleased link can have a token to refer to current version, which is replaced when
 * adding the link.
 */
public abstract class PrepareAddOnNextDevIter extends UpdateChangelogNextDevIter {

    public static final String CURRENT_VERSION_TOKEN = "@CURRENT_VERSION@";

    private final RegularFileProperty buildFile;

    public PrepareAddOnNextDevIter() {
        ObjectFactory objects = getProject().getObjects();
        this.buildFile = objects.fileProperty();

        setGroup("ZAP Add-On Misc");
        setDescription("Prepares the next development iteration of the add-on.");
    }

    @InputFile
    @PathSensitive(PathSensitivity.NONE)
    public RegularFileProperty getBuildFile() {
        return buildFile;
    }

    @Override
    public void prepare() throws IOException {
        super.prepare();

        Path updatedBuildFile = updateBuildFile();

        Files.copy(
                updatedBuildFile,
                buildFile.getAsFile().get().toPath(),
                StandardCopyOption.REPLACE_EXISTING);
    }

    private Path updateBuildFile() throws IOException {
        Path buildFilePath = buildFile.getAsFile().get().toPath();
        Path updatedBuildFile =
                getTemporaryDir().toPath().resolve("updated-" + buildFilePath.getFileName());

        String currentVersionLine = versionLine(getCurrentVersion().get());
        String newVersion = bumpVersion(getCurrentVersion().get());

        boolean updateVersion = true;
        try (BufferedReader reader = Files.newBufferedReader(buildFilePath);
                BufferedWriter writer = Files.newBufferedWriter(updatedBuildFile)) {
            String line;
            while ((line = reader.readLine()) != null) {
                if (updateVersion && currentVersionLine.equals(line)) {
                    line = versionLine(newVersion);
                    updateVersion = false;
                }
                writer.write(line);
                writer.write("\n");
            }
        }

        if (updateVersion) {
            throw new InvalidUserDataException(
                    "Failed to update the version, current version line not found: "
                            + currentVersionLine);
        }

        return updatedBuildFile;
    }

    private static String versionLine(String version) {
        return "version = \"" + version + "\"";
    }

    private static String bumpVersion(String version) {
        try {
            int currentVersion = Integer.parseInt(version);
            return Integer.toString(++currentVersion);
        } catch (NumberFormatException e) {
            // Ignore, not an integer version.
        }

        try {
            return Version.parse(version).nextMinorVersion().toString();
        } catch (IllegalArgumentException | ParseException e) {
            throw new InvalidUserDataException(
                    "Failed to parse the current version: " + version, e);
        }
    }
}
