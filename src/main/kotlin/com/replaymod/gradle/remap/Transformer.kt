package com.replaymod.gradle.remap

import com.intellij.codeInsight.CustomExceptionHandler
import com.intellij.mock.MockProject
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.extensions.Extensions
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.local.CoreLocalFileSystem
import com.intellij.psi.PsiManager
import com.intellij.psi.search.GlobalSearchScope
import com.replaymod.gradle.remap.legacy.LegacyMapping
import org.cadixdev.lorenz.MappingSet
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.cli.common.config.KotlinSourceRoot
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.compiler.NoScopeRecordCliBindingTrace
import org.jetbrains.kotlin.cli.jvm.compiler.TopDownAnalyzerFacadeForJVM
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.system.exitProcess

class Transformer(private val map: MappingSet) {
    var classpath: Array<String>? = null
    private var fail: Boolean = false

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>): Map<String, String> {
        val tmpDir = Files.createTempDirectory("remap")
        try {
            for ((unitName, source) in sources) {
                val path = tmpDir.resolve(unitName)
                Files.createDirectories(path.parent)
                Files.write(path, source.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE)
            }

            val config = CompilerConfiguration()
            config.put(CommonConfigurationKeys.MODULE_NAME, "main")
            config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(tmpDir.toFile(), ""))
            config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, KotlinSourceRoot(tmpDir.toAbsolutePath().toString(), false))
            config.put<MessageCollector>(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, true))

            val environment = KotlinCoreEnvironment.createForProduction(
                    Disposer.newDisposable(),
                    config,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            val rootArea = Extensions.getRootArea()
            if (!rootArea.hasExtensionPoint(CustomExceptionHandler.KEY)) {
                rootArea.registerExtensionPoint(CustomExceptionHandler.KEY.name, CustomExceptionHandler::class.java.name, ExtensionPoint.Kind.INTERFACE)
            }

            val project = environment.project as MockProject

            environment.updateClasspath(classpath!!.map { JvmClasspathRoot(File(it)) })

            TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                    project,
                    emptyList(),
                    NoScopeRecordCliBindingTrace(),
                    environment.configuration,
                    { scope: GlobalSearchScope -> environment.createPackagePartProvider(scope) }
            )

            val vfs = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL) as CoreLocalFileSystem
            val results = HashMap<String, String>()
            for (name in sources.keys) {
                val file = vfs.findFileByIoFile(tmpDir.resolve(name).toFile())!!
                val psiFile = PsiManager.getInstance(project).findFile(file)!!

                val mapped = PsiMapper(map, psiFile).remapFile()
                if (mapped == null) {
                    fail = true
                    continue
                }
                results[name] = mapped
            }
            return results
        } finally {
            Files.walk(tmpDir).map<File> { it.toFile() }.sorted(Comparator.reverseOrder()).forEach { it.delete() }
        }
    }

    companion object {

        @Throws(IOException::class)
        @JvmStatic
        fun main(args: Array<String>) {
            val mappings: MappingSet = if (args[0].isEmpty()) {
                MappingSet.create()
            } else {
                LegacyMapping.readMappingSet(File(args[0]).toPath(), args[1] == "true")
            }
            val transformer = Transformer(mappings)

            val reader = BufferedReader(InputStreamReader(System.`in`))

            transformer.classpath = (1..Integer.parseInt(args[2])).map { reader.readLine() }.toTypedArray()

            val sources = mutableMapOf<String, String>()
            while (true) {
                val name = reader.readLine()
                if (name == null || name.isEmpty()) {
                    break
                }

                val lines = arrayOfNulls<String>(Integer.parseInt(reader.readLine()))
                for (i in lines.indices) {
                    lines[i] = reader.readLine()
                }
                val source = lines.joinToString("\n")

                sources[name] = source
            }

            val results = transformer.remap(sources)

            for (name in sources.keys) {
                println(name)
                val lines = results.getValue(name).split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
                println(lines.size)
                for (line in lines) {
                    println(line)
                }
            }

            if (transformer.fail) {
                exitProcess(1)
            }
        }
    }

}
