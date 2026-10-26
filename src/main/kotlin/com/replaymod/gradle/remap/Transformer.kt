package com.replaymod.gradle.remap

import com.intellij.codeInsight.CustomExceptionHandler
import com.intellij.openapi.Disposable
import com.intellij.openapi.extensions.ExtensionPoint
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.registry.Registry
import com.intellij.openapi.vfs.StandardFileSystems
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.openapi.vfs.local.CoreLocalFileSystem
import com.intellij.psi.PsiClass
import com.intellij.psi.PsiManager
import net.fabricmc.mappingio.tree.MappingTreeView
import org.jetbrains.kotlin.analysis.api.session.analyze
import org.jetbrains.kotlin.analysis.api.standalone.StandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.api.standalone.buildStandaloneAnalysisAPISession
import org.jetbrains.kotlin.analysis.decompiled.light.classes.ClsJavaStubByVirtualFileCache
import org.jetbrains.kotlin.analysis.decompiled.light.classes.DecompiledLightClassesFactory
import org.jetbrains.kotlin.analysis.decompiler.psi.file.KtClsFile
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtLibraryModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSdkModule
import org.jetbrains.kotlin.analysis.project.structure.builder.buildKtSourceModule
import org.jetbrains.kotlin.platform.jvm.JvmPlatforms
import org.jetbrains.kotlin.psi.KtClassOrObject
import org.jetbrains.kotlin.psi.KtFile
import org.jetbrains.kotlin.psi.KtFileClassProvider
import java.io.File
import java.io.IOException
import java.lang.Exception
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import java.util.*

class Transformer(
    private val mappingTree: MappingTreeView,
) {
    var classpath: Array<String>? = null
    var remappedClasspath: Array<String>? = null
    var jdkHome: File? = null
    var remappedJdkHome: File? = null
    var patternAnnotation: String? = null
    var manageImports = false
    var verboseCompilerMessages = false

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

            val session = createSession(disposable, tmpDir, classpath, jdkHome?.toPath())
            val remappedSession = remappedClasspath?.let {
                createSession(disposable, if (manageImports) processedTmpDir else null, it, (remappedJdkHome ?: jdkHome)?.toPath())
            }

            // FIXME these shouldn't be needed? `intellij.java.psi.impl.xml` declares them
            Registry.get("unsound.capture.conversion.java.spec.change").setValue(false)
            Registry.get("javac.fresh.variables.for.captured.wildcards.only").setValue(true)
            val extensionArea = session.application.extensionArea
            synchronized(extensionArea) {
                if (!extensionArea.hasExtensionPoint(CustomExceptionHandler.KEY)) {
                    extensionArea.registerExtensionPoint(CustomExceptionHandler.KEY.name, CustomExceptionHandler::class.java.name, ExtensionPoint.Kind.INTERFACE, true)
                }
            }

            val psiManager = PsiManager.getInstance(session.project)
            val vfs = VirtualFileManager.getInstance().getFileSystem(StandardFileSystems.FILE_PROTOCOL) as CoreLocalFileSystem

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

            val autoImports = if (manageImports && remappedSession != null) {
                val shortNameIndex = ShortNameIndex(
                    remappedSession.project,
                    remappedJdkHome ?: jdkHome,
                    remappedClasspath!!.map { File(it) },
                    listOf(processedTmpDir),
                )
                AutoImports(remappedSession.project, shortNameIndex)
            } else {
                null
            }

            val results = HashMap<String, Pair<String, List<Pair<Int, String>>>>()
            for (name in sources.keys) {
                val file = vfs.findFileByIoFile(tmpDir.resolve(name).toFile())!!
                val psiFile = psiManager.findFile(file)!!

                var (text, errors) = try {
                    analyze(psiFile) {
                        val changes = Changes(psiFile)
                        if (patterns != null) {
                            applyPatterns(psiFile, changes, patterns)
                        }
                        PsiMapper(mappingTree, remappedSession?.project, psiFile, changes).remapFile()
                        changes.getResult()
                    }
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

    private fun createSession(
        disposable: Disposable,
        srcRoot: Path?,
        classpath: Array<String>?,
        jdkHome: Path?,
    ): StandaloneAnalysisAPISession {
        val platform = JvmPlatforms.defaultJvmPlatform
        return buildStandaloneAnalysisAPISession(disposable) {
            buildKtModuleProvider {
                this.platform = platform
                addModule(buildKtSourceModule {
                    moduleName = "main"
                    this.platform = platform
                    if (srcRoot != null) {
                        addSourceRoot(srcRoot)
                    }
                    addRegularDependency(addModule(buildKtLibraryModule {
                        libraryName = "libs"
                        this.platform = platform
                        addBinaryRoots(classpath?.map { Path.of(it) } ?: emptyList())
                    }))
                    if (jdkHome != null) {
                        addRegularDependency(addModule(buildKtSdkModule {
                            libraryName = "jdk"
                            this.platform = platform
                            addBinaryRootsFromJdkHome(jdkHome, false)
                        }))
                    }
                })
            }

            // Workaround for https://youtrack.jetbrains.com/issue/KT-89902/Analysis-API-Standalone-JavaPsiFacade-doesnt-find-Kotlin-classes-from-binary-libraries
            // from https://github.com/JetBrains/kotlin/blob/87055674c0a4a08ebce11f15b11e30e9bcb74320/analysis/analysis-api-impl-base/testFixtures/org/jetbrains/kotlin/analysis/api/impl/base/test/configurators/AnalysisApiBaseTestServiceRegistrar.kt#L77
            registerProjectService(KtFileClassProvider::class.java, object : KtFileClassProvider {
                override fun getFileClasses(file: KtFile): Array<PsiClass> {
                    val virtualFile = file.virtualFile
                    val classOrObject = file.declarations.filterIsInstance<KtClassOrObject>().singleOrNull()
                    if (file is KtClsFile && virtualFile != null) {
                        DecompiledLightClassesFactory.createClsJavaClassFromVirtualFile(file, virtualFile, classOrObject, project)?.let {
                            return arrayOf(it)
                        }
                    }
                    return PsiClass.EMPTY_ARRAY
                }
            })
            registerProjectService(ClsJavaStubByVirtualFileCache::class.java, ClsJavaStubByVirtualFileCache())
        }
    }
}
