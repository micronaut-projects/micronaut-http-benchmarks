plugins {
    id("io.micronaut.testcase")
}

repositories {
    mavenCentral()
    maven {
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
}

dependencies {
    runtimeOnly("org.yaml:snakeyaml")
    implementation("io.micronaut:micronaut-http-server-netty:4.7.2")
}

micronaut {
    version("4.6.3")
}
