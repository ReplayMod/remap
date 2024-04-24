package com.replaymod.gradle.remap

import com.replaymod.gradle.remap.legacy.LegacyMapping
import org.cadixdev.lorenz.MappingSet
import org.jetbrains.kotlin.cli.common.CLIConfigurationKeys
import org.jetbrains.kotlin.cli.common.config.ContentRoot
import org.jetbrains.kotlin.cli.common.messages.MessageCollector
import org.jetbrains.kotlin.cli.common.messages.MessageRenderer
import org.jetbrains.kotlin.cli.common.messages.PrintingMessageCollector
import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.cli.jvm.config.JavaSourceRoot
import org.jetbrains.kotlin.cli.jvm.config.JvmClasspathRoot
import org.jetbrains.kotlin.cli.jvm.modules.CoreJrtFileSystem
import org.jetbrains.kotlin.com.intellij.codeInsight.CustomExceptionHandler
import org.jetbrains.kotlin.com.intellij.mock.MockProject
import org.jetbrains.kotlin.com.intellij.openapi.Disposable
import org.jetbrains.kotlin.com.intellij.openapi.extensions.ExtensionPoint
import org.jetbrains.kotlin.com.intellij.openapi.extensions.Extensions
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.openapi.vfs.StandardFileSystems
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFileManager
import org.jetbrains.kotlin.com.intellij.openapi.vfs.local.CoreLocalFileSystem
import org.jetbrains.kotlin.com.intellij.psi.PsiManager
import org.jetbrains.kotlin.config.CommonConfigurationKeys
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.config.JVMConfigurationKeys
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.utils.PathUtil
import java.io.BufferedReader
import java.io.File
import java.io.IOException
import java.io.InputStreamReader
import java.lang.Exception
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*
import kotlin.system.exitProcess

class Transformer(private val map: MappingSet) {
    var classpath: Array<String>? = null
    var remappedClasspath: Array<String>? = null
    var jdkHome: File? = null
    var remappedJdkHome: File? = null
    var patternAnnotation: String? = null
    var manageImports = false

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> =
            remap(sources, emptyMap())

    @Throws(IOException::class)
    fun remap(sources: Map<String, String>, processedSources: Map<String, String>): Map<String, Pair<String, List<Pair<Int, String>>>> {
        val tmpDir = Files.createTempDirectory("remap")
        val processedTmpDir = Files.createTempDirectory("remap-processed")
        val disposable = Disposer.newDisposable()
        try {
            for ((unitName, source) in sources) {
                val path = tmpDir.resolve(unitName)
                Files.createDirectories(path.parent)
                Files.write(path, source.toByteArray(StandardCharsets.UTF_8), StandardOpenOption.CREATE)

                val processedSource = processedSources[unitName] ?: source
                val processedPath = processedTmpDir.resolve(unitName)
                Files.createDirectories(processedPath.parent)
                Files.write(processedPath, processedSource.toByteArray(), StandardOpenOption.CREATE)
            }

            val config = CompilerConfiguration()
            config.put(CommonConfigurationKeys.MODULE_NAME, "main")
            jdkHome?.let {config.setupJdk(it) }
            config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(tmpDir.toFile(), ""))
            val kotlinSourceRoot = try {
                kotlinSourceRoot1521(tmpDir.toAbsolutePath().toString(), false)
            } catch (e: NoSuchMethodError) {
                kotlinSourceRoot190(tmpDir.toAbsolutePath().toString(), false)
            }
            config.add<ContentRoot>(CLIConfigurationKeys.CONTENT_ROOTS, kotlinSourceRoot)
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

            val analysis = try {
                analyze1521(environment, ktFiles)
            } catch (e: NoSuchMethodError) {
                analyze1620(environment, ktFiles)
            }

            val remappedEnv = remappedClasspath?.let {
                setupRemappedProject(disposable, it, processedTmpDir)
            }

            val patterns = patternAnnotation?.let { annotationFQN ->
                val patterns = PsiPatterns(annotationFQN)
                val annotationName = annotationFQN.substring(annotationFQN.lastIndexOf('.') + 1)
                for ((unitName, source) in sources) {
                    if (!source.contains(annotationName)) continue
                    try {
                        val patternFile = vfs.findFileByIoFile(tmpDir.resolve(unitName).toFile())!!
                        val patternPsiFile = psiManager.findFile(patternFile)!!
                        patterns.read(patternPsiFile, processedSources[unitName]!!)
                    } catch (e: Exception) {
                        throw RuntimeException("Failed to read patterns from file \"$unitName\".", e)
                    }
                }
                patterns
            }

            val autoImports = if (manageImports && remappedEnv != null) {
                AutoImports(remappedEnv)
            } else {
                null
            }

            val results = HashMap<String, Pair<String, List<Pair<Int, String>>>>()
            for (name in sources.keys) {
                val file = vfs.findFileByIoFile(tmpDir.resolve(name).toFile())!!
                val psiFile = psiManager.findFile(file)!!

                var (text, errors) = try {
                    PsiMapper(map, remappedEnv?.project, psiFile, analysis.bindingContext, patterns).remapFile()
                } catch (e: Exception) {
                    throw RuntimeException("Failed to map file \"$name\".", e)
                }

                if (autoImports != null && "/* remap: no-manage-imports */" !in text) {
                    val processedText = processedSources[name] ?: text
                    text = autoImports.apply(psiFile, text, processedText)
                }

                results[name] = text to errors
            }
            return results
        } finally {
            Files.walk(tmpDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            Files.walk(processedTmpDir).sorted(Comparator.reverseOrder()).forEach { Files.delete(it) }
            Disposer.dispose(disposable)
        }
    }

    private fun CompilerConfiguration.setupJdk(jdkHome: File) {
        put(JVMConfigurationKeys.JDK_HOME, jdkHome)

        if (!CoreJrtFileSystem.isModularJdk(jdkHome)) {
            val roots = PathUtil.getJdkClassesRoots(jdkHome).map { JvmClasspathRoot(it, true) }
            addAll(CLIConfigurationKeys.CONTENT_ROOTS, 0, roots)
        }
    }

    private fun setupRemappedProject(disposable: Disposable, classpath: Array<String>, sourceRoot: Path): KotlinCoreEnvironment {
        val config = CompilerConfiguration()
        (remappedJdkHome ?: jdkHome)?.let { config.setupJdk(it) }
        config.put(CommonConfigurationKeys.MODULE_NAME, "main")
        config.addAll(CLIConfigurationKeys.CONTENT_ROOTS, classpath.map { JvmClasspathRoot(File(it)) })
        if (manageImports) {
            config.add(CLIConfigurationKeys.CONTENT_ROOTS, JavaSourceRoot(sourceRoot.toFile(), ""))
        }
        config.put(CLIConfigurationKeys.MESSAGE_COLLECTOR_KEY, PrintingMessageCollector(System.err, MessageRenderer.GRADLE_STYLE, true))

        val environment = KotlinCoreEnvironment.createForProduction(
            disposable,
            config,
            EnvironmentConfigFiles.JVM_CONFIG_FILES
        )
        try {
            analyze1521(environment, emptyList())
        } catch (e: NoSuchMethodError) {
            analyze1620(environment, emptyList())
        }
        return environment
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
