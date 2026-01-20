package com.replaymod.gradle.remap.util

import com.replaymod.gradle.remap.Transformer
import com.replaymod.gradle.remap.legacy.LegacyMappingSetModelFactory
import org.cadixdev.lorenz.MappingSet
import java.io.File
import java.net.URL
import java.nio.file.Paths
import kotlin.io.path.absolutePathString

object TestData {
    private val mappingsPath = Paths.get(javaClass.getResource("/mappings.srg")!!.toURI())
    private val mappings = mappingsPath.readMappings().let { mappings ->
        val legacyCopy = MappingSet.create(LegacyMappingSetModelFactory())
        mappings.topLevelClassMappings.forEach { it.copy(legacyCopy) }
        legacyCopy
    }

    val transformer = Transformer(mappings).apply {
        fun findClasspathEntry(cls: String): String {
            val classFilePath = "/${cls.replace('.', '/')}.class"
            val url = javaClass.getResource(classFilePath)
                ?: throw RuntimeException("Failed to find $cls on classpath.")

            return when {
                url.protocol == "jar" && url.file.endsWith("!$classFilePath") -> {
                    Paths.get(URL(url.file.removeSuffix("!$classFilePath")).toURI()).absolutePathString()
                }
                url.protocol == "file" && url.file.endsWith(classFilePath) -> {
                    var path = Paths.get(url.toURI())
                    repeat(cls.count { it == '.' } + 1) {
                        path = path.parent
                    }
                    path.absolutePathString()
                }
                else -> {
                    throw RuntimeException("Do not know how to turn $url into classpath entry.")
                }
            }
        }
        jdkHome = File(System.getProperty("java.home"))
        classpath = arrayOf(
            findClasspathEntry("org.spongepowered.asm.mixin.Mixin"),
            findClasspathEntry("a.pkg.A"),
            findClasspathEntry("AMarkerKt"),
        )
        remappedClasspath = arrayOf(
            findClasspathEntry("org.spongepowered.asm.mixin.Mixin"),
            findClasspathEntry("b.pkg.B"),
            findClasspathEntry("BMarkerKt"),
        )
        patternAnnotation = "remap.Pattern"
        manageImports = true
    }

    private fun Map<String, Pair<String, List<Pair<Int, String>>>>.expectNoErrors(): Map<String, String> {
        return mapValues { (file, result) ->
            val (content, errors) = result
            if (errors.isNotEmpty()) {
                throw AssertionError("Remapping produced errors:\n" +
                        errors.joinToString("\n") { (line, message) ->
                            "$file:${line + 1}: $message"
                        })
            }
            content
        }
    }

    fun remap(content: String): String =
        remap("test.java", content)
    fun remap(fileName: String, content: String): String =
        remap(fileName, content, "", "")
    fun remap(content: String, patternsBefore: String, patternsAfter: String): String =
        remap("test.java", content, patternsBefore, patternsAfter)
    fun remap(fileName: String, content: String, patternsBefore: String, patternsAfter: String): String = transformer.remap(mapOf(
        fileName to content,
        "pattern.java" to "class Patterns {\n$patternsBefore\n}",
    ), mapOf(
        "pattern.java" to "class Patterns {\n$patternsAfter\n}",
    )).expectNoErrors()[fileName]!!
    fun remapWithErrors(content: String) = transformer.remap(mapOf("test.java" to content))["test.java"]!!

    fun remapKt(content: String): String = transformer.remap(mapOf("test.kt" to content)).expectNoErrors()["test.kt"]!!
    fun remapKtWithErrors(content: String) = transformer.remap(mapOf("test.kt" to content))["test.kt"]!!
}