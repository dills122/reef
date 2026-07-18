import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.0"
    application
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.2")
    implementation("org.postgresql:postgresql:42.7.4")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("io.grpc:grpc-netty-shaded:1.73.0")
    implementation("io.grpc:grpc-protobuf:1.73.0")
    implementation("io.grpc:grpc-stub:1.73.0")
    implementation("io.nats:jnats:2.25.3")
    implementation("org.apache.kafka:kafka-clients:3.9.0")
    implementation("com.google.protobuf:protobuf-java:4.33.2")
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
    finalizedBy(tasks.jacocoTestReport)
}

sourceSets.named("test") {
    kotlin.exclude(
        "com/reef/platform/admin/AdminCliAdapterTest.kt",
        "com/reef/platform/api/AdminJsonValidationTest.kt",
        "com/reef/platform/api/PlatformHttpServerBoundaryTest.kt",
        "com/reef/platform/api/PlatformHttpServerHelpersTest.kt",
        "com/reef/platform/application/admin/AdminApplicationServiceTest.kt",
        "com/reef/platform/application/arena/**",
        "com/reef/platform/infrastructure/persistence/PostgresSchemaMigrationIntegrationTest.kt",
        "com/reef/platform/infrastructure/persistence/PostgresSchemaRequirementsTest.kt"
    )
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
                minimum = "0.58".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
