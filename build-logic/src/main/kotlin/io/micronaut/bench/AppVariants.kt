package io.micronaut.bench

import org.gradle.api.Action
import org.gradle.api.initialization.Settings
import org.gradle.api.invocation.Gradle
import org.gradle.api.plugins.JavaPluginExtension
import java.io.File
import javax.inject.Inject

abstract class AppVariants(val settings: Settings) {

    @get:Inject
    abstract val gradle: Gradle

    fun combinations(spec: Action<in DimensionsSpec>) = DimensionsSpec().run {
        spec.execute(this)
        dimensions.values.map { it.variants.entries.toList() }.combinations().forEach { combination ->
            val variantNames = combination.map { s ->
                "${s.value.dimensionName}-${s.key}"
            }
            if (excludedVariantSpecs.any { it(variantNames) }) {
                return@forEach
            }
            val path = variantNames.joinToString("/")
            if (!excludedVariants.contains(path)) {
                buildVariant(path, combination.map { it.value }, variantNames)
            }
        }
    }

    private fun buildVariant(path: String, variantSpecs: List<VariantSpec>, variantNames: List<String>) {
        val projectPath = ":test-case:${path.replace('/', ':')}"
        settings.include(projectPath)
        val projectSegments = projectPath.split(':').drop(1)
        projectSegments.indices.map { index ->
            ":${projectSegments.take(index + 1).joinToString(":")}"
        }.dropLast(1).forEach { syntheticProjectPath ->
            val syntheticProjectDir = File(settings.rootDir, ".gradle/synthetic-projects/${syntheticProjectPath.drop(1).replace(':', '/')}")
            syntheticProjectDir.mkdirs()
            settings.project(syntheticProjectPath).projectDir = syntheticProjectDir
        }
        settings.project(projectPath).projectDir = File(settings.rootDir, "test-case-common")
        gradle.beforeProject {
            if (this.path == projectPath) {
                layout.buildDirectory.set(projectDir.resolve("build/${path}"))
                project.plugins.withId("java") {
                    project.extensions.findByType(JavaPluginExtension::class.java)?.let { java ->
                        variantNames.forEach { variantName ->
                            java.sourceSets.all {
                                this.java.srcDir("src/$name/variants/$variantName/java")
                                this.resources.srcDir("src/$name/variants/$variantName/resources")
                            }
                        }
                    }
                }
            }
        }
        gradle.afterProject {
            if (this.path == projectPath) {
                variantSpecs.forEach {
                    val pluginId = "io.micronaut.testcase.${it.dimensionName}.${it.name}"
                    val plugin = File(settings.settingsDir, "build-logic/src/main/kotlin/$pluginId.gradle.kts")
                    if (plugin.exists()) {
                        plugins.apply(pluginId)
                    }
                }
            }
        }
    }

    class DimensionsSpec {
        val dimensions = mutableMapOf<String, DimensionSpec>()
        val excludedVariants = mutableSetOf<String>()
        val excludedVariantSpecs = mutableListOf<(List<String>) -> Boolean>()

        fun dimension(dimensionName: String, dimensionSpec: Action<in DimensionSpec>) {
            dimensions.put(dimensionName, DimensionSpec(dimensionName).apply { dimensionSpec.execute(this) })
        }

        fun exclude(variantName: String) {
            excludedVariants.add(variantName)
        }

        fun exclude(predicate: (List<String>) -> Boolean) {
            excludedVariantSpecs.add(predicate)
        }
    }

    class DimensionSpec(val name: String) {
        val variants = mutableMapOf<String, VariantSpec>()

        fun variant(variantName: String) {
            variants.put(variantName, VariantSpec(name, variantName))
        }
    }


    class VariantSpec(val dimensionName: String, val name: String)
}

inline fun <reified T> List<List<T>>.combinations(): List<List<T>> {
    val result = mutableListOf(mutableListOf<T>())
    for (list in this) {
        val temp = mutableListOf<MutableList<T>>()
        for (item in list) {
            for (combination in result) {
                temp.add(mutableListOf(*combination.toTypedArray(), item))
            }
        }
        result.clear()
        result.addAll(temp)
    }
    return result
}
