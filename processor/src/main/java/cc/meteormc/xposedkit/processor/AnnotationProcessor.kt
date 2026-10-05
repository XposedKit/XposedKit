package cc.meteormc.xposedkit.processor

import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSClassDeclaration
import java.io.File
import java.util.Properties

class AnnotationProcessor(
    private val generator: CodeGenerator,
    private val logger: KSPLogger,
    private val options: Map<String, String>
) : SymbolProcessor {
    companion object {
        private const val SCOPES_RESOURCE_NAME = "module_scopes"

        private const val METADATA_OUTPUT_OPTION = "metadataOutput"

        private const val ACTIVITY_CLASS_NAME = "android.app.Activity"
        private const val XPOSED_MODULE_CLASS_NAME = "cc.meteormc.xposedkit.XposedModule"
        private const val MODULE_REGISTER_CLASS_NAME = "cc.meteormc.xposedkit.annotation.ModuleRegister"
        private const val MODULE_SETTINGS_ACTIVITY_CLASS_NAME = "cc.meteormc.xposedkit.annotation.ModuleSettingsActivity"
    }

    private val moduleClasses = mutableListOf<KSClassDeclaration>()
    private val settingsActivityClasses = mutableListOf<KSClassDeclaration>()

    override fun process(resolver: Resolver): List<KSAnnotated> {
        moduleClasses.addAll(
            resolver.getSymbolsWithAnnotation(MODULE_REGISTER_CLASS_NAME).filterIsInstance<KSClassDeclaration>()
        )
        settingsActivityClasses.addAll(
            resolver.getSymbolsWithAnnotation(MODULE_SETTINGS_ACTIVITY_CLASS_NAME).filterIsInstance<KSClassDeclaration>()
        )

        return emptyList()
    }

    override fun finish() {
        val metadataOutput = options[METADATA_OUTPUT_OPTION]?.let { File(it) } ?: return
        metadataOutput.parentFile.mkdirs()

        val metadata = Properties()
        processModuleAnnotation(metadata)
        processSettingsActivityAnnotation(metadata)
        metadataOutput.bufferedWriter().use {
            metadata.store(it, null)
        }
    }

    private fun processModuleAnnotation(metadata: Properties) {
        logger.info("processModuleAnnotation: $moduleClasses")

        val iterator = moduleClasses.iterator()
        while (iterator.hasNext()) {
            val clazz = iterator.next()
            if (clazz.isSubclassOf(XPOSED_MODULE_CLASS_NAME)) continue

            val className = clazz.qualifiedName?.asString()
            logger.warn("Class $className is annotated with @ModuleRegister but does not implement $XPOSED_MODULE_CLASS_NAME, ignoring it")
            iterator.remove()
        }

        if (moduleClasses.isEmpty()) {
            throw NoSuchElementException("No @ModuleRegister annotation found! Please annotate your module class with @ModuleRegister")
        }

        if (moduleClasses.size > 1) {
            throw IllegalArgumentException("Multiple @ModuleRegister annotations found! Please ensure that only one class is annotated with @ModuleRegister")
        }

        val moduleClass = moduleClasses.single()
        val dependencies = Dependencies(true, moduleClass.containingFile!!)

        generator.createNewFileByPath(
            dependencies,
            "META-INF/services/$XPOSED_MODULE_CLASS_NAME",
            ""
        ).writer().use {
            it.write(moduleClass.qualifiedName!!.asString())
        }

        metadata.putAll(
            moduleClass.annotations.first {
                it.annotationType.resolve().declaration.qualifiedName?.asString() == MODULE_REGISTER_CLASS_NAME
            }.arguments.associate {
                it.name!!.asString() to it.value.toString()
            }
        )
    }

    private fun processSettingsActivityAnnotation(metadata: Properties) {
        logger.info("processSettingsActivityAnnotation: $settingsActivityClasses")

        val settingsActivity = settingsActivityClasses.firstOrNull { it.isSubclassOf(ACTIVITY_CLASS_NAME) } ?: return
        metadata["settingsActivity"] = settingsActivity.qualifiedName!!.asString()
    }

    fun KSClassDeclaration.isSubclassOf(
        target: String,
        visited: MutableSet<String> = mutableSetOf()
    ): Boolean {
        val currentName = qualifiedName?.asString() ?: return false
        if (!visited.add(currentName)) return false
        return superTypes.any { superType ->
            val parent = superType.resolve()
                .declaration as? KSClassDeclaration
                ?: return@any false
            val parentName = parent.qualifiedName?.asString()
            parentName == target || parent.isSubclassOf(target, visited)
        }
    }
}