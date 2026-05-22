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
    implementation("io.grpc:grpc-netty-shaded:1.73.0")
    implementation("io.grpc:grpc-protobuf:1.73.0")
    implementation("io.grpc:grpc-stub:1.73.0")
    implementation("com.google.protobuf:protobuf-java:4.33.2")
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
