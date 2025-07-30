plugins {
    java
    application
    id("com.gradleup.shadow")
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":relay-api"))
    implementation(libs.logback.classic)
}

application {
    mainClass.set("io.micronaut.benchmark.relay.agent.Main")
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(21)
}
