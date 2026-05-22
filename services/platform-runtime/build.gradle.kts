plugins {
    kotlin("jvm") version "2.1.10"
    application
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    implementation("org.postgresql:postgresql:42.7.4")
    testImplementation(kotlin("test"))
}

application {
    mainClass.set("com.reef.platform.MainKt")
}

kotlin {
    jvmToolchain(21)
}

tasks.test {
    useJUnitPlatform()
}
