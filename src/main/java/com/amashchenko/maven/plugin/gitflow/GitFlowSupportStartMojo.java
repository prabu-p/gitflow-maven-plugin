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

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * The git flow support start mojo.
 *
 * @since 1.5.0
 */
@Mojo(name = "support-start", aggregator = true)
public class GitFlowSupportStartMojo extends AbstractGitFlowMojo {

    /**
     * Whether to push to the remote.
     *
     * @since 1.6.0
     */
    @Parameter(property = "pushRemote", defaultValue = "true")
    private boolean pushRemote;

    /**
     * Branch name to use instead of the default.
     *
     * @since 1.16.0
     */
    @Parameter(property = "sourceBranch")
    private String sourceBranch;

    /**
     * Support release version
     */
    @Parameter(property = "releaseVersion", defaultValue = "")
    private String releaseVersion = "";

    /**
     * Whether to use snapshot in support.
     *
     * @since 1.16.0
     */
    @Parameter(property = "useSnapshotInSupport", defaultValue = "false")
    private boolean useSnapshotInSupport;

    /**
     * Version digit to increment
     */
    @Parameter(property = "versionDigitToIncrement")
    private Integer versionDigitToIncrement = 3;

    /**
     * {@inheritDoc}
     */
    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        validateConfiguration();

        try {
            // set git flow configuration
            initGitFlowConfig();

            // check uncommitted changes
            checkUncommittedChanges();

            String tag = sourceBranch;
            if (settings.isInteractiveMode()) {
                // get tags
                String tagsStr = gitFindTags();

                if (StringUtils.isBlank(tagsStr)) {
                    throw new MojoFailureException("There are no tags.");
                }

                try {
                    tag = prompter.prompt("Choose tag to start support branch",
                        Arrays.asList(tagsStr.split("\\r?\\n")));
                } catch (PrompterException e) {
                    throw new MojoFailureException("support-start", e);
                }
            }
            if (StringUtils.isBlank(tag)) {
                throw new MojoFailureException("Tag is blank.");
            }

            if (!gitCheckTagExists(tag)) {
                throw new MojoFailureException("The tag '" + sourceBranch + "' doesn't exist.");
            }

            // Checkout tag
            gitCheckout(tag);

            String version = releaseVersion;
            if (StringUtils.isBlank(version)) {
                version = getCurrentProjectVersion();
                if (!tychoBuild) {
                    GitFlowVersionInfo versionInfo = new GitFlowVersionInfo(version);
                    version = versionInfo.digitsVersionInfo().getPaddedVersion(versionDigitToIncrement);
                    versionInfo = new GitFlowVersionInfo(version);
                    version = versionInfo.getNextVersion().getReleaseVersionString();
                }
                if (version == null) {
                    throw new MojoFailureException("Cannot get default project version.");
                }
                if (settings.isInteractiveMode()) {
                    version = getPromptReleaseVersion(version);
                }
            }
            String branchName = version;

            // git for-each-ref refs/heads/support/...
            final boolean supportBranchExists = gitCheckBranchExists(gitFlowConfig.getSupportBranchPrefix() + branchName);

            if (supportBranchExists) {
                throw new MojoFailureException("Support branch with that name already exists.");
            }

            // git checkout -b ... tag
            gitCreateAndCheckout(gitFlowConfig.getSupportBranchPrefix() + branchName, tag);
            if (useSnapshotInSupport && !ArtifactUtils.isSnapshot(version)) {
                version = version + "-" + Artifact.SNAPSHOT_VERSION;
            }

            getLog().info("Updating support version " + version);
            mvnSetVersions(version);
            Map<String, String> properties = new HashMap<String, String>();
            properties.put("version", version);
            gitCommit(commitMessages.getSupportStartMessage(), properties);

            if (installProject) {
                // mvn clean install
                mvnCleanInstall();
            }

            if (pushRemote) {
                gitPush(gitFlowConfig.getSupportBranchPrefix() + branchName, false);
            }
        } catch (CommandLineException e) {
            throw new MojoFailureException("support-start", e);
        } catch (VersionParseException e) {
            throw new MojoFailureException("support-start", e);
        }
    }
}
