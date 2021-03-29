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
import org.apache.maven.shared.release.versions.VersionParseException;
import org.codehaus.plexus.components.interactivity.PrompterException;
import org.codehaus.plexus.util.StringUtils;
import org.codehaus.plexus.util.cli.CommandLineException;

import java.util.HashMap;
import java.util.Map;

/**
 * The git flow release finish mojo.
 */
@Mojo(name = "release-update", aggregator = true)
public class GitFlowReleaseUpdateMojo extends AbstractGitFlowMojo {

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
     * Whether to rebase branch or merge. If <code>true</code> then rebase will be performed.
     *
     * @since 1.2.3
     */
    @Parameter(property = "releaseRebase", defaultValue = "false")
    private boolean releaseRebase = false;

    /**
     * Whether to use <code>--no-ff</code> option when merging.
     *
     * @since 1.2.3
     */
    @Parameter(property = "releaseMergeNoFF", defaultValue = "true")
    private boolean releaseMergeNoFF = true;

    /**
     * Whether to push to the remote.
     *
     * @since 1.3.0
     */
    @Parameter(property = "pushRemote", defaultValue = "true")
    private boolean pushRemote;

    /**
     * Whether to use <code>--ff-only</code> option when merging.
     *
     * @since 1.4.0
     */
    @Parameter(property = "releaseMergeFFOnly", defaultValue = "false")
    private boolean releaseMergeFFOnly = false;

    /**
     * Whether to remove qualifiers from the next development version.
     *
     * @since 1.6.0
     */
    @Parameter(property = "digitsOnlyDevVersion", defaultValue = "false")
    private boolean digitsOnlyDevVersion = false;

    /**
     * Development version to use instead of the default next development version in non interactive mode.
     *
     * @since 1.6.0
     */
    @Parameter(property = "developmentVersion", defaultValue = "")
    private String developmentVersion = "";

    /**
     * Release version to use instead of the default next release version in non interactive mode.
     *
     * @since 1.3.1
     */
    @Parameter(property = "releaseVersion", defaultValue = "")
    private String releaseVersion = "";

    /**
     * Which digit to increment in the next development version. Starts from zero.
     *
     * @since 1.6.0
     */
    @Parameter(property = "versionDigitToIncrement")
    private Integer versionDigitToIncrement;


    /**
     * Maven goals to execute in the release branch before merging into the production branch.
     *
     * @since 1.8.0
     */
    @Parameter(property = "preReleaseGoals")
    private String preReleaseGoals;

    /**
     * Maven goals to execute in the production branch after the release.
     *
     * @since 1.8.0
     */
    @Parameter(property = "postReleaseGoals")
    private String postReleaseGoals;

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
    @Parameter(property = "useSnapshotInRelease", defaultValue = "false")
    private boolean useSnapshotInRelease;

    /**
     * Whether to skip merging release into the production branch.
     *
     * @since 1.15.0
     */
    @Parameter(property = "skipReleaseMergeProdBranch", defaultValue = "true")
    private boolean skipReleaseMergeProdBranch = true;

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        validateConfiguration(preReleaseGoals, postReleaseGoals);

        try {
            // check uncommitted changes
            checkUncommittedChanges();

            // git for-each-ref --format='%(refname:short)' refs/heads/release/*
            String releaseBranch = gitFindBranches(gitFlowConfig.getReleaseBranchPrefix(), false).trim();

            if (StringUtils.isBlank(releaseBranch)) {
                if (fetchRemote) {
                    releaseBranch = gitFetchAndFindRemoteBranches(gitFlowConfig.getOrigin(),
                        gitFlowConfig.getReleaseBranchPrefix(), false).trim();
                    if (StringUtils.isBlank(releaseBranch)) {
                        throw new MojoFailureException("There is no remote or local release branch.");
                    }

                    // remove remote name with slash from branch name
                    releaseBranch = releaseBranch.substring(gitFlowConfig.getOrigin().length() + 1);

                    if (StringUtils.countMatches(releaseBranch, gitFlowConfig.getReleaseBranchPrefix()) > 1) {
                        throw new MojoFailureException("More than one remote release branch exists. Cannot finish release.");
                    }

                    gitCreateAndCheckout(releaseBranch, gitFlowConfig.getOrigin() + "/" + releaseBranch);
                } else {
                    throw new MojoFailureException("There is no release branch.");
                }
            }
            if (StringUtils.countMatches(releaseBranch, gitFlowConfig.getReleaseBranchPrefix()) > 1) {
                throw new MojoFailureException("More than one release branch exists. Cannot finish release.");
            }

            // check snapshots dependencies
            if (!allowSnapshots) {
                gitCheckout(releaseBranch);
                checkSnapshotDependencies();
            }

            if (fetchRemote) {
                // fetch and check remote
                gitFetchRemoteAndCompare(releaseBranch);

                if (notSameProdDevName()) {
                    // checkout from remote if doesn't exist
                    gitFetchRemoteAndCreate(gitFlowConfig.getProductionBranch());

                    // fetch and check remote
                    gitFetchRemoteAndCompare(gitFlowConfig.getProductionBranch());
                }
            }

            // git checkout release/...
            gitCheckout(releaseBranch);

            if (!skipTestProject) {
                // mvn clean test
                mvnCleanTest();
            }

            // maven goals before merge
            if (StringUtils.isNotBlank(preReleaseGoals)) {
                mvnRun(preReleaseGoals);
            }

            String currentReleaseVersion = getReleaseVersion();
            System.out.println("Current Release version : " + currentReleaseVersion);

            Map<String, String> messageProperties = new HashMap<>();
            messageProperties.put("version", currentReleaseVersion);

            if (ArtifactUtils.isSnapshot(currentReleaseVersion)) {
                currentReleaseVersion = currentReleaseVersion.replace("-" + Artifact.SNAPSHOT_VERSION, "");
            }

            mvnSetVersions(currentReleaseVersion);
            messageProperties.put("version", currentReleaseVersion);
            gitCommit(commitMessages.getReleaseFinishMessage(), messageProperties);

            // get current project version from pom
            final String currentVersion = getCurrentProjectVersion();

            if (!skipTag) {
                String tagVersion = currentVersion;
                if ((tychoBuild || useSnapshotInRelease) && ArtifactUtils.isSnapshot(currentVersion)) {
                    tagVersion = currentVersion.replace("-" + Artifact.SNAPSHOT_VERSION, "");
                }

                messageProperties.put("version", tagVersion);

                // git tag -a ...
                gitTag(gitFlowConfig.getVersionTagPrefix() + tagVersion, commitMessages.getTagReleaseMessage(), gpgSignTag, messageProperties);
            }

            // maven goals after merge
            if (StringUtils.isNotBlank(postReleaseGoals)) {
                mvnRun(postReleaseGoals);
            }

            // get next snapshot version
            GitFlowVersionInfo versionInfo = new GitFlowVersionInfo(currentVersion);
            if (digitsOnlyDevVersion) {
                versionInfo = versionInfo.digitsVersionInfo();
            }
            final String nextSnapshotVersion = versionInfo.nextSnapshotVersion(versionDigitToIncrement);

            if (StringUtils.isBlank(nextSnapshotVersion)) {
                throw new MojoFailureException("Next snapshot version is blank.");
            }

            // mvn versions:set -DnewVersion=... -DgenerateBackupPoms=false
            mvnSetVersions(nextSnapshotVersion);

            messageProperties.put("version", nextSnapshotVersion);

            // git commit -a -m updating for next development version
            gitCommit(commitMessages.getReleaseFinishMessage(), messageProperties);

            if (installProject) {
                // mvn clean install
                mvnCleanInstall();
            }

            if (pushRemote) {
                gitPush(releaseBranch, !skipTag);
            }
        } catch (Exception e) {
            e.printStackTrace();
            throw new MojoFailureException("release-update", e);
        }
    }

    private String getReleaseVersion() throws MojoFailureException, VersionParseException, CommandLineException {
        // get current project version from pom
        final String currentVersion = getCurrentProjectVersion();

        String defaultVersion = null;
        if (tychoBuild) {
            defaultVersion = currentVersion;
        } else {
            // get default release version
            defaultVersion = new GitFlowVersionInfo(currentVersion)
                .getReleaseVersionString();
        }

        if (defaultVersion == null) {
            throw new MojoFailureException(
                "Cannot get default project version.");
        }

        String version = null;
        if (settings.isInteractiveMode()) {
            try {
                while (version == null) {
                    version = prompter.prompt("What is release version? ["
                        + defaultVersion + "]");

                    if (!"".equals(version)
                        && (!GitFlowVersionInfo.isValidVersion(version) || !validBranchName(version))) {
                        getLog().info("The version is not valid.");
                        version = null;
                    }
                }
            } catch (PrompterException e) {
                throw new MojoFailureException("release-start", e);
            }
        } else {
            version = releaseVersion;
        }

        if (StringUtils.isBlank(version)) {
            getLog().info("Version is blank. Using default version.");
            version = defaultVersion;
        }

        return version;
    }
}
