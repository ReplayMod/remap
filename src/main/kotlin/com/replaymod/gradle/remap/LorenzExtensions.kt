package com.replaymod.gradle.remap

import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.FieldMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

fun ClassMapping<*, *>.findFieldMapping(obfuscatedName: String): FieldMapping? = getFieldMapping(obfuscatedName).orElse(null)
fun ClassMapping<*, *>.findMethodMapping(signature: MethodSignature): MethodMapping? = getMethodMapping(signature).orElse(null)

fun MappingSet.findClassMapping(obfuscatedClassId: ClassId): ClassMapping<*, *>? =
    getClassMapping(obfuscatedClassId.dollarQualifiedName).orElse(null)

val ClassMapping<*, *>.deobfuscatedClassId: ClassId
    get() = lorenzToClassId(fullDeobfuscatedName)
val ClassMapping<*, *>.obfuscatedClassId: ClassId
    get() = lorenzToClassId(fullObfuscatedName)
private fun lorenzToClassId(name: String): ClassId {
    val pkgSplit = name.lastIndexOf('/')
    val pkg = if (pkgSplit > 0) name.substring(0, pkgSplit) else ""
    val cls = if (pkgSplit > 0) name.substring(pkgSplit + 1) else name
    return ClassId(FqName(pkg.replace('/', '.')), FqName(cls.replace('$', '.')), false)
}
