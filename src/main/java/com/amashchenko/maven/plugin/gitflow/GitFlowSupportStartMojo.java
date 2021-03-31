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
     * Tag name to use in non-interactive mode.
     *
     * @since 1.9.0
     */
    @Parameter(property = "tagName")
    private String tagName;

    /**
     * Branch name to use instead of the default.
     *
     * @since 1.16.0
     */
    @Parameter(property = "supportBranch")
    private String supportBranch;

    /**
     * Support version
     */
    @Parameter(property = "supportVersion", defaultValue = "")
    private String supportVersion = "";

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

            String tag = tagName;
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
                throw new MojoFailureException("The tag '" + tagName + "' doesn't exist.");
            }

            // Checkout tag
            gitCheckout(tag);

            String version = getReleaseVersion();
            String branchName = version;
            if (StringUtils.isNotBlank(supportBranch)) {
                branchName = supportBranch;
            }

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

    private String getReleaseVersion() throws MojoFailureException, VersionParseException, CommandLineException {
        // get current project version from pom
        final String currentVersion = getCurrentProjectVersion();
        String defaultVersion = null;
        if (tychoBuild) {
            defaultVersion = currentVersion;
        } else {
            // get default release version
            GitFlowVersionInfo versionInfo = new GitFlowVersionInfo(currentVersion).digitsVersionInfo();
            defaultVersion = versionInfo.getReleaseVersionString();
            int i = versionDigitToIncrement;
            if (i > versionInfo.getDigits().size()) {
                while (i > versionInfo.getDigits().size()) {
                    defaultVersion = defaultVersion + ".0";
                    i--;
                }
                versionInfo = new GitFlowVersionInfo(defaultVersion);
            }
            defaultVersion = versionInfo.getNextVersion().getReleaseVersionString();
        }

        if (defaultVersion == null) {
            throw new MojoFailureException("Cannot get default project version.");
        }

        String version = null;
        if (settings.isInteractiveMode()) {
            try {
                while (version == null) {
                    version = prompter.prompt("What is release version? [" + defaultVersion + "]");
                    if (!"".equals(version) && (!GitFlowVersionInfo.isValidVersion(version) || !validBranchName(version))) {
                        getLog().info("The version is not valid.");
                        version = null;
                    }
                }
            } catch (PrompterException e) {
                throw new MojoFailureException("support-start", e);
            }
        } else {
            version = supportVersion;
        }

        if (StringUtils.isBlank(version)) {
            getLog().info("Version is blank. Using default version " + defaultVersion);
            version = defaultVersion;
        }
        return version;
    }
}
