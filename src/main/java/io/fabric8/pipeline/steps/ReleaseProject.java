/**
 * Copyright (C) Original Authors 2017
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.fabric8.pipeline.steps;

import io.fabric8.Fabric8FunctionSupport;
import io.jenkins.functions.runtime.FunctionSupport;
import io.fabric8.pipeline.steps.model.ServiceConstants;
import io.fabric8.pipeline.steps.model.StagedProjectInfo;
import io.fabric8.utils.Strings;
import io.jenkins.functions.Argument;
import io.jenkins.functions.Logger;
import io.jenkins.functions.Step;
import org.kohsuke.github.GHPullRequest;

import javax.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

@Step(displayName = "Releases a staged release promoting artifacts and docker images and waiting until artifacts are synced to the central repository")
public class ReleaseProject extends Fabric8FunctionSupport implements Function<ReleaseProject.Arguments, Boolean> {
    public ReleaseProject() {
    }

    public ReleaseProject(FunctionSupport parentStep) {
        super(parentStep);
    }

    @Override
    @Step
    public Boolean apply(Arguments config) {
        GHPullRequest pullRequest = new PromoteArtifacts(this).apply(config.createPromoteArtifactsArguments());

        PromoteImages.Arguments promoteImagesArgs = config.createPromoteImagesArguments(getLogger());
        if (promoteImagesArgs != null) {
            new PromoteImages(this).apply(promoteImagesArgs);
        }

        TagImages.Arguments tagImagesArguments = config.createTagImagesArguments();
        if (tagImagesArguments != null) {
            new TagImages(this).apply(tagImagesArguments);
        }

        if (pullRequest != null) {
            WaitUntilPullRequestMerged.Arguments waitUntilPullRequestMergedArguments = config.createWaitUntilPullRequestMergedArguments(pullRequest);
            new WaitUntilPullRequestMerged(this).apply(waitUntilPullRequestMergedArguments);
        }

        WaitUntilArtifactSyncedWithCentral.Arguments waitUntilArtifactSyncedWithCentralArguments = config.createWaitUntilArtifactSyncedWithCentralArguments(getLogger());
        if (waitUntilArtifactSyncedWithCentralArguments != null) {
            new WaitUntilArtifactSyncedWithCentral(this).apply(waitUntilArtifactSyncedWithCentralArguments);
        }
        return true;
    }

    public static class Arguments {
        @Argument
        @NotEmpty
        private String project = "";
        @Argument
        @NotEmpty
        private String releaseVersion = "";
        @Argument
        private List<String> repoIds = new ArrayList<>();
        @Argument
        private String containerName = "maven";
        @Argument
        private String dockerOrganisation = "";
        @Argument
        private String promoteToDockerRegistry = "";
        @Argument
        private List<String> promoteDockerImages = new ArrayList<>();
        @Argument
        private List<String> extraImagesToTag = new ArrayList<>();
        @Argument
        private String repositoryToWaitFor = ServiceConstants.MAVEN_CENTRAL;
        @Argument
        private String groupId = "";
        @Argument
        private String artifactExtensionToWaitFor = "";
        @Argument
        private String artifactIdToWaitFor = "";

        public Arguments() {
        }

        public Arguments(StagedProjectInfo stagedProject) {
            this.project = stagedProject.getProject();
            this.releaseVersion = stagedProject.getReleaseVersion();
            this.repoIds = stagedProject.getRepoIds();
        }

        @Override
        public String toString() {
            return "Arguments{" +
                    "project=" + project +
                    ", releaseVersion='" + releaseVersion + '\'' +
                    ", repoIds='" + repoIds + '\'' +
                    ", containerName='" + containerName + '\'' +
                    ", dockerOrganisation='" + dockerOrganisation + '\'' +
                    ", promoteToDockerRegistry='" + promoteToDockerRegistry + '\'' +
                    ", promoteDockerImages=" + promoteDockerImages +
                    ", extraImagesToTag=" + extraImagesToTag +
                    ", repositoryToWaitFor='" + repositoryToWaitFor + '\'' +
                    ", groupId='" + groupId + '\'' +
                    ", artifactExtensionToWaitFor='" + artifactExtensionToWaitFor + '\'' +
                    ", artifactIdToWaitFor='" + artifactIdToWaitFor + '\'' +
                    '}';
        }

        /**
         * Returns the arguments for invoking {@link PromoteArtifacts}
         */
        public PromoteArtifacts.Arguments createPromoteArtifactsArguments() {
            return new PromoteArtifacts.Arguments(getProject(), getReleaseVersion(), getRepoIds());
        }

        /**
         * Return the arguments for invoking {@link PromoteImages} or null if there is not sufficient configuration
         * to promote images
         */
        public PromoteImages.Arguments createPromoteImagesArguments(Logger logger) {
            String org = getDockerOrganisation();
            String toRegistry = getPromoteToDockerRegistry();
            List<String> images = getPromoteDockerImages();
            if (images != null && !images.isEmpty()) {
                if (Strings.isNullOrBlank(org)) {
                    logger.warn("Cannot promote images " + images + " as missing the dockerOrganisation argument: " + this);
                    return null;
                }
                if (Strings.isNullOrBlank(toRegistry)) {
                    logger.warn("Cannot promote images " + images + " as missing the promoteToDockerRegistry argument: " + this);
                    return null;
                }
                return new PromoteImages.Arguments(getReleaseVersion(), org, toRegistry, images);
            }
            return null;
        }

        /**
         * Returns the arguments for invoking {@link TagImages} or null if there are no images to tag
         */
        public TagImages.Arguments createTagImagesArguments() {
            if (extraImagesToTag != null && !extraImagesToTag.isEmpty()) {
                return new TagImages.Arguments(getReleaseVersion(), extraImagesToTag);
            } else {
                return null;
            }
        }

        /**
         * Returns the arguments for invoking {@link WaitUntilPullRequestMerged}
         *
         * @param pullRequestId
         */
        public WaitUntilPullRequestMerged.Arguments createWaitUntilPullRequestMergedArguments(GHPullRequest pullRequestId) {
            return new WaitUntilPullRequestMerged.Arguments(pullRequestId.getId(), getProject());
        }

        /**
         * Returns the arguments for invoking {@link WaitUntilArtifactSyncedWithCentral}
         */
        public WaitUntilArtifactSyncedWithCentral.Arguments createWaitUntilArtifactSyncedWithCentralArguments(Logger logger) {
            if (Strings.isNullOrBlank(groupId) || Strings.isNullOrBlank(artifactIdToWaitFor)) {
                logger.warn("Cannot wait for artifacts to be synced to central repository as require groupId and artifactIdToWaitFor properties. Was given " + this);
                return null;
            }

            WaitUntilArtifactSyncedWithCentral.Arguments arguments = new WaitUntilArtifactSyncedWithCentral.Arguments(groupId, artifactIdToWaitFor, getReleaseVersion());
            if (Strings.notEmpty(artifactExtensionToWaitFor)) {
                arguments.setExt(artifactExtensionToWaitFor);
            }
            if (Strings.notEmpty(repositoryToWaitFor)) {
                arguments.setRepo(repositoryToWaitFor);
            }
            return arguments;
        }

        // Properties
        //-------------------------------------------------------------------------

        public String getProject() {
            return project;
        }

        public void setProject(String project) {
            this.project = project;
        }

        public String getReleaseVersion() {
            return releaseVersion;
        }

        public void setReleaseVersion(String releaseVersion) {
            this.releaseVersion = releaseVersion;
        }

        public List<String> getRepoIds() {
            return repoIds;
        }

        public void setRepoIds(List<String> repoIds) {
            this.repoIds = repoIds;
        }

        public String getContainerName() {
            return containerName;
        }

        public void setContainerName(String containerName) {
            this.containerName = containerName;
        }

        public String getDockerOrganisation() {
            return dockerOrganisation;
        }

        public void setDockerOrganisation(String dockerOrganisation) {
            this.dockerOrganisation = dockerOrganisation;
        }

        public String getPromoteToDockerRegistry() {
            return promoteToDockerRegistry;
        }

        public void setPromoteToDockerRegistry(String promoteToDockerRegistry) {
            this.promoteToDockerRegistry = promoteToDockerRegistry;
        }

        public List<String> getPromoteDockerImages() {
            return promoteDockerImages;
        }

        public void setPromoteDockerImages(List<String> promoteDockerImages) {
            this.promoteDockerImages = promoteDockerImages;
        }

        public List<String> getExtraImagesToTag() {
            return extraImagesToTag;
        }

        public void setExtraImagesToTag(List<String> extraImagesToTag) {
            this.extraImagesToTag = extraImagesToTag;
        }

        public String getRepositoryToWaitFor() {
            return repositoryToWaitFor;
        }

        public void setRepositoryToWaitFor(String repositoryToWaitFor) {
            this.repositoryToWaitFor = repositoryToWaitFor;
        }

        public String getGroupId() {
            return groupId;
        }

        public void setGroupId(String groupId) {
            this.groupId = groupId;
        }

        public String getArtifactExtensionToWaitFor() {
            return artifactExtensionToWaitFor;
        }

        public void setArtifactExtensionToWaitFor(String artifactExtensionToWaitFor) {
            this.artifactExtensionToWaitFor = artifactExtensionToWaitFor;
        }

        public String getArtifactIdToWaitFor() {
            return artifactIdToWaitFor;
        }

        public void setArtifactIdToWaitFor(String artifactIdToWaitFor) {
            this.artifactIdToWaitFor = artifactIdToWaitFor;
        }
    }
}
