package com.replaymod.gradle.remap.util

import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.io.MappingFormats
import java.nio.file.Path

fun Path.readMappings(): MappingSet {
    val name = fileName.toString()
    val ext = name.substring(name.lastIndexOf(".") + 1)
    val format = MappingFormats.REGISTRY.values().find { it.standardFileExtension.orElse(null) == ext }
        ?: throw UnsupportedOperationException("Cannot find mapping format for $this")
    return format.read(this)
}
