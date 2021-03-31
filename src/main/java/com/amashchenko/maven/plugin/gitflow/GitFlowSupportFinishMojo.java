/*
 * Copyright 2014-2021 Aleksandr Mashchenko.
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
package com.amashchenko.maven.plugin.gitflow;

import org.apache.maven.artifact.Artifact;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.codehaus.plexus.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

/**
 * The git flow release finish mojo.
 */
@Mojo(name = "support-finish", aggregator = true)
public class GitFlowSupportFinishMojo extends AbstractGitFlowMojo {

    /**
     * Whether to skip tagging the release in Git.
     */
    @Parameter(property = "skipTag", defaultValue = "false")
    private boolean skipTag = false;

    /**
     * Whether to keep release branch after finish.
     */
    @Parameter(property = "keepBranch", defaultValue = "false")
    private boolean keepBranch = false;

    /**
     * Whether to skip calling Maven test goal before merging the branch.
     *
     * @since 1.0.5
     */
    @Parameter(property = "skipTestProject", defaultValue = "false")
    private boolean skipTestProject = false;

    /**
     * Whether to allow SNAPSHOT versions in dependencies.
     *
     * @since 1.2.2
     */
    @Parameter(property = "allowSnapshots", defaultValue = "false")
    private boolean allowSnapshots = false;

    /**
     * Whether to push to the remote.
     *
     * @since 1.3.0
     */
    @Parameter(property = "pushRemote", defaultValue = "true")
    private boolean pushRemote;

    /**
     * Whether to remove qualifiers from the next development version.
     *
     * @since 1.6.0
     */
    @Parameter(property = "digitsOnlyDevVersion", defaultValue = "false")
    private boolean digitsOnlyDevVersion = false;


    /**
     * Maven goals to execute in the support branch.
     *
     * @since 1.8.0
     */
    @Parameter(property = "preSupportGoals")
    private String preSupportGoals;

    /**
     * Maven goals to execute in the support branch after the release.
     *
     * @since 1.8.0
     */
    @Parameter(property = "postSupportGoals")
    private String postSupportGoals;

    /**
     * Whether to make a GPG-signed tag.
     *
     * @since 1.9.0
     */
    @Parameter(property = "gpgSignTag", defaultValue = "false")
    private boolean gpgSignTag = false;

    /**
     * Whether to use snapshot in release.
     *
     * @since 1.10.0
     */
    @Parameter(property = "useSnapshotInSupport", defaultValue = "false")
    private boolean useSnapshotInSupport;

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        validateConfiguration(preSupportGoals, postSupportGoals);

        try {
            // check uncommitted changes
            checkUncommittedChanges();

            // git for-each-ref --format='%(refname:short)' refs/heads/support/*
            String supportBranch = gitFindBranches(gitFlowConfig.getSupportBranchPrefix(), false).trim();

            if (StringUtils.isBlank(supportBranch)) {
                if (fetchRemote) {
                    supportBranch = gitFetchAndFindRemoteBranches(gitFlowConfig.getOrigin(),
                        gitFlowConfig.getSupportBranchPrefix(), false).trim();
                    if (StringUtils.isBlank(supportBranch)) {
                        throw new MojoFailureException("There is no remote or local support branch.");
                    }

                    // remove remote name with slash from branch name
                    supportBranch = supportBranch.substring(gitFlowConfig.getOrigin().length() + 1);
                    gitCreateAndCheckout(supportBranch, gitFlowConfig.getOrigin() + "/" + supportBranch);
                } else {
                    throw new MojoFailureException("There is no support branch.");
                }
            }

            gitCheckout(supportBranch);

            // check snapshots dependencies
            if (!allowSnapshots) {

                checkSnapshotDependencies();
            }

            if (fetchRemote) {
                // fetch and check remote
                gitFetchRemoteAndCompare(supportBranch);
            }

            if (!skipTestProject) {
                // mvn clean test
                mvnCleanTest();
            }

            // maven goals before merge
            if (StringUtils.isNotBlank(preSupportGoals)) {
                mvnRun(preSupportGoals);
            }

            String currentReleaseVersion = getCurrentProjectVersion();

            Map<String, String> messageProperties = new HashMap<String, String>();
            messageProperties.put("version", currentReleaseVersion);

            if (useSnapshotInSupport && ArtifactUtils.isSnapshot(currentReleaseVersion)) {
                String commitVersion = currentReleaseVersion.replace("-" + Artifact.SNAPSHOT_VERSION, "");

                mvnSetVersions(commitVersion);

                messageProperties.put("version", commitVersion);

                gitCommit(commitMessages.getSupportStartMessage(), messageProperties);
            }

            // get current project version from pom
            final String currentVersion = getCurrentProjectVersion();

            if (!skipTag) {
                String tagVersion = currentVersion;
                if ((tychoBuild || useSnapshotInSupport) && ArtifactUtils.isSnapshot(currentVersion)) {
                    tagVersion = currentVersion.replace("-" + Artifact.SNAPSHOT_VERSION, "");
                }

                messageProperties.put("version", tagVersion);

                // git tag -a ...
                gitTag(gitFlowConfig.getVersionTagPrefix() + tagVersion, commitMessages.getTagSupportMessage(), gpgSignTag, messageProperties);
            }

            // maven goals after merge
            if (StringUtils.isNotBlank(postSupportGoals)) {
                mvnRun(postSupportGoals);
            }

            if (installProject) {
                // mvn clean install
                mvnCleanInstall();
            }

            if (pushRemote) {
                gitPush(supportBranch, !skipTag);
            }

            // Checkout prod branch
            gitFetchRemoteAndCreate(gitFlowConfig.getProductionBranch());
            if (!keepBranch) {
                // git branch -d support/...
                gitBranchDelete(supportBranch);
            }
        } catch (Exception e) {
            throw new MojoFailureException("support-finish", e);
        }
    }
}
