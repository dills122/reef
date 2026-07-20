import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    application
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("io.grpc:grpc-netty-shaded:1.82.2")
    implementation("io.grpc:grpc-protobuf:1.82.2")
    implementation("io.grpc:grpc-stub:1.82.2")
    implementation("io.nats:jnats:2.26.0")
    implementation("org.apache.kafka:kafka-clients:4.3.1")
    implementation("com.google.protobuf:protobuf-java:4.35.1")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.reef.platform.MainKt")
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_21)
    }
}

tasks.test {
    useJUnitPlatform()
    dependsOn("verifyNoTestSourceExclusions")
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register("verifyNoTestSourceExclusions") {
    group = "verification"
    description = "Fails when platform-runtime Kotlin tests are hidden with source-set exclusions."
    doLast {
        val exclusions = kotlin.sourceSets.getByName("test").kotlin.excludes.sorted()
        check(exclusions.isEmpty()) {
            "platform-runtime test source exclusions are forbidden; move product-owned tests or repair stale tests instead: ${exclusions.joinToString()}"
        }
    }
}

tasks.jacocoTestReport {
    dependsOn(tasks.test)

    reports {
        xml.required.set(true)
        csv.required.set(true)
        html.required.set(true)
    }

    classDirectories.setFrom(
        files(
            classDirectories.files.map {
                fileTree(it) {
                    exclude("reef/contracts/**")
                    exclude("com/reef/platform/tools/**")
                    exclude("com/reef/platform/Main*.class")
                }
            },
        ),
    )
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)
    classDirectories.setFrom(tasks.jacocoTestReport.get().classDirectories)

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.61".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
