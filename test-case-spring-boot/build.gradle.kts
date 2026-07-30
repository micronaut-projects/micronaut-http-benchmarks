plugins {
    id("java")
    id("application")
    alias(libs.plugins.spring.boot)
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.Main")
}

dependencies {
    implementation(libs.spring.boot.starter.web) {
        exclude("org.springframework.boot", "spring-boot-starter-tomcat")
    }
    implementation(libs.spring.boot.starter.jetty)
    runtimeOnly(libs.jetty.alpn.server)
    runtimeOnly(libs.jetty.alpn.java.server)
    runtimeOnly(libs.jetty.http2.server)
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
