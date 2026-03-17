import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType

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
        pluginVerifier()
    }
    // Gson for JSON parsing in plugin
    implementation("com.google.code.gson:gson:2.11.0")
    // SQL formatter for Detail Panel "Format SQL" button
    implementation("com.github.vertical-blank:sql-formatter:2.0.5")
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // JUnit 4 required by IJ Platform's JUnit5TestSessionListener at test runtime
    testRuntimeOnly("junit:junit:4.13.2")
}

tasks.test {
    useJUnitPlatform()
}

intellijPlatform {
    pluginConfiguration {
        id = "com.github.megabars.transactionmonitor"
        name = "Transaction Monitor"
        version = "0.3.0"

        vendor {
            name = "megabars"
            url = "https://github.com/megabars/transactionplugin"
        }

        description = providers.provider {
            """
            <p>Real-time Spring Boot transaction monitoring — zero code changes required.</p>
            <p>The plugin automatically injects a Java Agent and captures every @Transactional
            method execution: duration, SQL queries with bound parameters, batch row counts,
            and exception stack traces.</p>
            <h3>Features</h3>
            <ul>
                <li><b>Inlay hints</b> above @Transactional methods — status, duration, propagation, batch count</li>
                <li><b>Tool Window</b> with full history and status filter (COMMITTED / ROLLED BACK)</li>
                <li><b>SQL with bound parameters</b> and batch row counts in Detail Panel</li>
                <li>Works with Spring Boot 2.x and 3.x, Java and Kotlin</li>
                <li>Supports nested transactions and noRollbackFor semantics</li>
            </ul>
            """.trimIndent()
        }

        changeNotes = providers.provider {
            """
            <h3>0.3.0</h3>
            <ul>
                <li>Real-time @Transactional monitoring via Java Agent (no code changes required)</li>
                <li>Inlay hints with status, duration, propagation type, and batch row count</li>
                <li>Tool Window with full transaction history, sorting, and status filter</li>
                <li>SQL with bound parameters and batch rows in Detail Panel</li>
                <li>Navigate to source from detail panel</li>
                <li>Supports nested transactions and noRollbackFor semantics</li>
                <li>Spring Boot 2.x and 3.x, Java and Kotlin</li>
                <li>IntelliJ IDEA Community and Ultimate 2023.3+</li>
            </ul>
            """.trimIndent()
        }

        ideaVersion {
            sinceBuild = "233"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            ide(IntelliJPlatformType.IntellijIdeaCommunity, "2023.3.7")
        }
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

// Copy built agent JAR into plugin resources before processing resources
tasks.named("processResources") {
    dependsOn(":agent:shadowJar")
    doLast {
        val agentJar = project(":agent").tasks.named<Jar>("shadowJar").get().archiveFile.get().asFile
        val dest = layout.buildDirectory.dir("resources/main/agent").get().asFile
        dest.mkdirs()
        agentJar.copyTo(dest.resolve("transaction-agent.jar"), overwrite = true)
    }
}
