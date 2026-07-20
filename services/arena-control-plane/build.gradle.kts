import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.10"
    jacoco
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(files("../platform-runtime/build/libs/platform-runtime.jar"))
    implementation(kotlin("stdlib"))
    implementation("com.fasterxml.jackson.core:jackson-databind:2.22.1")
    implementation("org.postgresql:postgresql:42.7.13")
    implementation("com.zaxxer:HikariCP:7.1.0")
    testImplementation(kotlin("test"))
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
    description = "Fails when Arena control-plane Kotlin tests are hidden with source-set exclusions."
    doLast {
        val exclusions = kotlin.sourceSets.getByName("test").kotlin.excludes.sorted()
        check(exclusions.isEmpty()) {
            "arena-control-plane test source exclusions are forbidden; repair or deliberately relocate stale tests instead: ${exclusions.joinToString()}"
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
                minimum = "0.56".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}
