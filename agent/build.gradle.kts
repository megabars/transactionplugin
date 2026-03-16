plugins {
    java
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
    toolchain { languageVersion.set(JavaLanguageVersion.of(17)) }
}

dependencies {
    // Byte Buddy for bytecode instrumentation
    implementation("net.bytebuddy:byte-buddy:1.14.18")
    implementation("net.bytebuddy:byte-buddy-agent:1.14.18")
    // Spring TX — compile-only, available at runtime from the target app's classpath
    compileOnly("org.springframework:spring-tx:6.1.14")
    compileOnly("org.springframework:spring-context:6.1.14")
    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("org.springframework:spring-tx:6.1.14")
    testImplementation("org.springframework:spring-context:6.1.14")
    testImplementation("com.google.code.gson:gson:2.11.0")
}

tasks.test {
    useJUnitPlatform()
}

// Fat JAR — agent must be self-contained
tasks.jar {
    manifest {
        attributes(
            "Premain-Class" to "com.txplugin.agent.AgentMain",
            "Agent-Class" to "com.txplugin.agent.AgentMain",
            "Can-Redefine-Classes" to "true",
            "Can-Retransform-Classes" to "true",
            "Can-Set-Native-Method-Prefix" to "true"
        )
    }
    from(configurations.runtimeClasspath.get().map { if (it.isDirectory) it else zipTree(it) })
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
