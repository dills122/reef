plugins { java; application }
repositories { mavenCentral() }
dependencies {
    implementation("org.apache.kafka:kafka-streams:4.3.1")
    implementation("org.apache.kafka:kafka-streams-test-utils:4.3.1")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.20.0")
    runtimeOnly("org.slf4j:slf4j-simple:2.0.17")
}
application { mainClass.set("CalcifyExperiment") }
tasks.register<Copy>("dependenciesForRun") { from(configurations.runtimeClasspath); into(layout.buildDirectory.dir("deps")) }
