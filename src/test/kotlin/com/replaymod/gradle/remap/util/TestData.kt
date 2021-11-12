package com.replaymod.gradle.remap.util

import com.replaymod.gradle.remap.Transformer
import com.replaymod.gradle.remap.legacy.LegacyMappingSetModelFactory
import org.cadixdev.lorenz.MappingSet
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
        classpath = arrayOf(
            findClasspathEntry("org.spongepowered.asm.mixin.Mixin"),
            findClasspathEntry("a.pkg.A"),
        )
        remappedClasspath = arrayOf(
            findClasspathEntry("org.spongepowered.asm.mixin.Mixin"),
            findClasspathEntry("b.pkg.B"),
        )
        patternAnnotation = "remap.Pattern"
    }

    fun remap(content: String, patternsBefore: String = "", patternsAfter: String = ""): String = transformer.remap(mapOf(
        "test.java" to content,
        "pattern.java" to "class Patterns {\n$patternsBefore\n}",
    ), mapOf(
        "pattern.java" to "class Patterns {\n$patternsAfter\n}",
    ))["test.java"]!!.first
    fun remapWithErrors(content: String) = transformer.remap(mapOf("test.java" to content))["test.java"]!!

    fun remapKt(content: String): String = transformer.remap(mapOf("test.kt" to content))["test.kt"]!!.first
    fun remapKtWithErrors(content: String) = transformer.remap(mapOf("test.kt" to content))["test.kt"]!!
}