package cc.meteormc.xposedkit.plugin

import cc.meteormc.xposedkit.plugin.task.GenerateManifestTask
import cc.meteormc.xposedkit.plugin.task.GenerateResourcesTask
import com.android.build.api.variant.AndroidComponentsExtension
import com.google.devtools.ksp.gradle.KspExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.kotlin.dsl.register

class XposedKitPlugin : Plugin<Project> {
    companion object {
        private const val KSP_PLUGIN_ID = "com.google.devtools.ksp"
    }

    override fun apply(target: Project) {
        val buildDir = target.layout.buildDirectory

        val plugins = target.pluginManager
        plugins.apply(KSP_PLUGIN_ID)

        val dependencies = target.dependencies
        dependencies.add("implementation", "cc.meteormc:core:1.0.0")
        dependencies.add("ksp", "cc.meteormc:xposedkit-processor:1.0.0")

        val tasks = target.tasks
        val generatedDir = buildDir.dir("generated/xposedkit").get()
        val metadataFile = generatedDir.file("metadata.properties")

        val ksp = target.extensions.findByType(KspExtension::class.java)
            ?: throw IllegalStateException("KSP extension not found!")
        ksp.arg(
            "metadataOutput",
            metadataFile.asFile.absolutePath
        )

        val androidComponents = target.extensions.findByType(AndroidComponentsExtension::class.java)
            ?: throw IllegalStateException("Android components extension not found!")
        androidComponents.onVariants { variant ->
            val sources = variant.sources
            val variantName = variant.name
            val capitalizeName = variantName.replaceFirstChar(Char::titlecaseChar)
            val variantDir = generatedDir.dir(variantName)

            val allManifests = sources.manifests.all.get()
            val manifestOutput = variantDir.file("AndroidManifest.xml")
            sources.manifests.addGeneratedManifestFile(
                tasks.register<GenerateManifestTask>("generateXposedKit${capitalizeName}Manifest") {
                    dependsOn("ksp${capitalizeName}Kotlin")
                    metadataInput.set(metadataFile)
                    this.output.set(manifestOutput)
                    this.allManifests.set(allManifests)
                },
                GenerateManifestTask::output
            )

            val staticRes = sources.res!!.static.get().flatten()
            val resourcesOutput = variantDir.dir("resources")
            sources.resources!!.addGeneratedSourceDirectory(
                tasks.register<GenerateResourcesTask>("generateXposedKit${capitalizeName}Resources") {
                    dependsOn("ksp${capitalizeName}Kotlin")
                    metadataInput.set(metadataFile)
                    this.staticRes.set(staticRes)
                    this.output.set(resourcesOutput)
                },
                GenerateResourcesTask::output
            )
        }
    }
}