plugins {
    id("io.micronaut.library")
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
    mavenLocal()
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

dependencies {
    implementation("org.yaml:snakeyaml")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-sdk")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-identity")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-core")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-bastion")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-computeinstanceagent")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-psql")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-bmc-objectstorage")
    implementation("io.micronaut.oraclecloud:micronaut-oraclecloud-httpclient-netty")
    implementation("io.micronaut.toml:micronaut-toml")
    implementation(libs.netty.pkitesting)
    api("io.micronaut:micronaut-jackson-databind")
    implementation("io.micronaut:micronaut-http-client")
    api(libs.hyperfoil.api)
    api(libs.hyperfoil.core)
    api(libs.hyperfoil.clustering)
    implementation(libs.mina.sshd.core)
    implementation(libs.mina.sshd.scp)
    implementation(libs.mina.sshd.sftp)
    implementation("io.projectreactor:reactor-core")
    implementation(libs.logback.classic)
    implementation(libs.bcpkix)
    runtimeOnly(libs.postgresql)
    implementation(project(":relay-api"))
    testImplementation(libs.testcontainers)
    testImplementation(libs.testcontainers.junit.jupiter)
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
