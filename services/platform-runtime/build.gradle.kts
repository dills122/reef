import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    application
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.3")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("io.grpc:grpc-netty-shaded:1.84.0")
    implementation("io.grpc:grpc-protobuf:1.84.0")
    implementation("io.grpc:grpc-stub:1.84.0")
    implementation("io.nats:jnats:2.26.3")
    implementation("org.apache.kafka:kafka-clients:4.3.1")
    implementation("org.apache.kafka:kafka-streams:4.3.1")
    testImplementation("org.apache.kafka:kafka-streams-test-utils:4.3.1")
    implementation("com.google.protobuf:protobuf-java:4.36.2")
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

// Test-only external process probes exercise real broker crash/recovery boundaries.
tasks.register<Copy>("resolverProbeDependencies") {
    from(configurations.testRuntimeClasspath)
    into(layout.buildDirectory.dir("resolver-probe-deps"))
}

// Compat bootstrap packages same migration-owned order identity SQL as deployment.
val runtimeOrderIdentityMigrations = files(
    "../../scripts/dev/db/migrations/runtime/0073_runtime_order_run_identity.sql",
    "../../scripts/dev/db/migrations/runtime/0074_matching_ioc_cancellation.sql",
)
val verifyRuntimeOrderIdentityMigration = tasks.register("verifyRuntimeOrderIdentityMigration") {
    group = "verification"
    doLast {
        runtimeOrderIdentityMigrations.forEach { migration ->
            check(migration.isFile) { "Missing runtime bootstrap migration: $migration" }
        }
    }
}
tasks.processResources {
    dependsOn(verifyRuntimeOrderIdentityMigration)
    from(runtimeOrderIdentityMigrations) { into("db") }
}
