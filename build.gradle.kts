plugins {
    `java-library`
    `maven-publish`
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

repositories {
    mavenLocal()
    mavenCentral()

    maven {
        url = uri("https://repo.papermc.io/repository/maven-public/")
    }

    maven {
        url = uri("https://oss.sonatype.org/content/repositories/snapshots/")
    }

    maven {
        url = uri("https://mvn.lumine.io/repository/maven-public/")
    }

    maven {
        url = uri("https://repo.maven.apache.org/maven2/")
    }

    maven {
        url = uri("https://maven.citizensnpcs.co/repo")
    }

    maven("https://repo.codemc.org/repository/maven-public/") {
        name = "codemc"
    }
}

dependencies {
    implementation("io.papermc.paper:paper-api:${project.properties["craftbukkit.version"]}")
    implementation("com.denizenscript:denizen:${project.properties["denizen.version"]}")
    implementation("com.github.javaparser:javaparser-core:3.25.10")
}

fun buildNumber(): String = project.findProperty("BUILD_NUMBER") as? String ?: "UNKNOWN"

group = "meigo"
version = buildNumber() + "-DEV"
description = "DenizenReflect"

publishing {
    publications.create<MavenPublication>("maven") {
        from(components["java"])
    }
}

tasks.withType<JavaCompile>() {
    options.encoding = "UTF-8"
}

tasks.withType<Javadoc>() {
    options.encoding = "UTF-8"
}
tasks.processResources {
    filesMatching("plugin.yml") {
        expand(mapOf("version" to project.properties["BUILD_NUMBER"]))
    }
}
