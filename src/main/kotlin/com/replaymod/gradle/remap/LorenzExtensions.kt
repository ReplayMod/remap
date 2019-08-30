package com.replaymod.gradle.remap

import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.FieldMapping
import org.cadixdev.lorenz.model.MethodMapping

fun MappingSet.findClassMapping(obfuscatedName: String): ClassMapping<*, *>? = getClassMapping(obfuscatedName).orElse(null)
fun ClassMapping<*, *>.findFieldMapping(obfuscatedName: String): FieldMapping? = getFieldMapping(obfuscatedName).orElse(null)
fun ClassMapping<*, *>.findMethodMapping(signature: MethodSignature): MethodMapping? = getMethodMapping(signature).orElse(null)
