package com.replaymod.gradle.remap

import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MappingTreeView.ClassMappingView
import net.fabricmc.mappingio.tree.MappingTreeView.ElementMappingView
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

val ElementMappingView.dstName: String?
    get() = getDstName(0)

fun MappingTreeView.getClass(srcClassId: ClassId): ClassMappingView? =
    getClass(classIdToMappingIo(srcClassId))

val ClassMappingView.srcClassId: ClassId
    get() = mappingIoToClassId(srcName)
val ClassMappingView.dstClassId: ClassId
    get() = mappingIoToClassId(dstName ?: srcName)

private fun mappingIoToClassId(name: String): ClassId {
    val pkgSplit = name.lastIndexOf('/')
    val pkg = if (pkgSplit > 0) name.substring(0, pkgSplit) else ""
    val cls = if (pkgSplit > 0) name.substring(pkgSplit + 1) else name
    return ClassId(FqName(pkg.replace('/', '.')), FqName(cls.replace('$', '.')), false)
}
private fun classIdToMappingIo(classId: ClassId): String {
    return buildString {
        if (!classId.packageFqName.isRoot) {
            append(classId.packageFqName.asString().replace('.', '/'))
            append("/")
        }
        append(classId.relativeClassName.asString().replace('.', '$'))
    }
}
