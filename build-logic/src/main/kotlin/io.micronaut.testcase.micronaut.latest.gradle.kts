plugins {
    id("io.micronaut.testcase")
}

repositories {
    mavenCentral()
}

val libs = extensions.getByType(VersionCatalogsExtension::class.java).named("libs")

dependencies {
    runtimeOnly("org.yaml:snakeyaml")
    implementation("io.micronaut:micronaut-http-server-netty")
    implementation("io.micronaut:micronaut-http-client")
}

micronaut {
    version(libs.findVersion("micronaut").get().toString())
}
