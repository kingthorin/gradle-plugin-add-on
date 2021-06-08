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

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.function.BiConsumer;
import org.eclipse.jgit.api.Git;
import org.eclipse.jgit.api.errors.GitAPIException;
import org.eclipse.jgit.diff.DiffEntry;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.ObjectReader;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.revwalk.RevCommit;
import org.eclipse.jgit.revwalk.RevTree;
import org.eclipse.jgit.revwalk.RevWalk;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.treewalk.AbstractTreeIterator;
import org.eclipse.jgit.treewalk.CanonicalTreeParser;
import org.eclipse.jgit.treewalk.TreeWalk;
import org.eclipse.jgit.treewalk.filter.PathFilterGroup;
import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.Nested;
import org.gradle.api.tasks.TaskAction;
import org.zaproxy.gradle.addon.internal.BuildException;
import org.zaproxy.gradle.addon.internal.model.ProjectInfo;
import org.zaproxy.gradle.addon.internal.model.ReleaseState;

/**
 * A task that generates the release state of the last commit, allowing to know what was released
 * (if anything).
 */
public abstract class GenerateReleaseStateLastCommit extends DefaultTask {

    private static final String VERSION_PROPERTY = "version";
    private static final String RELEASE_PROPERTY = "release";

    private static final String GIT_DIR = ".git";
    private static final String HEAD_REF = "HEAD";

    public GenerateReleaseStateLastCommit() {
        getGitDir().set(new File(getProject().getRootDir(), GIT_DIR));
    }

    @InputDirectory
    public abstract DirectoryProperty getGitDir();

    @Nested
    public abstract ListProperty<ProjectInfo> getProjects();

    @TaskAction
    void generate() {
        File gitDir = getGitDir().get().getAsFile();

        try (Repository repository = createRepository(gitDir)) {
            ObjectId head = getHead(repository).getObjectId();
            for (ProjectInfo project : getProjects().get()) {
                ReleaseState releaseState = new ReleaseState();
                readProperties(
                        repository,
                        head,
                        project.getPropertiesPath().get(),
                        (previousProperties, currentProperties) -> {
                            releaseState.setPreviousVersion(
                                    previousProperties.getProperty(VERSION_PROPERTY));
                            releaseState.setCurrentVersion(
                                    currentProperties.getProperty(VERSION_PROPERTY));
                            releaseState.setPreviousRelease(
                                    Boolean.parseBoolean(
                                            previousProperties.getProperty(RELEASE_PROPERTY)));
                            releaseState.setCurrentRelease(
                                    Boolean.parseBoolean(
                                            currentProperties.getProperty(RELEASE_PROPERTY)));
                        });

                releaseState.write(project.getOutputFile().getAsFile().get());
            }
        }
    }

    private static void readProperties(
            Repository repository,
            ObjectId head,
            String pathProperties,
            BiConsumer<Properties, Properties> propertiesConsumer) {
        try (RevWalk walk = new RevWalk(repository)) {
            RevCommit headCommit = walk.parseCommit(head);

            walk.markStart(headCommit);
            Properties currentProperties = null;
            Iterator<RevCommit> it = walk.iterator();
            if (it.hasNext()) {
                try (TreeWalk treeWalk = new TreeWalk(repository)) {
                    treeWalk.addTree(walk.parseTree(it.next().getTree().getId()));
                    treeWalk.setRecursive(true);
                    treeWalk.setFilter(PathFilterGroup.createFromStrings(pathProperties));
                    if (treeWalk.next()) {
                        currentProperties = createProperties(repository, treeWalk.getObjectId(0));
                    }
                }
            }
            if (currentProperties == null) {
                throw new BuildException("File not found in the current commit: " + pathProperties);
            }

            RevCommit parent;
            if (isMergeCommit(headCommit)) {
                parent =
                        getCommonAncestor(
                                repository,
                                headCommit.getParent(0).getId(),
                                headCommit.getParent(1).getId());
            } else {
                parent = headCommit.getParent(0);
            }

            Properties previousProperties = currentProperties;
            java.util.Optional<DiffEntry> diffResult =
                    isFileChanged(repository, parent, headCommit, pathProperties);
            if (diffResult.isPresent()) {
                previousProperties =
                        createProperties(repository, diffResult.get().getOldId().toObjectId());
            }

            propertiesConsumer.accept(previousProperties, currentProperties);
        } catch (IOException e) {
            throw new BuildException(
                    "An error occurred while using the Git repository: " + e.getMessage(), e);
        }
    }

    private static Repository createRepository(File projectDir) {
        try {
            return new FileRepositoryBuilder().setGitDir(projectDir).build();
        } catch (IOException e) {
            throw new BuildException("Failed to read the Git repository: " + e.getMessage(), e);
        }
    }

    private static Ref getHead(Repository repository) {
        Ref head;
        try {
            head = repository.findRef(HEAD_REF);
        } catch (IOException e) {
            throw new BuildException(
                    String.format(
                            "Failed to get the ref %s from the Git repository: %s",
                            HEAD_REF, e.getMessage()),
                    e);
        }
        if (head == null) {
            throw new BuildException(
                    String.format("No ref %s found in the Git repository.", HEAD_REF));
        }
        return head;
    }

    private static boolean isMergeCommit(RevCommit commit) {
        return commit.getParentCount() > 1;
    }

    private static Properties createProperties(Repository repository, ObjectId objectId) {
        Properties config = new Properties();
        try (InputStream in = repository.open(objectId).openStream()) {
            config.load(in);
        } catch (IOException e) {
            throw new BuildException(
                    "Failed to read the file from the Git repository: " + e.getMessage(), e);
        }
        return config;
    }

    private static RevCommit getCommonAncestor(
            Repository repository, ObjectId commitA, ObjectId commitB) {
        List<RevCommit> treeA = walkTree(repository, commitA, 50);
        List<RevCommit> treeB = walkTree(repository, commitB, 50);

        Set<RevCommit> common = new HashSet<>(treeA);
        for (RevCommit commit : treeB) {
            if (!common.add(commit)) {
                return commit;
            }
        }

        throw new BuildException(
                "Common ancestor not found between " + commitA + " and " + commitB);
    }

    private static List<RevCommit> walkTree(Repository repository, ObjectId start, int count) {
        List<RevCommit> commits = new ArrayList<>();
        try (RevWalk revWalk = new RevWalk(repository)) {
            revWalk.markStart(revWalk.parseCommit(start));
            for (RevCommit commit : revWalk) {
                commits.add(commit);
                if (commits.size() >= count) {
                    break;
                }
            }
        } catch (IOException e) {
            throw new BuildException(
                    "An error occurred while traversing the commit tree: " + e.getMessage(), e);
        }
        return commits;
    }

    private static AbstractTreeIterator prepareTreeParser(Repository repository, ObjectId objectId)
            throws IOException {
        try (RevWalk walk = new RevWalk(repository)) {
            RevTree tree = walk.parseTree(walk.parseCommit(objectId).getTree().getId());

            CanonicalTreeParser treeParser = new CanonicalTreeParser();
            try (ObjectReader reader = repository.newObjectReader()) {
                treeParser.reset(reader, tree.getId());
            }
            return treeParser;
        }
    }

    private static java.util.Optional<DiffEntry> isFileChanged(
            Repository repository, RevCommit commitA, RevCommit commitB, String filePath) {
        try (Git git = new Git(repository)) {
            AbstractTreeIterator oldTree = prepareTreeParser(repository, commitA);
            AbstractTreeIterator newTree = prepareTreeParser(repository, commitB);
            return git.diff().setOldTree(oldTree).setNewTree(newTree).call().stream()
                    .filter(
                            e ->
                                    e.getChangeType() == DiffEntry.ChangeType.MODIFY
                                            && e.getNewPath().equals(filePath))
                    .findFirst();

        } catch (GitAPIException | IOException e) {
            throw new BuildException(
                    "An error occurred while diffing the commits: " + e.getMessage(), e);
        }
    }
}
