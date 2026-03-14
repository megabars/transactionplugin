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
    // Jackson for JSON serialization (newline-delimited)
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    // Spring TX — compile-only, available at runtime from the target app's classpath
    compileOnly("org.springframework:spring-tx:6.1.14")
    compileOnly("org.springframework:spring-context:6.1.14")
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
