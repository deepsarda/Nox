package nox.ksp

import com.google.devtools.ksp.processing.*
import com.google.devtools.ksp.symbol.*
import java.util.UUID

class NoxPluginProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor {
        return NoxPluginProcessor(environment.codeGenerator, environment.logger)
    }
}

class NoxPluginProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger
) : SymbolProcessor {

    private var invoked = false

    override fun process(resolver: Resolver): List<KSAnnotated> {
        if (invoked) return emptyList()
        invoked = true

        val symbols = resolver.getSymbolsWithAnnotation("nox.plugin.annotations.NoxModule")
        val classes = symbols.filterIsInstance<KSClassDeclaration>().toList()

        if (classes.isEmpty()) return emptyList()

        val isTest = classes.any { it.containingFile?.filePath?.contains("/src/test/") == true }
        val className = if (isTest) "GeneratedRegistryTest" else "GeneratedRegistry"
        val packageName = "nox.plugin"
        val fullClassName = "$packageName.$className"

        val file = codeGenerator.createNewFile(
            dependencies = Dependencies(false, *classes.mapNotNull { it.containingFile }.toTypedArray()),
            packageName = packageName,
            fileName = className
        )

        file.writer().use { writer ->
            writer.write("package $packageName\n\n")
            writer.write("class $className : PluginRegistryProvider {\n")
            writer.write("    override fun registerAll(registry: LibraryRegistry) {\n")

            for (cls in classes) {
                val fqcn = cls.qualifiedName?.asString() ?: continue
                
                if (cls.classKind == ClassKind.OBJECT) {
                    writer.write("        registry.registerModule($fqcn)\n")
                } else {
                    writer.write("        registry.registerModule(${fqcn}())\n")
                }
            }

            writer.write("    }\n")
            writer.write("}\n")
        }
        
        val reflectFile = codeGenerator.createNewFile(
            dependencies = Dependencies(false, *classes.mapNotNull { it.containingFile }.toTypedArray()),
            packageName = "META-INF.native-image.nox",
            fileName = "reflect-config",
            extensionName = "json"
        )
        
        reflectFile.writer().use { writer ->
            writer.write("[\n")
            
            // Add GeneratedRegistry / GeneratedRegistryTest
            writer.write("  {\n")
            writer.write("    \"name\": \"$fullClassName\",\n")
            writer.write("    \"methods\": [{\"name\": \"<init>\", \"parameterTypes\": []}]\n")
            writer.write("  }")
            
            for (cls in classes) {
                val fqcn = cls.qualifiedName?.asString() ?: continue
                writer.write(",\n  {\n")
                writer.write("    \"name\": \"$fqcn\",\n")
                writer.write("    \"allDeclaredConstructors\": true,\n")
                writer.write("    \"allDeclaredMethods\": true,\n")
                writer.write("    \"allDeclaredFields\": true\n")
                writer.write("  }")
            }
            writer.write("\n]\n")
        }

        return emptyList()
    }
}
