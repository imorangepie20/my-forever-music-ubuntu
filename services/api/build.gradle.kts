import org.gradle.api.GradleException
import org.springframework.boot.gradle.tasks.run.BootRun
import java.io.File

plugins {
    java
    id("org.springframework.boot") version "3.5.14"
}

apply(plugin = "io.spring.dependency-management")

group = "io.myforevermusic"
version = "0.1.0-SNAPSHOT"
description = "My Forever Music API"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

configurations {
    compileOnly {
        extendsFrom(configurations.annotationProcessor.get())
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-security")
    implementation("org.springframework.boot:spring-boot-starter-data-jpa")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-database-postgresql")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.17")
    implementation("org.jsoup:jsoup:1.18.3")

    developmentOnly("org.springframework.boot:spring-boot-devtools")
    runtimeOnly("org.postgresql:postgresql")

    annotationProcessor("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
    testImplementation("org.testcontainers:junit-jupiter")
    testImplementation("org.testcontainers:postgresql")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

tasks.wrapper {
    gradleVersion = "8.14.3"
    distributionType = Wrapper.DistributionType.BIN
}

fun parseLocalEnvFile(file: File): Map<String, String> {
    val keyPattern = Regex("[A-Za-z_][A-Za-z0-9_]*")

    return buildMap {
        file.readLines().forEachIndexed { index, rawLine ->
            val trimmedLine = rawLine.trim()
            if (trimmedLine.isBlank() || trimmedLine.startsWith("#")) {
                return@forEachIndexed
            }

            val separatorIndex = rawLine.indexOf('=')
            if (separatorIndex <= 0) {
                throw GradleException("Invalid env assignment in ${file.path}:${index + 1}")
            }

            val key = rawLine.substring(0, separatorIndex).trim()
            if (!keyPattern.matches(key)) {
                throw GradleException("Invalid env key in ${file.path}:${index + 1}: ${key}")
            }

            val rawValue = rawLine.substring(separatorIndex + 1).trim()
            val value = if (
                rawValue.length >= 2
                && ((rawValue.startsWith('"') && rawValue.endsWith('"'))
                    || (rawValue.startsWith('\'') && rawValue.endsWith('\'')))
            ) {
                rawValue.substring(1, rawValue.length - 1)
            } else {
                rawValue
            }

            put(key, value)
        }
    }
}

tasks.named<BootRun>("bootRun") {
    val localEnvFile = project.file(".env.local")
    if (localEnvFile.isFile) {
        parseLocalEnvFile(localEnvFile).forEach { (key, value) ->
            val shellValue = providers.environmentVariable(key).orNull
            if (shellValue.isNullOrBlank()) {
                environment(key, value)
            }
        }
    }
}
