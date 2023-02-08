package org.jboss.bacon.experimental.impl.generator;

import lombok.extern.slf4j.Slf4j;
import org.jboss.bacon.experimental.impl.config.BuildConfigGeneratorConfig;
import org.jboss.bacon.experimental.impl.dependencies.DependencyResult;
import org.jboss.bacon.experimental.impl.dependencies.Project;
import org.jboss.bacon.experimental.impl.projectfinder.FoundProject;
import org.jboss.bacon.experimental.impl.projectfinder.FoundProjects;
import org.jboss.da.model.rest.GAV;
import org.jboss.pnc.api.enums.BuildType;
import org.jboss.pnc.bacon.pig.impl.config.BuildConfig;
import org.jboss.pnc.bacon.pig.impl.mapping.BuildConfigMapping;
import org.jboss.pnc.dto.BuildConfiguration;
import org.jboss.pnc.dto.BuildConfigurationRef;
import org.jboss.pnc.dto.BuildConfigurationRevision;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.StringJoiner;
import java.util.stream.Collectors;

@Slf4j
public class BuildConfigGenerator {
    private BuildConfigGeneratorConfig config;

    public BuildConfigGenerator(BuildConfigGeneratorConfig buildConfigGeneratorConfig) {
        this.config = buildConfigGeneratorConfig;
    }

    public List<BuildConfig> generateConfigs(DependencyResult dependencies, FoundProjects foundProjects) {
        Map<Project, BuildConfig> buildConfigMap = new HashMap<>();
        for (Project project : dependencies.getTopLevelProjects()) {
            generateConfig(buildConfigMap, project, foundProjects);
        }
        return new ArrayList<>(buildConfigMap.values());
    }

    private BuildConfig generateConfig(
            Map<Project, BuildConfig> buildConfigMap,
            Project project,
            FoundProjects foundProjects) {
        if (buildConfigMap.containsKey(project)) {
            return buildConfigMap.get(project);
        }
        BuildConfig buildConfig = processProject(project, getFoundForProject(project, foundProjects));
        buildConfigMap.put(project, buildConfig);
        for (Project dep : project.getDependencies()) {
            BuildConfig depBuildConfig = generateConfig(buildConfigMap, dep, foundProjects);
            String name = depBuildConfig.getName();
            buildConfig.getDependencies().add(name);
        }
        return buildConfig;
    }

    public BuildConfig processProject(Project project, FoundProject found) {
        String name = generateBCName(project);
        // Strategy
        if (found.isExactMatch()) {
            BuildConfig buildConfig = copyExisting(found.getBuildConfig(), found.getBuildConfigRevision(), name);
            return updateExactMatch(buildConfig, project);
        } else if (found.isFound()) {
            BuildConfig buildConfig = copyExisting(found.getBuildConfig(), found.getBuildConfigRevision(), name);
            return updateSimilar(buildConfig, project);
        } else {
            return generateNewBuildConfig(project, name);
        }
    }

    private BuildConfig generateNewBuildConfig(Project project, String name) {
        GAV gav = getFirstGAV(project);
        BuildConfig buildConfig = new BuildConfig();
        buildConfig.setBuildType(BuildType.MVN.name());
        buildConfig.setBuildScript(generateBuildScript());
        buildConfig.setEnvironmentName(config.getDefaultValues().getEnvironmentName());
        buildConfig.setName(name);
        buildConfig.setProject(gav.getGroupId() + "-" + gav.getArtifactId());
        buildConfig.setDescription("Autobuild genearted config for " + gav);
        String scmUrl = processScmUrl(project.getSourceCodeURL());
        if (scmUrl == null) {
            setPlaceholderSCM(name, buildConfig);
        } else {
            buildConfig.setScmUrl(scmUrl);
            buildConfig.setScmRevision(project.getSourceCodeRevision());
        }
        return buildConfig;
    }

    private String updateBuildScript(String script) {
        String commentAutogenerated = "# This build configuration was modified by Autobuild";
        String failCommand = "false";
        StringJoiner sj = new StringJoiner("\n");
        sj.add(commentAutogenerated);
        sj.add(script);
        if (config.isFailGeneratedBuildScript()) {
            sj.add(failCommand);
        }
        return sj.toString();
    }

    private String generateBuildScript() {
        String commentAutogenerated = "# This script was autogenerated";
        String defaultBuildCommand = config.getDefaultValues().getBuildScript();
        String failCommand = "false";
        StringJoiner sj = new StringJoiner("\n");
        sj.add(commentAutogenerated);
        sj.add(defaultBuildCommand);
        if (config.isFailGeneratedBuildScript()) {
            sj.add(failCommand);
        }
        return sj.toString();
    }

    private BuildConfig updateExactMatch(BuildConfig buildConfig, Project project) {
        boolean updated = updateAlignParams(buildConfig, true);
        if (updated) {
            buildConfig.setBuildScript(updateBuildScript(buildConfig.getBuildScript()));
        }
        return buildConfig;
    }

    private BuildConfig updateSimilar(BuildConfig buildConfig, Project project) {
        updateAlignParams(buildConfig, false);
        buildConfig.setScmRevision(project.getSourceCodeRevision());
        buildConfig.setBuildScript(updateBuildScript(buildConfig.getBuildScript()));
        return buildConfig;
    }

    private boolean updateAlignParams(BuildConfig buildConfig, boolean keepVersionOverride) {
        Set<String> originalAlignParams = buildConfig.getAlignmentParameters();
        Set<String> alignmentParameters = originalAlignParams.stream()
                .map(p -> BuildConfigGenerator.removeOverride(p, keepVersionOverride))
                .filter(p -> !p.isBlank())
                .collect(Collectors.toSet());
        if (alignmentParameters.equals(originalAlignParams)) {
            return false;
        } else {
            buildConfig.setAlignmentParameters(alignmentParameters);
            return true;
        }
    }

    static String removeOverride(String parameter, boolean keepVersionOverride) {
        return Arrays.stream(parameter.split(" "))
                .filter(s -> !s.contains("dependencyOverride"))
                .filter(s -> keepVersionOverride || !s.contains("versionOverride"))
                .collect(Collectors.joining(" "));
        // TODO: what about `manipulation.disable=true`,
    }

    private String generateBCName(Project project) {
        GAV gav = getFirstGAV(project);
        return gav.getGroupId() + "-" + gav.getArtifactId() + "-" + gav.getVersion() + "-AUTOBUILD";
    }

    private static GAV getFirstGAV(Project project) {
        GAV gav = project.getGavs()
                .stream()
                .sorted(Comparator.comparing(GAV::getGroupId).thenComparing(GAV::getArtifactId))
                .findFirst()
                .get();
        return gav;
    }

    /**
     * Returns true if the project has a "well-defined" build config already. Well-defined Build Config is such that
     * either: - Has no dependencies and no dependencies need to be setup - Has all dependencies that need to be setup
     * already setup AND all the dependencies are well-defined Build Configs.
     */
    private boolean isWellDefined(Project project, FoundProject found) {
        if (!found.isFound()) {
            return false; // Build config doesn't exist, so it's not defined at all
        }
        Map<String, BuildConfigurationRef> dependencies = found.getBuildConfig().getDependencies();
        if (project.getDependencies().isEmpty()) {
            return dependencies.isEmpty();
        } else {
            Set<String> dependencyIds = new HashSet<>();
            for (Project dep : project.getDependencies()) {
                FoundProject depFound = getFoundForProject(dep, null /* TODO */);
                if (!depFound.isFound()) {
                    return false;
                }
                String dependencyId = depFound.getBuildConfig().getId();
                if (!dependencies.containsKey(dependencyId)) {
                    return false;
                }
                dependencyIds.add(dependencyId);
                if (!isWellDefined(dep, depFound)) {
                    return false;
                }
            }
            return dependencyIds.size() == dependencies.size();
        }
    }

    private FoundProject getFoundForProject(Project project, FoundProjects founds) {
        return founds.getFoundProjects().stream().filter(fp -> fp.getGavs().equals(project.getGavs())).findAny().get();
    }

    public BuildConfig copyExisting(BuildConfiguration bc, BuildConfigurationRevision bcr, String name) {
        BuildConfigMapping.GeneratorOptions opts = BuildConfigMapping.GeneratorOptions.builder()
                .nameOverride(Optional.of(name))
                .useEnvironmentName(true)
                .build();
        BuildConfig buildConfig = BuildConfigMapping.toBuildConfig(bc, bcr, opts);
        String scmUrl = processScmUrl(buildConfig.getScmUrl());
        if (scmUrl == null) {
            setPlaceholderSCM(name, buildConfig);
        } else {
            buildConfig.setScmUrl(scmUrl);
        }
        buildConfig.getDependencies().clear();
        return buildConfig;
    }

    private void setPlaceholderSCM(String name, BuildConfig buildConfig) {
        log.warn("Using placeholder SCM url for Build Config {}", name);
        buildConfig.setScmUrl(config.getDefaultValues().getScmUrl());
        buildConfig.setScmRevision(config.getDefaultValues().getScmRevision());
    }

    private String processScmUrl(String originalUrl) {
        if (originalUrl == null) {
            return null;
        }
        for (String key : config.getScmReplaceWithPlaceholder()) {
            if (originalUrl.contains(key)) {
                return null;
            }
        }
        String updatedUrl = originalUrl;
        for (Map.Entry<String, String> e : config.getScmPattern().entrySet()) {
            updatedUrl = updatedUrl.replace(e.getKey(), e.getValue());
        }
        for (Map.Entry<String, String> e : config.getScmMapping().entrySet()) {
            String key = e.getKey();
            if (updatedUrl.contains(key)) {
                updatedUrl = e.getValue();
                break;
            }
            if (key.endsWith(".git") && updatedUrl.endsWith(key.substring(0, key.length() - 4))) {
                updatedUrl = e.getValue();
                break;
            }
        }
        if (!originalUrl.equals(updatedUrl)) {
            log.debug("Updated SCM URL from {} to {}", originalUrl, updatedUrl);
        }
        return updatedUrl;
    }

}
