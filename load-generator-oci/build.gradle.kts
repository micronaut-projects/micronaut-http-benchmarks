plugins {
    id("io.micronaut.library")
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    runtimeOnly("org.yaml:snakeyaml")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-sdk")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-identity")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-core")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-bastion")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-computeinstanceagent")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-psql")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-httpclient-netty")
    implementation("io.micronaut.toml:micronaut-toml")
    implementation(libs.netty.pkitesting)
    api("io.micronaut:micronaut-jackson-databind")
    runtimeOnly("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")
    implementation("io.micronaut:micronaut-http-client")
    api(libs.hyperfoil.api)
    api(libs.hyperfoil.core)
    api(libs.hyperfoil.clustering)
    runtimeOnly(variantOf(libs.async.profiler) { classifier("linux-x64") })
    runtimeOnly(variantOf(libs.async.profiler) { classifier("linux-arm64") })
    api(libs.async.profiler.jfr.converter)
    implementation(libs.mina.sshd.core)
    implementation(libs.mina.sshd.scp)
    implementation(libs.mina.sshd.sftp)
    runtimeOnly(libs.logback.classic)
    implementation(libs.bcpkix)
    runtimeOnly(libs.postgresql)
    implementation(project(":relay-api"))
    testImplementation("org.testcontainers:testcontainers")
    testImplementation("org.testcontainers:junit-jupiter")
    testRuntimeOnly("org.apache.commons:commons-compress:1.27.1") // dependency issue
}

micronaut {
    version(libs.versions.micronaut.asProvider().get())
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("io.micronaut.benchmark.loadgen.oci.*")
    }
}

tasks.named<ProcessResources>("processResources") {
    // TODO: find a better solution

    from("../relay-agent/build/libs/relay-agent-all.jar") {
        rename { "relay-agent-all.jar" }
    }
    dependsOn(
        project(":relay-agent").tasks.named("shadowJar")
    )
}
