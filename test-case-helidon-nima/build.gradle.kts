plugins {
    id("java")
    id("application")
    id("com.gradleup.shadow")
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.Main")
}

dependencies {
    implementation(libs.helidon.webserver)
    implementation(libs.helidon.webserver.http2)
    implementation(libs.helidon.http.media.jsonb)

    // for self-signed cert generation
    implementation(libs.netty.handler)
    implementation(libs.bcpkix)

    testImplementation(libs.jupiter)
    testImplementation(libs.jackson.databind)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}
