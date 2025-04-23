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
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-bastion")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-computeinstanceagent")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-httpclient-netty")
    implementation("io.micronaut.toml:micronaut-toml")
    implementation("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-http-client")
    implementation("io.hyperfoil:hyperfoil-api:0.27")
    implementation("io.hyperfoil:hyperfoil-core:0.27")
    implementation("io.hyperfoil:hyperfoil-clustering:0.27")
    implementation("tools.profiler:async-profiler:4.0:linux-x64")
    implementation("tools.profiler:async-profiler:4.0:linux-arm64")
    implementation("tools.profiler:jfr-converter:4.0")
    implementation("org.apache.sshd:sshd-core:2.15.0")
    implementation("org.apache.sshd:sshd-scp:2.15.0")
    implementation("org.apache.sshd:sshd-sftp:2.15.0")
}

micronaut {
    version("4.2.3")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.micronaut.benchmark.loadgen.oci.*")
    }
}
