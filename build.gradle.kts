plugins {
    id("java")
    id("org.jetbrains.intellij.platform") version "2.20.1"
}

group = "com.github.baileyseye"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // Build against the IntelliJ IDEA installation present on this machine.
        local(providers.gradleProperty("localIdePath").get())

        // Git API (git4idea) and the VCS module are required by the plugin.
        bundledPlugin("Git4Idea")
        bundledModule("com.intellij.modules.vcs")
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

intellijPlatform {
    buildSearchableOptions = false

    pluginConfiguration {
        name = "Branch Commit Message"
        version = project.version.toString()

        ideaVersion {
            // IntelliJ IDEA 2026.2 (build 262) and newer.
            sinceBuild = "262"
        }

        vendor {
            name = "baileyseye"
        }
    }
}
