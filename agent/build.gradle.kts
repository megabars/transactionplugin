plugins {
    java
    id("com.github.johnrengelman.shadow") version "8.1.1"
}

java {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
    toolchain { languageVersion.set(JavaLanguageVersion.of(11)) }
}

dependencies {
    // Byte Buddy for bytecode instrumentation
    implementation("net.bytebuddy:byte-buddy:1.14.18")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.18")
    // Spring TX — compile-only, available at runtime from the target app's classpath
    compileOnly("org.springframework:spring-tx:5.3.39")
    compileOnly("org.springframework:spring-context:5.3.39")
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework:spring-tx:5.3.39")
    testImplementation("org.springframework:spring-context:5.3.39")
    testImplementation("com.google.code.gson:gson:2.11.0")
}

tasks.test {
    useJUnitPlatform()
}

val agentManifest = mapOf(
    "Premain-Class" to "com.txplugin.agent.AgentMain",
    "Agent-Class" to "com.txplugin.agent.AgentMain",
    "Can-Redefine-Classes" to "true",
    "Can-Retransform-Classes" to "true",
    "Can-Set-Native-Method-Prefix" to "true"
)

// Regular jar — thin, used as input for shadowJar
tasks.jar {
    manifest { attributes(agentManifest) }
}

// Shadow (shaded) fat JAR — Byte Buddy relocated to avoid version conflicts
// with the target app's own Byte Buddy (e.g. from Mockito, Hibernate, etc.)
tasks.shadowJar {
    manifest { attributes(agentManifest) }
    // Relocate Byte Buddy to a private package so it never clashes with
    // net.bytebuddy.* already on the app's classpath
    relocate("net.bytebuddy", "com.txplugin.agent.bytebuddy")
    mergeServiceFiles()
    archiveClassifier.set("")
}

// shadowJar is the actual agent artifact — ensure it is built by default
tasks.assemble {
    dependsOn(tasks.shadowJar)
}
