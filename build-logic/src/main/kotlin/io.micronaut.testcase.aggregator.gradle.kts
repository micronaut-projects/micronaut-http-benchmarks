/**
 * This plugin is intended to be used in the root project, in order to aggregate
 * the artifacts from all the subprojects: shadow jars and native images.
 */
plugins {
    id("base")
}

val shadowJarAggregator = configurations.create("shadowJarAggregator") {
    isCanBeConsumed = false
    isCanBeResolved = true
}
val nativeImages = configurations.create("nativeImages") {
    isCanBeConsumed = false
    isCanBeResolved = true
}

val thisProject = project

allprojects {
    afterEvaluate {
        if (plugins.hasPlugin("io.micronaut.testcase")) {
            thisProject.dependencies.add("shadowJarAggregator", dependencies.project(mapOf("path" to path, "configuration" to "shadowJars")))
            thisProject.dependencies.add("nativeImages", dependencies.project(mapOf("path" to path, "configuration" to "nativeImages")))
        }
    }
}

tasks.register<Sync>("shadowJar") {
    from(shadowJarAggregator)
    into(rootProject.layout.buildDirectory.dir("libs"))
}

tasks.register<Sync>("nativeCompile") {
    from(nativeImages)
    into(rootProject.layout.buildDirectory.dir("native-images"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
