/*
 * Copyright 2019 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package common

import configurations.branchesFilterExcluding
import configurations.buildScanCustomValue
import configurations.buildScanTag
import configurations.checkCleanDirUnixLike
import configurations.checkCleanDirWindows
import configurations.enablePullRequestFeature
import jetbrains.buildServer.configs.kotlin.v2019_2.AbsoluteId
import jetbrains.buildServer.configs.kotlin.v2019_2.BuildStep
import jetbrains.buildServer.configs.kotlin.v2019_2.BuildSteps
import jetbrains.buildServer.configs.kotlin.v2019_2.BuildType
import jetbrains.buildServer.configs.kotlin.v2019_2.BuildTypeSettings
import jetbrains.buildServer.configs.kotlin.v2019_2.CheckoutMode
import jetbrains.buildServer.configs.kotlin.v2019_2.Dependencies
import jetbrains.buildServer.configs.kotlin.v2019_2.FailureAction
import jetbrains.buildServer.configs.kotlin.v2019_2.RelativeId
import jetbrains.buildServer.configs.kotlin.v2019_2.Requirements
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.GradleBuildStep
import jetbrains.buildServer.configs.kotlin.v2019_2.buildSteps.script
import jetbrains.buildServer.configs.kotlin.v2019_2.failureConditions.BuildFailureOnText
import jetbrains.buildServer.configs.kotlin.v2019_2.failureConditions.failOnText
import jetbrains.buildServer.configs.kotlin.v2019_2.ui.add
import java.util.Locale

fun BuildSteps.customGradle(init: GradleBuildStep.() -> Unit, custom: GradleBuildStep.() -> Unit): GradleBuildStep =
    GradleBuildStep(init)
        .apply(custom)
        .also { step(it) }

/**
 * Adds a [Gradle build step](https://confluence.jetbrains.com/display/TCDL/Gradle)
 * that runs with the Gradle wrapper.
 *
 * @see GradleBuildStep
 */
fun BuildSteps.gradleWrapper(init: GradleBuildStep.() -> Unit): GradleBuildStep =
    customGradle(init) {
        useGradleWrapper = true
        if (buildFile == null) {
            buildFile = "" // Let Gradle detect the build script
        }
    }

fun Requirements.requiresOs(os: Os) {
    contains("teamcity.agent.jvm.os.name", os.agentRequirement)
}

fun Requirements.requiresArch(os: Os, arch: Arch) {
    if (os == Os.MACOS) {
        contains("teamcity.agent.jvm.os.arch", arch.nameOnMac)
    } else {
        contains("teamcity.agent.jvm.os.arch", arch.nameOnLinuxWindows)
    }
}

fun Requirements.requiresNoEc2Agent() {
    doesNotContain("teamcity.agent.name", "ec2")
    // US region agents have name "EC2-XXX"
    doesNotContain("teamcity.agent.name", "EC2")
}

/**
 * This is an undocumented location that forbids anonymous access.
 * We put artifacts here to avoid accidentally exposing sensitive information publicly.
 */
const val hiddenArtifactDestination = ".teamcity/gradle-logs"

fun BuildType.applyDefaultSettings(os: Os = Os.LINUX, arch: Arch = Arch.AMD64, buildJvm: Jvm = BuildToolBuildJvm, timeout: Int = 30) {
    artifactRules = """
        build/report-* => $hiddenArtifactDestination
        build/tmp/test files/** => $hiddenArtifactDestination/test-files
        build/errorLogs/** => $hiddenArtifactDestination/errorLogs
        subprojects/internal-build-reports/build/reports/incubation/all-incubating.html => incubation-reports
        build/reports/dependency-verification/** => dependency-verification-reports
    """.trimIndent()

    paramsForBuildToolBuild(buildJvm, os, arch)
    params {
        // The promotion job doesn't have a branch, so %teamcity.build.branch% doesn't work.
        param("env.BUILD_BRANCH", "%teamcity.build.branch%")
    }

    vcs {
        root(AbsoluteId(VersionedSettingsBranch.fromDslContext().vcsRootId()))
        checkoutMode = CheckoutMode.ON_AGENT
        branchFilter = branchesFilterExcluding()
    }

    features {
        enablePullRequestFeature()
    }

    requirements {
        requiresOs(os)
        requiresArch(os, arch)
    }

    failureConditions {
        if (this@applyDefaultSettings.type != BuildTypeSettings.Type.COMPOSITE) {
            executionTimeoutMin = timeout
        }
        testFailure = false
        add {
            failOnText {
                conditionType = BuildFailureOnText.ConditionType.CONTAINS
                pattern = "%unmaskedFakeCredentials%"
                failureMessage = "This build might be leaking credentials"
                reverse = false
                stopBuildOnFailure = true
            }
        }
    }
}

fun javaHome(jvm: Jvm, os: Os, arch: Arch = Arch.AMD64) = "%${os.name.lowercase()}.${jvm.version}.${jvm.vendor}.${arch.suffix}%"

fun BuildType.paramsForBuildToolBuild(buildJvm: Jvm = BuildToolBuildJvm, os: Os, arch: Arch = Arch.AMD64) {
    params {
        param("env.BOT_TEAMCITY_GITHUB_TOKEN", "%github.bot-teamcity.token%")
        param("env.GRADLE_CACHE_REMOTE_PASSWORD", "%gradle.cache.remote.password%")
        param("env.GRADLE_CACHE_REMOTE_URL", "%gradle.cache.remote.url%")
        param("env.GRADLE_CACHE_REMOTE_USERNAME", "%gradle.cache.remote.username%")

        param("env.JAVA_HOME", javaHome(buildJvm, os, arch))
        param("env.GRADLE_OPTS", "-Xmx1536m -XX:MaxPermSize=384m")
        param("env.ANDROID_HOME", os.androidHome)
        param("env.ANDROID_SDK_ROOT", os.androidHome)
        param("env.GRADLE_INTERNAL_REPO_URL", "%gradle.internal.repository.url%")
        if (os == Os.MACOS) {
            // Use fewer parallel forks on macOs, since the agents are not very powerful.
            param("maxParallelForks", "2")
        }
        if (os == Os.LINUX || os == Os.MACOS) {
            param("env.LC_ALL", "en_US.UTF-8")
        }
    }
}

fun BuildSteps.checkCleanM2AndAndroidUserHome(os: Os = Os.LINUX) {
    script {
        name = "CHECK_CLEAN_M2_ANDROID_USER_HOME"
        executionMode = BuildStep.ExecutionMode.ALWAYS
        scriptContent = if (os == Os.WINDOWS) {
            checkCleanDirWindows("%teamcity.agent.jvm.user.home%\\.m2\\repository") + checkCleanDirWindows("%teamcity.agent.jvm.user.home%\\.m2\\.gradle-enterprise") + checkCleanDirWindows("%teamcity.agent.jvm.user.home%\\.android", false)
        } else {
            checkCleanDirUnixLike("%teamcity.agent.jvm.user.home%/.m2/repository") + checkCleanDirUnixLike("%teamcity.agent.jvm.user.home%/.m2/.gradle-enterprise") + checkCleanDirUnixLike("%teamcity.agent.jvm.user.home%/.android", false)
        }
    }
}

fun buildToolGradleParameters(daemon: Boolean = true, isContinue: Boolean = true): List<String> =
    listOf(
        // We pass the 'maxParallelForks' setting as 'workers.max' to limit the maximum number of executers even
        // if multiple test tasks run in parallel. We also pass it to the Gradle build as a maximum (maxParallelForks)
        // for each test task, such that we are independent of whatever default value is defined in the build itself.
        "-Dorg.gradle.workers.max=%maxParallelForks%",
        "-PmaxParallelForks=%maxParallelForks%",
        "-Dorg.gradle.internal.plugins.portal.url.override=%gradle.plugins.portal.url%",
        "-s",
        "--no-configuration-cache",
        "%additional.gradle.parameters%",
        if (daemon) "--daemon" else "--no-daemon",
        if (isContinue) "--continue" else ""
    )

fun Dependencies.dependsOn(buildTypeId: RelativeId) {
    dependency(buildTypeId) {
        snapshot {
            onDependencyFailure = FailureAction.FAIL_TO_START
            onDependencyCancel = FailureAction.FAIL_TO_START
        }
    }
}

fun Dependencies.compileAllDependency(compileAllId: String) {
    // Compile All has to succeed before anything else is started
    dependsOn(RelativeId(compileAllId))
    // Get the build receipt from sanity check to reuse the timestamp
    artifacts(RelativeId(compileAllId)) {
        id = "ARTIFACT_DEPENDENCY_$compileAllId"
        cleanDestination = true
        artifactRules = "build-receipt.properties => incoming-distributions"
    }
}

fun functionalTestExtraParameters(buildScanTag: String, os: Os, arch: Arch, testJvmVersion: String, testJvmVendor: String): String {
    val buildScanValues = mapOf(
        "coverageOs" to os.name.lowercase(),
        "coverageArch" to arch.name.lowercase(),
        "coverageJvmVendor" to testJvmVendor,
        "coverageJvmVersion" to "java$testJvmVersion"
    )
    return (
        listOf(
            "-PtestJavaVersion=$testJvmVersion",
            "-PtestJavaVendor=$testJvmVendor"
        ) +
            listOf(buildScanTag(buildScanTag)) +
            buildScanValues.map { buildScanCustomValue(it.key, it.value) }
        ).filter { it.isNotBlank() }.joinToString(separator = " ")
}

fun functionalTestParameters(os: Os): List<String> {
    return listOf(
        "-PteamCityBuildId=%teamcity.build.id%",
        os.javaInstallationLocations(),
        "-Porg.gradle.java.installations.auto-download=false"
    )
}

fun BuildType.killProcessStep(stepName: String, daemon: Boolean) {
    steps {
        gradleWrapper {
            name = stepName
            executionMode = BuildStep.ExecutionMode.ALWAYS
            tasks = "killExistingProcessesStartedByGradle"
            gradleParams =
                (buildToolGradleParameters(daemon) + buildScanTag("CleanUpBuild")).joinToString(separator = " ")
        }
    }
}

fun String.toCapitalized() = this.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }
