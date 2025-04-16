plugins {
    id("java")
    id("application")
    id("com.github.johnrengelman.shadow")
}

repositories {
    mavenCentral()
}

application {
    mainClass.set("org.example.Main")
}

dependencies {
    implementation("io.helidon.webserver:helidon-webserver:4.1.7")
    implementation("io.helidon.webserver:helidon-webserver-http2:4.1.7")
    implementation("io.helidon.http.media:helidon-http-media-jsonb:4.1.7")

    // for self-signed cert generation
    implementation("io.netty:netty-handler:4.1.119.Final")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.80")

    testImplementation("org.junit.jupiter:junit-jupiter-engine:5.9.2")
    testImplementation("com.fasterxml.jackson.core:jackson-databind:2.16.1")
}

tasks.getByName<Test>("test") {
    useJUnitPlatform()
}