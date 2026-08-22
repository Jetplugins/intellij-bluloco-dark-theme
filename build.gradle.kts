import org.jetbrains.changelog.Changelog
import org.jetbrains.changelog.markdownToHTML
import groovy.json.JsonSlurper
import javax.xml.parsers.DocumentBuilderFactory
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Locale

abstract class StripProductDescriptorTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sandboxPluginLibDirectory: DirectoryProperty

    @TaskAction
    fun stripProductDescriptor() {
        val libraryDirectory = sandboxPluginLibDirectory.get().asFile.toPath()
        val pluginJar = Files.list(libraryDirectory).use { files ->
            files.filter { it.fileName.toString().endsWith(".jar") }
                .findFirst()
                .orElseThrow { GradleException("No plugin JAR found in $libraryDirectory") }
        }

        val replacementJar = Files.createTempFile(libraryDirectory, "ui-test-plugin-", ".jar")
        try {
            Files.copy(pluginJar, replacementJar, StandardCopyOption.REPLACE_EXISTING)
            FileSystems.newFileSystem(replacementJar, emptyMap<String, Any>()).use { zip ->
                val descriptor = zip.getPath("/META-INF/plugin.xml")
                val original = Files.readString(descriptor)
                val withoutLicense = original.replace(
                    Regex("""\s*<product-descriptor\b[^>]*/>"""),
                    "\n    <!-- product-descriptor intentionally omitted from the UI-test sandbox -->",
                )
                if (withoutLicense == original) {
                    throw GradleException("UI-test plugin.xml has no product-descriptor to omit")
                }
                Files.writeString(descriptor, withoutLicense)
            }
            Files.move(replacementJar, pluginJar, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            Files.deleteIfExists(replacementJar)
        }
    }
}

plugins {
    id("java")
    alias(libs.plugins.intelliJPlatform)
    alias(libs.plugins.changelog)
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val generatedThemeResources = layout.buildDirectory.dir("generated/theme-resources")
val editorSchemeTemplate = layout.projectDirectory.file("src/main/theme/BlulocoScheme.xml.template")
val editorSchemeTokens = layout.projectDirectory.file("src/main/theme/editor-schemes.json")
val demoProjectDirectory = layout.projectDirectory.dir("src/uiTest/resources/demo-project")
val screenshotThemeManifest = layout.buildDirectory.file("generated/ui-test/screenshot-themes.tsv")

sourceSets {
    main {
        resources.srcDir(generatedThemeResources)
    }
}

val uiTestSourceSet = sourceSets.create("uiTest")
uiTestSourceSet.compileClasspath += sourceSets.main.get().output
uiTestSourceSet.runtimeClasspath += sourceSets.main.get().output

configurations[uiTestSourceSet.implementationConfigurationName]
    .extendsFrom(configurations.testImplementation.get())
configurations[uiTestSourceSet.runtimeOnlyConfigurationName]
    .extendsFrom(configurations.testRuntimeOnly.get())

// Configure project's dependencies
repositories {
    mavenCentral()
    maven("https://packages.jetbrains.team/maven/p/ij/intellij-dependencies")

    // IntelliJ Platform Gradle Plugin Repositories Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-repositories-extension.html
    intellijPlatform {
        defaultRepositories()
    }
}

// Dependencies are managed with Gradle version catalog - read more: https://docs.gradle.org/current/userguide/version_catalogs.html
dependencies {
    add(uiTestSourceSet.implementationConfigurationName, platform(libs.junitBom))
    add(uiTestSourceSet.implementationConfigurationName, libs.junitJupiter)
    add(uiTestSourceSet.implementationConfigurationName, libs.remoteRobot)
    add(uiTestSourceSet.implementationConfigurationName, libs.remoteFixtures)
    add(uiTestSourceSet.implementationConfigurationName, libs.kotlinStdlib)
    add(uiTestSourceSet.runtimeOnlyConfigurationName, libs.junitPlatformLauncher)

    // IntelliJ Platform Gradle Plugin Dependencies Extension - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-dependencies-extension.html
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion"))

        // Plugin Dependencies. Uses `platformBundledPlugins` property from the gradle.properties file for bundled IntelliJ Platform plugins.
        bundledPlugins(providers.gradleProperty("platformBundledPlugins").map { it.split(',') })

        // Plugin Dependencies. Uses `platformPlugins` property from the gradle.properties file for plugin from JetBrains Marketplace.
        plugins(providers.gradleProperty("platformPlugins").map { it.split(',') })

        // Module Dependencies. Uses `platformBundledModules` property from the gradle.properties file for bundled IntelliJ Platform modules.
        bundledModules(providers.gradleProperty("platformBundledModules").map { it.split(',') })
    }
}

// Configure IntelliJ Platform Gradle Plugin - read more: https://plugins.jetbrains.com/docs/intellij/tools-intellij-platform-gradle-plugin-extension.html
intellijPlatform {
    buildSearchableOptions = false
    instrumentCode = false

    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")

        // Extract the <!-- Plugin description --> section from README.md and provide for the plugin's manifest
        description = providers.fileContents(layout.projectDirectory.file("README.md")).asText.map {
            val start = "<!-- Plugin description -->"
            val end = "<!-- Plugin description end -->"

            with(it.lines()) {
                if (!containsAll(listOf(start, end))) {
                    throw GradleException("Plugin description section not found in README.md:\n$start ... $end")
                }
                subList(indexOf(start) + 1, indexOf(end)).joinToString("\n").let(::markdownToHTML)
            }
        }

        val changelog = project.changelog // local variable for configuration cache compatibility
        // Get the latest available change notes from the changelog file
        changeNotes = providers.gradleProperty("pluginVersion").map { pluginVersion ->
            with(changelog) {
                renderItem(
                    (getOrNull(pluginVersion) ?: getUnreleased())
                        .withHeader(false)
                        .withEmptySections(false),
                    Changelog.OutputType.HTML,
                )
            }
        }

        ideaVersion {
            sinceBuild = providers.gradleProperty("pluginSinceBuild")
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
        // The pluginVersion is based on the SemVer (https://semver.org) and supports pre-release labels, like 2.1.7-alpha.3
        // Specify pre-release label to publish the plugin in a custom Release Channel automatically. Read more:
        // https://plugins.jetbrains.com/docs/intellij/publishing-plugin.html#specifying-a-release-channel
        channels = providers.gradleProperty("pluginVersion").map { listOf(it.substringAfter('-', "").substringBefore('.').ifEmpty { "default" }) }
    }

    pluginVerification {
        ides {
            recommended()
        }
    }
}

// Configure Gradle Changelog Plugin - read more: https://github.com/JetBrains/gradle-changelog-plugin
changelog {
    groups.empty()
    repositoryUrl = providers.gradleProperty("pluginRepositoryUrl")
    versionPrefix = ""
}

tasks {
    val generateEditorSchemes by registering {
        group = "theme"
        description = "Generates all editor schemes from one canonical XML file and shared color tokens."

        val templateFile = editorSchemeTemplate.asFile
        val tokensFile = editorSchemeTokens.asFile
        val outputRoot = generatedThemeResources.get().asFile
        inputs.file(templateFile)
        inputs.file(tokensFile)
        outputs.dir(outputRoot)

        doLast {
            val definition = JsonSlurper().parse(tokensFile) as Map<*, *>
            val generatedAt = definition["generatedAt"] as? String
                ?: error("editor-schemes.json is missing generatedAt")
            val schemes = definition["schemes"] as? List<*>
                ?: error("editor-schemes.json is missing schemes")
            val tokens = definition["tokens"] as? Map<*, *>
                ?: error("editor-schemes.json is missing tokens")
            val template = templateFile.readText()
            val outputDirectory = outputRoot.resolve("themes").apply { mkdirs() }

            check(template.contains("@SCHEME_NAME@") && template.contains("@GENERATED_AT@")) {
                "Editor scheme template is missing required placeholders"
            }

            schemes.forEach { rawScheme ->
                val scheme = rawScheme as? Map<*, *> ?: error("Invalid editor scheme definition")
                val id = scheme["id"] as? String ?: error("Editor scheme is missing id")
                val name = scheme["name"] as? String ?: error("Editor scheme '$id' is missing name")
                val fileName = scheme["file"] as? String ?: error("Editor scheme '$id' is missing file")

                var rendered = template
                    .replace("@SCHEME_NAME@", name)
                    .replace("@GENERATED_AT@", generatedAt)

                tokens.forEach { (rawTokenName, rawValues) ->
                    val tokenName = rawTokenName as String
                    val values = rawValues as? Map<*, *> ?: error("Token '$tokenName' has invalid values")
                    val source = values["dark"] as? String ?: error("Token '$tokenName' has no dark value")
                    val replacement = values[id] as? String ?: error("Token '$tokenName' has no '$id' value")
                    val sourceAttribute = "value=\"$source\""

                    check(rendered.contains(sourceAttribute)) {
                        "Token '$tokenName' does not match the canonical editor scheme"
                    }
                    rendered = rendered.replace(sourceAttribute, "value=\"$replacement\"")
                }

                check(!rendered.contains("@SCHEME_")) { "Unresolved placeholder in $fileName" }
                outputDirectory.resolve(fileName).writeText(rendered)
            }
        }
    }

    val validateThemeResources by registering {
        group = "verification"
        description = "Validates theme descriptors, editor schemes, and plugin registrations."
        dependsOn(generateEditorSchemes)

        val resourcesRoot = layout.projectDirectory.dir("src/main/resources").asFile
        val themesRoot = resourcesRoot.resolve("themes")
        val generatedResourcesRoot = generatedThemeResources.get().asFile
        val pluginDescriptor = resourcesRoot.resolve("META-INF/plugin.xml")

        inputs.files(fileTree(themesRoot) {
            include("*.theme.json")
        })
        inputs.dir(generatedThemeResources)
        inputs.file(pluginDescriptor)

        doLast {
            val xmlFactory = DocumentBuilderFactory.newInstance().apply {
                setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
                isXIncludeAware = false
                isExpandEntityReferences = false
            }

            val descriptors = themesRoot.listFiles { file -> file.name.endsWith(".theme.json") }
                ?.sortedBy { it.name }
                .orEmpty()

            check(descriptors.isNotEmpty()) { "No theme descriptors found in $themesRoot" }

            descriptors.forEach { descriptor ->
                val theme = JsonSlurper().parse(descriptor) as Map<*, *>
                listOf("name", "dark", "author", "editorScheme", "ui").forEach { requiredKey ->
                    check(theme.containsKey(requiredKey)) {
                        "${descriptor.name} is missing required key '$requiredKey'"
                    }
                }

                val schemePath = theme["editorScheme"] as? String
                    ?: error("${descriptor.name} has an invalid editorScheme")
                val schemeFile = generatedResourcesRoot.resolve(schemePath.removePrefix("/"))
                check(schemeFile.isFile) {
                    "${descriptor.name} references missing editor scheme $schemePath"
                }
                xmlFactory.newDocumentBuilder().parse(schemeFile)

                val icons = theme["icons"] as? Map<*, *>
                    ?: error("${descriptor.name} has no icons section")
                val iconPalette = icons["ColorPalette"] as? Map<*, *>
                    ?: error("${descriptor.name} has no icons.ColorPalette")
                check(iconPalette.isNotEmpty()) { "${descriptor.name} has an empty icons.ColorPalette" }
            }

            val pluginDocument = xmlFactory.newDocumentBuilder().parse(pluginDescriptor)
            val providers = pluginDocument.getElementsByTagName("themeProvider")
            val providerIds = mutableSetOf<String>()

            for (index in 0 until providers.length) {
                val provider = providers.item(index).attributes
                val id = provider.getNamedItem("id")?.nodeValue
                    ?: error("themeProvider at index $index has no id")
                val path = provider.getNamedItem("path")?.nodeValue
                    ?: error("themeProvider '$id' has no path")

                check(providerIds.add(id)) { "Duplicate themeProvider id '$id'" }
                check(resourcesRoot.resolve(path.removePrefix("/")).isFile) {
                    "themeProvider '$id' references missing descriptor $path"
                }
            }

            check(providers.length == descriptors.size) {
                "Found ${descriptors.size} theme descriptors but ${providers.length} themeProvider registrations"
            }
        }
    }

    check {
        dependsOn(validateThemeResources)
    }

    processResources {
        dependsOn(generateEditorSchemes)
    }

    withType<JavaCompile>().configureEach {
        options.release = 21
    }

    val generateScreenshotThemeManifest by registering {
        group = "documentation"
        description = "Discovers every theme descriptor and prepares the Marketplace screenshot matrix."

        val themeDescriptors = fileTree(layout.projectDirectory.dir("src/main/resources/themes")) {
            include("*.theme.json")
        }
        val outputFile = screenshotThemeManifest.get().asFile
        inputs.files(themeDescriptors)
        outputs.file(outputFile)

        doLast {
            val entries = themeDescriptors.files.sortedBy { it.name }.map { descriptor ->
                val theme = JsonSlurper().parse(descriptor) as? Map<*, *>
                    ?: error("Invalid theme descriptor: ${descriptor.name}")
                val name = theme["name"] as? String
                    ?: error("${descriptor.name} is missing its theme name")
                val colors = theme["colors"] as? Map<*, *>
                    ?: error("${descriptor.name} is missing its colors section")
                val slug = name.removePrefix("Bluloco ")
                    .lowercase(Locale.ROOT)
                    .replace(Regex("[^a-z0-9]+"), "-")
                    .trim('-')

                fun requiredColor(key: String): String = colors[key] as? String
                    ?: error("${descriptor.name} is missing the '$key' screenshot color")

                check(slug.isNotEmpty()) { "${descriptor.name} produces an empty screenshot slug" }
                check('\t' !in name) { "${descriptor.name} contains a tab in its theme name" }

                listOf(
                    slug,
                    name,
                    requiredColor("bgEditor"),
                    requiredColor("bgMain"),
                    requiredColor("accent"),
                ).joinToString("\t")
            }

            check(entries.isNotEmpty()) { "No theme descriptors were found for screenshots" }
            check(entries.map { it.substringBefore('\t') }.toSet().size == entries.size) {
                "Theme names produce duplicate screenshot slugs"
            }

            outputFile.parentFile.mkdirs()
            outputFile.writeText(entries.joinToString("\n", postfix = "\n"))
        }
    }

    val uiScreenshotTest by registering(Test::class) {
        group = "verification"
        description = "Runs Remote Robot assertions and captures every installed theme with sample code."
        dependsOn(generateScreenshotThemeManifest)
        testClassesDirs = uiTestSourceSet.output.classesDirs
        classpath = uiTestSourceSet.runtimeClasspath
        useJUnitPlatform()
        shouldRunAfter(test)
        jvmArgs("--add-opens=java.base/java.lang=ALL-UNNAMED")

        val screenshotDirectory = providers.gradleProperty("screenshotDir")
            .orElse(layout.buildDirectory.dir("ui-test/screenshots").map { it.asFile.absolutePath })
        systemProperty("bluloco.screenshot.dir", screenshotDirectory.get())
        systemProperty("bluloco.theme.manifest", screenshotThemeManifest.get().asFile.absolutePath)
        systemProperty("bluloco.close.ide", providers.gradleProperty("closeIde").orElse("false").get())
        outputs.dir(screenshotDirectory)
        outputs.upToDateWhen { false }
    }

    val createScreenshots by registering(Exec::class) {
        group = "documentation"
        description = "Creates annotated 1200x760 Marketplace screenshots for every registered theme."
        dependsOn(generateEditorSchemes, generateScreenshotThemeManifest, uiTestSourceSet.classesTaskName)

        val outputDirectory = layout.projectDirectory.dir("marketplace/screenshots")
        inputs.files(editorSchemeTemplate, editorSchemeTokens)
        inputs.dir(layout.projectDirectory.dir("src/main/resources/themes"))
        inputs.dir(layout.projectDirectory.dir("src/uiTest"))
        inputs.file(layout.projectDirectory.file("scripts/create-screenshots.sh"))
        outputs.dir(outputDirectory)
        outputs.upToDateWhen { false }

        commandLine("bash", layout.projectDirectory.file("scripts/create-screenshots.sh").asFile.absolutePath)
        environment("SCREENSHOT_OUTPUT_DIR", outputDirectory.asFile.absolutePath)

        doFirst {
            check(!System.getProperty("os.name").startsWith("Windows", ignoreCase = true)) {
                "createScreenshots currently requires macOS or Linux; CI still runs Robot assertions on Windows."
            }
        }
    }

    val createDemoVideo by registering(Exec::class) {
        group = "documentation"
        description = "Creates a 13-second Marketplace demo video from real IDE screenshots."
        dependsOn(createScreenshots)

        val screenshotDirectory = layout.projectDirectory.dir("marketplace/screenshots")
        val outputFile = layout.projectDirectory.file("marketplace/media/bluloco-demo.mp4")
        inputs.dir(screenshotDirectory)
        inputs.file(layout.projectDirectory.file("scripts/create-demo-video.sh"))
        outputs.file(outputFile)
        outputs.upToDateWhen { false }

        commandLine(
            "bash",
            layout.projectDirectory.file("scripts/create-demo-video.sh").asFile.absolutePath,
            screenshotDirectory.asFile.absolutePath,
            outputFile.asFile.absolutePath,
        )
    }

    register("createMarketplaceMedia") {
        group = "documentation"
        description = "Creates the complete Marketplace screenshot and video set."
        dependsOn(createDemoVideo)
    }

    wrapper {
        gradleVersion = providers.gradleProperty("gradleVersion").get()
    }

    publishPlugin {
        dependsOn(patchChangelog)
    }

}

val stripUiTestProductDescriptor by tasks.registering(StripProductDescriptorTask::class) {
    group = "verification"
    description = "Omits the paid product descriptor from the Robot test sandbox only."
    dependsOn("prepareSandbox_runIdeForUiTests")
    sandboxPluginLibDirectory.set(
        layout.projectDirectory.dir(
            ".intellijPlatform/sandbox/${rootProject.name}/IU-${providers.gradleProperty("platformVersion").get()}" +
                "/plugins_runIdeForUiTests/${rootProject.name}/lib",
        ),
    )
    outputs.upToDateWhen { false }
}

intellijPlatformTesting {
    runIde {
        register("runIdeForUiTests") {
            task {
                jvmArgumentProviders += CommandLineArgumentProvider {
                    listOf(
                        "-Drobot-server.port=8082",
                        "-Dide.mac.message.dialogs.as.sheets=false",
                        "-Djb.privacy.policy.text=<!--999.999-->",
                        "-Djb.consents.confirmation.enabled=false",
                        "-Dide.mac.file.chooser.native=false",
                        "-DjbScreenMenuBar.enabled=false",
                        "-Dapple.laf.useScreenMenuBar=false",
                        "-Didea.trust.all.projects=true",
                        "-Dide.show.tips.on.startup.default.value=false",
                    )
                }
                argumentProviders += CommandLineArgumentProvider {
                    listOf(demoProjectDirectory.asFile.absolutePath)
                }
            }

            plugins {
                robotServerPlugin(libs.versions.remoteRobot.get())
            }
        }
    }
}

tasks.named("runIdeForUiTests") {
    dependsOn(stripUiTestProductDescriptor)
}
