plugins {
    alias(libs.plugins.micronaut.application)
}

group = "org.example"
version = "1.0.0"

repositories {
    mavenCentral()
}

micronaut {
    runtime("netty")
    ignoredAutomaticDependencies.add("io.micronaut.serde:micronaut-serde-processor")
    testRuntime("junit5")
    processing {
        incremental(true)
        annotations("org.example.*")
    }
}

application {
    mainClass.set("org.example.Main")
}

val nativeBuild = providers.gradleProperty("nativeBuild").isPresent
val nativeImageArgs = providers.gradleProperty("nativeImageArgs").orNull
if (nativeBuild) {
    graalvmNative {
        binaries {
            named("main") {
                buildArgs.add("--initialize-at-run-time=io.netty")
                if (!nativeImageArgs.isNullOrEmpty()) {
                    buildArgs.addAll(nativeImageArgs.split(","))
                }
            }
        }
    }
}

val codec = providers.gradleProperty("codec").orElse("jackson-databind").get()
require(codec in setOf("jackson-databind", "micronaut-serialization")) {
    "codec must be one of: jackson-databind, micronaut-serialization"
}

dependencies {
    implementation(mn.micronaut.http.client)
    implementation("io.projectreactor:reactor-core")
    implementation(libs.agroal)
    implementation(mn.postgresql)

    runtimeOnly("io.netty:netty-transport-native-io_uring::linux-x86_64")
    runtimeOnly("io.netty:netty-transport-native-io_uring::linux-aarch_64")
    runtimeOnly(mn.snakeyaml)
    runtimeOnly(mn.logback.classic)

    testImplementation(mn.micronaut.test.junit5)
    testRuntimeOnly(mn.junit.platform.launcher)

    when (codec) {
        "jackson-databind" -> {
            implementation(mn.micronaut.jackson.databind)
            implementation(mn.micronaut.serde.api)
        }
        "micronaut-serialization" -> {
            implementation(mn.micronaut.serde.jackson)
            annotationProcessor(mn.micronaut.serde.processor)
        }
    }
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(25))
}

tasks.named<Test>("test") {
    useJUnitPlatform()
}

val copyRuntimeDependencies = tasks.register("copyRuntimeDependencies", Sync::class) {
    from(configurations.runtimeClasspath)
    into(layout.buildDirectory.dir("libs/libs"))
}

tasks.named<Jar>("jar") {
    archiveFileName.set("micronaut-framework.jar")
    manifest {
        attributes["Main-Class"] = "org.example.Main"
    }
    doFirst {
        manifest.attributes["Class-Path"] = configurations.runtimeClasspath.get().files.joinToString(" ") { "libs/${it.name}" }
    }
    dependsOn(copyRuntimeDependencies)
}
