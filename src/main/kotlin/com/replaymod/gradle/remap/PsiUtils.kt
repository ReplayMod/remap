package com.replaymod.gradle.remap

import org.cadixdev.bombe.type.ArrayType
import org.cadixdev.bombe.type.FieldType
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.ObjectType
import org.cadixdev.bombe.type.Type
import org.cadixdev.bombe.type.VoidType
import org.cadixdev.bombe.type.signature.MethodSignature
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsClassImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.JavaStubElementTypes
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

internal data class FullyQualifiedClassName(val pkg: String, val name: List<String>) {
    val javaFqn: String
        get() = pkg + ".".takeUnless { pkg.isEmpty() } + name.joinToString(".")

    companion object {
        fun guess(str: String): Sequence<FullyQualifiedClassName> {
            if ("/" in str) {
                val split = str.lastIndexOf('/')
                return sequenceOf(FullyQualifiedClassName(
                    str.substring(0, split).replace('/', '.'),
                    str.substring(split + 1).split('$'),
                ))
            }

            if ("$" in str) {
                val split = str.lastIndexOf('.')
                return sequenceOf(FullyQualifiedClassName(
                    str.substring(0, split),
                    str.substring(split + 1).split('$'),
                ))
            }

            var split = str.length
            return generateSequence {
                split = str.lastIndexOf('.', split - 1)
                if (split == -1) return@generateSequence null
                FullyQualifiedClassName(
                    str.substring(0, split),
                    str.substring(split + 1).split('.'),
                )
            }
        }
    }
}

internal fun findPsiClass(project: Project, name: String): PsiClass? {
    JavaPsiFacade.getInstance(project).findClass(
        name.replace('/', '.').replace('$', '.'),
        GlobalSearchScope.allScope(project),
    )?.let { return it }

    // JavaPsiFacade won't be able to find "anonymous" classes (e.g. "MyClass$1"), we'll have to find those ourselves
    for (fqn in FullyQualifiedClassName.guess(name)) {
        if (fqn.name.size <= 1) continue // not an inner class

        val outerName = fqn.copy(name = listOf(fqn.name.first()))
        val outerCls = JavaPsiFacade.getInstance(project).findClass(outerName.javaFqn, GlobalSearchScope.allScope(project))
            ?: continue

        var cls = outerCls
        for (name in fqn.name.drop(1)) {
            cls = (cls as ClsClassImpl).stub
                .childrenStubs
                .firstNotNullOfOrNull { stub ->
                    if (stub.stubType != JavaStubElementTypes.CLASS) return@firstNotNullOfOrNull null
                    val psi = stub.psi as PsiClass
                    if (psi.name != name) return@firstNotNullOfOrNull null
                    psi
                }
                ?: return null
        }
        return cls
    }

    return null
}

internal val PsiClass.dollarQualifiedName: String? get() {
    val parent = PsiTreeUtil.getParentOfType<PsiClass>(this, PsiClass::class.java) ?: return qualifiedName
    val parentName = parent.dollarQualifiedName ?: return qualifiedName
    val selfName = name ?: return qualifiedName
    return "$parentName$$selfName"
}

internal val PsiNameValuePair.resolvedLiteralValue: Pair<PsiLiteralExpression, String>?
    get () = value?.resolvedLiteralValue

private val PsiElement.resolvedLiteralValue: Pair<PsiLiteralExpression, String>? get () {
    var value: PsiElement? = this
    while (value is PsiReferenceExpression) {
        val resolved = value.resolve()
        value = when (resolved) {
            is PsiField -> resolved.initializer
            else -> resolved
        }
    }
    val literal = value as? PsiLiteralExpression ?: return null
    return Pair(literal, StringUtil.unquoteString(literal.text))
}

internal val PsiAnnotationMemberValue.resolvedLiteralValues: List<Pair<PsiLiteralExpression, String>>
    get () = when (this) {
        is PsiArrayInitializerMemberValue -> initializers.mapNotNull { it.resolvedLiteralValue }
        else -> listOfNotNull(resolvedLiteralValue)
    }

internal fun PsiClass.getAsmTree(): ClassNode? {
    if (containingClass != null) {
        // For inner classes, `containingFile` returns the file of the outer class.
        // So for those we'll need to locate the proper class file ourselves.
        // JavaPsiFacade won't be able to find "anonymous" classes (e.g. "MyClass$1"), we'll have to find those ourselves
        val outerFile = containingFile?.virtualFile ?: return null
        if (outerFile.extension != "class") return null

        val folder = outerFile.parent ?: return null
        val classFileName = generateSequence(this) { it.containingClass }
            .toList()
            .asReversed()
            .joinToString("$", postfix = ".class") { it.name ?: "" }
        val classFile = folder.findChild(classFileName) ?: return null
        return classFile.readAsmTree()
    }
    return containingFile?.virtualFile?.getAsmTree()
}

private val ASM_TREE_KEY = Key<ClassNode>("ASM_TREE")
internal fun VirtualFile.getAsmTree(): ClassNode? {
    val cached = getUserData(ASM_TREE_KEY)
    if (cached != null) {
        return cached
    } else {
        val value = readAsmTree()
        putUserData(ASM_TREE_KEY, value)
        return value
    }
}
internal fun VirtualFile.readAsmTree(): ClassNode? {
    if (extension != "class") return null
    val bytes = contentsToByteArray()
    val node = ClassNode()
    val reader = ClassReader(bytes)
    reader.accept(node, ClassReader.SKIP_DEBUG or ClassReader.SKIP_CODE or ClassReader.SKIP_FRAMES)
    return node
}

internal object PsiUtils {
    fun getSignature(method: PsiMethod): MethodSignature? {
        return MethodSignature(method.name, getDescriptor(method) ?: return null)
    }

    private fun getDescriptor(method: PsiMethod): MethodDescriptor? {
        return MethodDescriptor(
            method.parameterList.parameters.map { getFieldType(it.type) ?: return null },
            getType(method.returnType) ?: return null
        )
    }

    private fun getFieldType(type: PsiType?): FieldType? { return when (val erasedType = TypeConversionUtil.erasure(type)) {
        is PsiPrimitiveType -> FieldType.of(erasedType.kind.binaryName)
        is PsiArrayType -> {
            val array = erasedType as PsiArrayType?
            ArrayType(array!!.arrayDimensions, getFieldType(array.deepComponentType))
        }
        is PsiClassType -> {
            val resolved = erasedType.resolve() ?: return null
            val qualifiedName = resolved.dollarQualifiedName ?: return null
            ObjectType(qualifiedName)
        }
        else -> throw IllegalArgumentException("Cannot translate type " + erasedType!!)
    }}

    private fun getType(type: PsiType?): Type? = if (TypeConversionUtil.isVoidType(type)) {
        VoidType.INSTANCE
    } else {
        getFieldType(type)
    }
}
