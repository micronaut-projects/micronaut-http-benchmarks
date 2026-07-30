
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
    implementation(libs.netty.codec.http)
    implementation(libs.netty.codec.http2)
    implementation(variantOf(libs.netty.io.uring) { classifier("linux-x86_64") })
    implementation(libs.jackson.databind)
    implementation(libs.bcpkix)
    runtimeOnly(libs.logback.classic)
    testImplementation(libs.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.withType<Test> {
    useJUnitPlatform()
}
