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
    implementation(kotlin("reflect"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310:2.22.1")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin:2.22.1")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.reef.stockdata.MainKt")
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
    description = "Fails when stock-data Kotlin tests are hidden with source-set exclusions."
    doLast {
        val exclusions = kotlin.sourceSets.getByName("test").kotlin.excludes.sorted()
        check(exclusions.isEmpty()) {
            "stock-data test source exclusions are forbidden; repair or deliberately relocate stale tests instead: ${exclusions.joinToString()}"
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
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.jacocoTestReport)

    violationRules {
        rule {
            limit {
                counter = "INSTRUCTION"
                minimum = "0.75".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
