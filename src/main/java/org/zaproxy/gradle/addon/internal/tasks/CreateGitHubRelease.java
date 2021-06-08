/*
 * Zed Attack Proxy (ZAP) and its related class files.
 *
 * ZAP is an HTTP/HTTPS proxy for assessing web application security.
 *
 * Copyright 2021 The ZAP Development Team
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
package org.zaproxy.gradle.addon.internal.tasks;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import org.gradle.api.Action;
import org.gradle.api.DefaultTask;
import org.gradle.api.InvalidUserDataException;
import org.gradle.api.Named;
import org.gradle.api.NamedDomainObjectContainer;
import org.gradle.api.Project;
import org.gradle.api.file.RegularFileProperty;
import org.gradle.api.model.ObjectFactory;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFile;
import org.gradle.api.tasks.Internal;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.Optional;
import org.gradle.api.tasks.PathSensitive;
import org.gradle.api.tasks.PathSensitivity;
import org.gradle.api.tasks.TaskAction;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.GHRelease;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.zaproxy.gradle.addon.internal.model.GitHubRepo;
import org.zaproxy.gradle.addon.internal.model.GitHubUser;

/** A task that creates a GitHub release. */
public abstract class CreateGitHubRelease extends DefaultTask {

    private final Property<String> tag;
    private final Property<String> title;
    private final Property<String> body;
    private final RegularFileProperty bodyFile;
    private NamedDomainObjectContainer<Asset> assets;

    public CreateGitHubRelease() {
        ObjectFactory objects = getProject().getObjects();
        this.tag = objects.property(String.class);
        this.title = objects.property(String.class);
        this.body = objects.property(String.class);
        this.bodyFile = objects.fileProperty();
        this.assets = getProject().container(Asset.class, label -> new Asset(label, getProject()));
    }

    @Input
    public abstract Property<GitHubUser> getUser();

    @Input
    public abstract Property<GitHubRepo> getRepo();

    @Input
    public Property<String> getTag() {
        return tag;
    }

    @Input
    public Property<String> getTitle() {
        return title;
    }

    @Input
    @Optional
    public Property<String> getBody() {
        return body;
    }

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    public RegularFileProperty getBodyFile() {
        return bodyFile;
    }

    @Nested
    @Optional
    public Iterable<Asset> getAssets() {
        return new ArrayList<>(assets);
    }

    public void setAssets(NamedDomainObjectContainer<Asset> assets) {
        this.assets = assets;
    }

    public void assets(Action<? super NamedDomainObjectContainer<Asset>> action) {
        action.execute(assets);
    }

    @TaskAction
    public void createRelease() throws IOException {
        if (getBodyFile().isPresent() && getBody().isPresent()) {
            throw new InvalidUserDataException("Only one type of body property must be set.");
        }

        GitHubUser user = getUser().get();
        GHRepository ghRepo =
                GitHub.connect(user.getName(), user.getAuthToken())
                        .getRepository(getRepo().get().toString());

        validateTagExists(ghRepo, tag.get());
        validateReleaseDoesNotExist(ghRepo, tag.get());

        String releaseBody =
                getBodyFile().isPresent()
                        ? readContents(getBodyFile().getAsFile().get().toPath())
                        : body.get();

        GHRelease release =
                ghRepo.createRelease(tag.get())
                        .name(title.get())
                        .body(releaseBody)
                        .draft(true)
                        .create();

        for (Asset asset : assets) {
            release.uploadAsset(asset.getFile().getAsFile().get(), asset.getContentType().get());
        }

        release.update().draft(false).update();
    }

    private static void validateTagExists(GHRepository repo, String tag) throws IOException {
        try {
            repo.getRef("tags/" + tag);
        } catch (GHFileNotFoundException e) {
            throw new InvalidUserDataException("Tag does not exist: " + tag, e);
        }
    }

    private static void validateReleaseDoesNotExist(GHRepository repo, String tag)
            throws IOException {
        GHRelease release = repo.getReleaseByTagName(tag);
        if (release != null) {
            throw new InvalidUserDataException(
                    "Release for tag " + tag + " already exists: " + release.getHtmlUrl());
        }
    }

    private static String readContents(Path file) throws IOException {
        return new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
    }

    public static final class Asset implements Named {

        private final String label;
        private final RegularFileProperty file;
        private final Property<String> contentType;

        public Asset(String label, Project project) {
            this.label = label;

            ObjectFactory objectFactory = project.getObjects();
            this.file = objectFactory.fileProperty();
            this.contentType =
                    objectFactory.property(String.class).value("application/octet-stream");
        }

        @Internal
        @Override
        public String getName() {
            return label;
        }

        @InputFile
        @PathSensitive(PathSensitivity.NONE)
        public RegularFileProperty getFile() {
            return file;
        }

        @Input
        public Property<String> getContentType() {
            return contentType;
        }
    }
}
