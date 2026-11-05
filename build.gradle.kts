plugins {
    kotlin("jvm") version "2.2.20"
    id("org.jetbrains.intellij.platform") version "2.10.5"
}

group = "local.debugger.mcp"
version = "0.1.4"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        local(providers.gradleProperty("ideaPath").orElse("C:/Program Files/JetBrains/IntelliJ IDEA 2025.3"))
        bundledPlugin("com.intellij.java")
        bundledPlugin("com.intellij.mcpServer")
    }
}

kotlin { jvmToolchain(21) }
intellijPlatform {
    // Kotlin supplies null checks; this plugin contains no GUI Designer forms.
    instrumentCode = false
    buildSearchableOptions = false
    pluginConfiguration {
        name = "Java Debugger MCP"
        ideaVersion { sinceBuild = "253"; untilBuild = "253.*" }
    }
}
