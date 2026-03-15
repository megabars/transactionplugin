import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    kotlin("jvm") version "1.9.23"
    id("org.jetbrains.intellij.platform") version "2.1.0"
}

kotlin {
    jvmToolchain(17)
}

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2023.3")
        bundledPlugin("com.intellij.java")
        testFramework(TestFrameworkType.Platform)
        instrumentationTools()
    }
    // Gson for JSON parsing in plugin
    implementation("com.google.code.gson:gson:2.11.0")
}

intellijPlatform {
    pluginConfiguration {
        id = "com.txplugin"
        name = "Transaction Monitor"
        version = "0.2.0"
        description = "Real-time Spring Boot transaction monitoring with inlay hints"
        ideaVersion {
            sinceBuild = "233"
            untilBuild = provider { null }
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Copy built agent JAR into plugin resources before processing resources
tasks.named("processResources") {
    dependsOn(":agent:jar")
    doLast {
        val agentJar = project(":agent").tasks.named<Jar>("jar").get().archiveFile.get().asFile
        val dest = layout.buildDirectory.dir("resources/main/agent").get().asFile
        dest.mkdirs()
        agentJar.copyTo(dest.resolve("transaction-agent.jar"), overwrite = true)
    }
}
