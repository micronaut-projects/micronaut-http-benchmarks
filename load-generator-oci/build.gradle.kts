plugins {
    id("io.micronaut.application")
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
    mavenLocal()
    maven {
        url = uri("https://s01.oss.sonatype.org/content/repositories/snapshots")
    }
}

dependencies {
    runtimeOnly("org.yaml:snakeyaml")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-sdk")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-identity")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-core")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-httpclient-netty")
    implementation("io.micronaut.toml:micronaut-toml")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.hyperfoil:hyperfoil-api:0.27")
    implementation("io.hyperfoil:hyperfoil-core:0.27")
    implementation("io.hyperfoil:hyperfoil-clustering:0.27")
}

micronaut {
    version("4.2.3")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.micronaut.benchmark.loadgen.oci.*")
    }
}
