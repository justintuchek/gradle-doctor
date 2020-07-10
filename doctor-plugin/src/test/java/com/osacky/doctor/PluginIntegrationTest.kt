package com.osacky.doctor

import com.google.common.truth.Truth.assertThat
import com.osacky.doctor.internal.androidHome
import org.gradle.testkit.runner.GradleRunner
import org.junit.Assume
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.File

@RunWith(Parameterized::class)
class PluginIntegrationTest constructor(private val version: String) {
    val agpVersion = "3.6.3"
    @get:Rule val testProjectRoot = TemporaryFolder()

    companion object {
        @JvmStatic
        @Parameterized.Parameters(name = "{0}")
        fun getParams(): List<String> {
            // Keep 5.0 as minimum unsupported version and 5.1 as minimum supported version.
            // Keep this list to 5 as testing against too many versions causes OOMs.
            return listOf("5.0", "5.1", "5.6.4", "6.0.1", "6.5.1")
        }
    }

    @Test
    fun testSupportedVersion() {
        assumeSupportedVersion()
        writeBuildGradle(
            """
                    |plugins {
                    |  id "com.osacky.doctor"
                    |}
                    |doctor {
                    |  disallowMultipleDaemons = false
                    |  ensureJavaHomeMatches = false
                    |}
                """.trimMargin("|")
        )

        val result = createRunner().build()

        assertThat(result.output).contains("BUILD SUCCESSFUL")
    }

    @Test
    fun testFailOnOlderVersion() {
        assumeUnsupportedVersion()
        writeBuildGradle(
            """
                    |plugins {
                    |  id "com.osacky.doctor"
                    |}
                    |doctor {
                    |  disallowMultipleDaemons = false
                    |  ensureJavaHomeMatches = !System.getenv().containsKey("CI")
                    |}
                """.trimMargin("|")
        )

        val result = createRunner().buildAndFail()
        assertThat(result.output).contains("Must be using Gradle Version 5.1 in order to use DoctorPlugin. Current Gradle Version is Gradle $version")
    }

    @Test
    fun testFailWithMultipleDaemons() {
        assumeSupportedVersion()
        writeBuildGradle(
            """
                    |plugins {
                    |  id "com.osacky.doctor"
                    |}
                    |doctor {
                    |  disallowMultipleDaemons = true
                    |  ensureJavaHomeMatches = !System.getenv().containsKey("CI")
                    |}
                """.trimMargin("|")
        )
        val result = createRunner().buildAndFail()
        assertThat(result.output)
            .contains(
                """
                    |  | This may indicate a settings mismatch between the IDE and the terminal.                              |
                    |  | There might also be a bug causing extra Daemons to spawn.                                            |
                    |  | You can check active Daemons with `jps`.                                                             |
                    |  | To kill all active Daemons use:                                                                      |
                    |  | pkill -f '.*GradleDaemon.*'                                                                          |
                    |  |                                                                                                      |
                    |  | This might be expected if you are working on multiple Gradle projects or if you are using build.grad |
                    |  | le.kts.                                                                                              |
                    |  | To disable this message add this to your root build.gradle file:                                     |
                    |  | doctor {                                                                                             |
                    |  |   disallowMultipleDaemons = false                                                                    |
                    |  | }                                                                                                    |
                    |  ========================================================================================================
                    """.trimMargin()
            )
    }

    // This is failing, perhaps because it is actually trying to use "foo" as JAVA_HOME.
    @Test @Ignore
    fun testJavaHomeNotSet() {
        assumeSupportedVersion()

        writeBuildGradle(
            """
                    |plugins {
                    |  id "com.osacky.doctor"
                    |}
                    |doctor {
                    |  disallowMultipleDaemons = false
                    |  ensureJavaHomeMatches = true
                    |}
                """.trimMargin("|")
        )
        testProjectRoot.newFile("settings.gradle")

        val result = createRunner()
            .withEnvironment(mapOf("JAVA_HOME" to "foo"))
            .withArguments("tasks")
            .buildAndFail()
        assertThat(result.output).contains(
            """
                |> =============================== Gradle Doctor Prescriptions ============================================
                |  | Gradle is not using JAVA_HOME.                                                                       |
                |  | JAVA_HOME is foo                                                                                     |
                |  """
                .trimMargin("|")
        )
    }

    @Test
    fun testFailAssembleMultipleProjects() {
        assumeSupportedVersion()
        Assume.assumeFalse("5.1" == version)
        testProjectRoot.newFile("local.properties").writeText("sdk.dir=${androidHome()}\n")
        writeBuildGradle(
            """
            buildscript {
              repositories {
                google()
              }
              dependencies {
                classpath("com.android.tools.build:gradle:$agpVersion")
              }
            }

            plugins {
              id "com.osacky.doctor"
            }
            doctor {
              disallowMultipleDaemons = false
              ensureJavaHomeMatches = false
            }
            """.trimIndent()
        )

        writeFileToName(
            "settings.gradle",
            """
            include 'app-one'
            include 'app-two'
        """.trimMargin()
        )

        val srcFolder = testProjectRoot.newFolder("app-one", "src", "main")
        val folder = File(testProjectRoot.root, "app-one")
        createFileInFolder(srcFolder, "AndroidManifest.xml", "<manifest package=\"com.foo.bar.one\"/>")
        createFileInFolder(
            folder, "build.gradle",
            """
            apply plugin: 'com.android.application'

            android {
              compileSdkVersion 28
            }
            """.trimIndent()
        )
        val srcFolder2 = testProjectRoot.newFolder("app-two", "src", "main")
        val folder2 = File(testProjectRoot.root, "app-two")
        createFileInFolder(srcFolder2, "AndroidManifest.xml", "<manifest package=\"com.foo.bar.two\"/>")
        createFileInFolder(
            folder2, "build.gradle",
            """
            apply plugin: 'com.android.application'

            android {
              compileSdkVersion 28
            }
            """.trimIndent()
        )
        val result = createRunner()
            .withArguments("assembleDebug")
            .buildAndFail()
        assertThat(result.output).contains(
            """
               |=============================== Gradle Doctor Prescriptions ============================================
               || Did you really mean to run all these? [task ':app-one:assembleDebug', task ':app-two:assembleDebug'] |
               || Maybe you just meant to assemble/install one of them? In that case, you can try                      |
               ||   ./gradlew app-one:assembleDebug                                                                    |
               || Or did you hit "build" in the IDE (Green Hammer)? Did you know that assembles all the code in the en |
               || tire project?                                                                                        |
               || Next time try "Sync Project with Gradle Files" (Gradle Elephant with Arrow).                         |
               |========================================================================================================
               """.trimMargin()
        )
    }

    @Test
    fun testFailInstallMultipleProjects() {
        assumeSupportedVersion()
        Assume.assumeFalse("5.1" == version)
        testProjectRoot.newFile("local.properties").writeText("sdk.dir=${androidHome()}\n")
        writeBuildGradle(
            """
            buildscript {
              repositories {
                google()
              }
              dependencies {
                classpath("com.android.tools.build:gradle:$agpVersion")
              }
            }

            plugins {
              id "com.osacky.doctor"
            }
            doctor {
              disallowMultipleDaemons = false
              ensureJavaHomeMatches = false
            }
            """.trimIndent()
        )

        writeFileToName(
            "settings.gradle",
            """
            include 'app-one'
            include 'app-two'
        """.trimMargin()
        )

        val srcFolder = testProjectRoot.newFolder("app-one", "src", "main")
        val folder = File(testProjectRoot.root, "app-one")
        createFileInFolder(srcFolder, "AndroidManifest.xml", "<manifest package=\"com.foo.bar.one\"/>")
        createFileInFolder(
            folder, "build.gradle",
            """
            apply plugin: 'com.android.application'

            android {
              compileSdkVersion 28
            }
            """.trimIndent()
        )
        val srcFolder2 = testProjectRoot.newFolder("app-two", "src", "main")
        val folder2 = File(testProjectRoot.root, "app-two")
        createFileInFolder(srcFolder2, "AndroidManifest.xml", "<manifest package=\"com.foo.bar.two\"/>")
        createFileInFolder(
            folder2, "build.gradle",
            """
            apply plugin: 'com.android.application'

            android {
              compileSdkVersion 28
            }
            """.trimIndent()
        )
        val result = createRunner()
            .withArguments("installDebug")
            .buildAndFail()
        assertThat(result.output).contains(
            """
               |=============================== Gradle Doctor Prescriptions ============================================
               || Did you really mean to run all these? [task ':app-one:installDebug', task ':app-two:installDebug']   |
               || Maybe you just meant to assemble/install one of them? In that case, you can try                      |
               ||   ./gradlew app-one:installDebug                                                                     |
               || Or did you hit "build" in the IDE (Green Hammer)? Did you know that assembles all the code in the en |
               || tire project?                                                                                        |
               || Next time try "Sync Project with Gradle Files" (Gradle Elephant with Arrow).                         |
               |========================================================================================================
               """.trimMargin()
        )
    }

    @Test
    fun testFailOnEmptyDirectories() {
        assumeSupportedVersion()
        writeBuildGradle(
            """
                    |plugins {
                    |  id "com.osacky.doctor"
                    |}
                    |doctor {
                    |  disallowMultipleDaemons = false
                    |  ensureJavaHomeMatches = false
                    |  failOnEmptyDirectories = true
                    |}
                """.trimMargin("|")
        )
        val fixtureName = "java-fixture"
        testProjectRoot.newFile("settings.gradle").writeText("include '$fixtureName'")
        testProjectRoot.setupFixture(fixtureName)
        testProjectRoot.newFolder("java-fixture", "src", "main", "java", "com", "foo")

        val result = createRunner()
            .withArguments("assemble")
            .buildAndFail()

        assertThat(result.output).contains("Empty src dir found. This causes build cache misses. Run the following command to fix it.")
    }

    private fun createRunner(): GradleRunner {
        return GradleRunner.create()
            .withProjectDir(testProjectRoot.root)
            .withPluginClasspath()
            .withGradleVersion(version)
    }

    private fun assumeSupportedVersion() {
        Assume.assumeFalse("5.0" == version)
    }

    private fun assumeUnsupportedVersion() {
        Assume.assumeTrue(version == "5.0")
    }

    private fun writeBuildGradle(build: String) {
        writeFileToName("build.gradle", build)
    }

    private fun writeFileToName(fileName: String, contents: String) {
        testProjectRoot.newFile(fileName).writeText(contents)
    }

    private fun createFileInFolder(folder: File, fileName: String, contents: String) {
        File(folder, fileName).writeText(contents)
    }
}
