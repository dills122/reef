import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.21"
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
    implementation("org.postgresql:postgresql:42.7.14")
    implementation("com.zaxxer:HikariCP:7.1.0")
    implementation("io.grpc:grpc-netty-shaded:1.85.0")
    implementation("io.grpc:grpc-protobuf:1.85.0")
    implementation("io.grpc:grpc-stub:1.85.0")
    implementation("io.nats:jnats:2.26.4")
    implementation("org.apache.kafka:kafka-clients:4.4.0")
    implementation("org.apache.kafka:kafka-streams:4.4.0")
    implementation("com.google.protobuf:protobuf-java:4.36.2")

    testImplementation(kotlin("test"))
    testImplementation("org.apache.kafka:kafka-streams-test-utils:4.4.0")
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
    // Tests tagged "broker" start a disposable Redpanda container (Docker). Run them with -PbrokerTests; they are excluded from the default run.
    useJUnitPlatform {
        if (!project.hasProperty("brokerTests")) excludeTags("broker")
        if (!project.hasProperty("researchTests")) excludeTags("research")
    }
    // Research runs hold millions of entities in memory (S3c scale spike); the default 512 MiB test heap cannot.
    // -PtestHeap=NNg raises it for the paced soak (the capacity ledger keeps every execution of the run in memory).
    if (project.hasProperty("researchTests")) maxHeapSize = (project.findProperty("testHeap") as String?) ?: "12g"
    // Research runs measure throughput. The JaCoCo agent instruments every class the test JVM loads (Kafka, protobuf, RocksDB included) and slows it, so the capacity ledger must not be measured with it.
    if (project.hasProperty("researchTests")) {
        extensions.getByType(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java).isEnabled = false
    }
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
