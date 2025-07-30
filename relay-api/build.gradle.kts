plugins {
    java
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.netty.handler)
    implementation(libs.slf4j)
    implementation(libs.netty.codec.http2)
    testRuntimeOnly(libs.logback.classic)
    testImplementation(libs.netty.pkitesting)
    testImplementation(libs.jupiter)
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

tasks.named<JavaCompile>("compileJava") {
    options.release.set(21)
}
