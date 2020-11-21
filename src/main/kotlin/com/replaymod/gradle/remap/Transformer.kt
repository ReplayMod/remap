package com.replaymod.gradle.remap

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
import org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.lang.Exception
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.system.exitProcess

class Transformer(private val map: MappingSet) {
    var classpath: Array<String>? = null

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        val tmpDir = Files.createTempDirectory("remap")
        val disposable = Disposer.newDisposable()
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
            config.addAll<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, classpath!!.map { JvmClasspathRoot(File(it)) })
            config.put<MessageCollector>(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, true))

            // Our PsiMapper only works with the PSI tree elements, not with the faster (but kotlin-specific) classes
            config.put(JVMConfigurationKeys.USE_PSI_CLASS_FILES_READING, true)

            val environment = KotlinCoreEnvironment.createForProduction(
                    disposable,
                    config,
                    EnvironmentConfigFiles.JVM_CONFIG_FILES
            )
            val rootArea = Extensions.getRootArea()
            synchronized(rootArea) {
                if (!rootArea.hasExtensionPoint(CustomExceptionHandler.KEY)) {
                    rootArea.registerExtensionPoint(CustomExceptionHandler.KEY.name, CustomExceptionHandler::class.java.name, ExtensionPoint.Kind.INTERFACE)
                }
            }

            val project = environment.project as MockProject
            val psiManager = PsiManager.getInstance(project)
            val vfs = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL) as CoreLocalFileSystem
            val virtualFiles = sources.mapValues { vfs.findFileByIoFile(tmpDir.resolve(it.key).toFile())!! }
            val psiFiles = virtualFiles.mapValues { psiManager.findFile(it.value)!! }
            val ktFiles = psiFiles.values.filterIsInstance<KtFile>()

            val analysis = TopDownAnalyzerFacadeForJVM.analyzeFilesWithJavaIntegration(
                    project,
                    ktFiles,
                    NoScopeRecordCliBindingTrace(),
                    environment.configuration,
                    { scope: GlobalSearchScope -> environment.createPackagePartProvider(scope) }
            )

            val results = HashMap<String, Pair<String, List<Pair<Int, String>>>>()
            for (name in sources.keys) {
                val file = vfs.findFileByIoFile(tmpDir.resolve(name).toFile())!!
                val psiFile = psiManager.findFile(file)!!

                val mapped = try {
                    PsiMapper(map, psiFile).remapFile(analysis.bindingContext)
                } catch (e: Exception) {
                    throw RuntimeException("Failed to map file \"$name\".", e)
                }
                results[name] = mapped
            }
            return results
        } finally {
            Files.walk(tmpDir).map<File> { it.toFile() }.sorted(Comparator.reverseOrder()).forEach { it.delete() }
            Disposer.dispose(disposable)
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
                val lines = results.getValue(name).first.split("\n").dropLastWhile { it.isEmpty() }.toTypedArray()
                println(lines.size)
                for (line in lines) {
                    println(line)
                }
            }

            if (results.any { it.value.second.isNotEmpty() }) {
                exitProcess(1)
            }
        }
    }

}
