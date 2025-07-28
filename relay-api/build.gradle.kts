plugins {
    id("io.micronaut.library")
}

group = "org.example"
version = "unspecified"

repositories {
    mavenCentral()
}

dependencies {
    api(libs.mina.sshd.core)
    implementation(libs.mina.sshd.scp)
    implementation(libs.mina.sshd.sftp)
}

micronaut {
    version("4.8.2")
}
