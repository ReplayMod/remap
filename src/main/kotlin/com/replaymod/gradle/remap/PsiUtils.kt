package com.replaymod.gradle.remap

import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.signature.MethodSignature
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsClassImpl
import org.jetbrains.kotlin.com.intellij.psi.impl.java.stubs.JavaStubElementTypes
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.ClassUtil
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.objectweb.asm.ClassReader
import org.objectweb.asm.tree.ClassNode

internal fun possibleClassIdsForMixinString(name: String): Sequence<ClassId> {
    // JVM-style name, e.g. a/pkg/A$Inner
    if ('/' in name) {
        val split = name.lastIndexOf('/')
        return sequenceOf(ClassId(
            FqName(name.substring(0, split).replace('/', '.')),
            FqName(name.substring(split + 1).replace('$', '.')),
            isLocal = false,
        ))
    }

    // Java-style package with class file name, e.g. a.pkg.A$Inner
    if ('$' in name) {
        val split = name.lastIndexOf('.')
        return sequenceOf(ClassId(
            FqName(name.substring(0, split).replace('/', '.')),
            FqName(name.substring(split + 1).replace('$', '.')),
            isLocal = false,
        ))
    }

    // Java-style name, e.g. a.pkg.A.Inner
    // For these we cannot for sure know what belongs to the outer vs inner class, so we need to try all combinations
    var split = name.length
    return generateSequence {
        split = name.lastIndexOf('.', split - 1)
        if (split == -1) return@generateSequence null
        ClassId(
            FqName(name.substring(0, split)),
            FqName(name.substring(split + 1)),
            isLocal = false,
        )
    }
}

internal fun findPsiClass(classId: ClassId, project: Project): PsiClass? {
    JavaPsiFacade.getInstance(project).findClass(
        classId.asFqNameString(),
        GlobalSearchScope.allScope(project),
    )?.let { return it }

    // JavaPsiFacade won't be able to find "anonymous" classes (e.g. "MyClass$1"), we'll have to find those ourselves
    val outermostCls = JavaPsiFacade.getInstance(project)
        .findClass(classId.outermostClassId.asFqNameString(), GlobalSearchScope.allScope(project))
        ?: return null

    var cls = outermostCls
    for (name in classId.relativeClassName.pathSegments().drop(1)) {
        cls = (cls as ClsClassImpl).stub
            .childrenStubs
            .firstNotNullOfOrNull { stub ->
                if (stub.stubType != JavaStubElementTypes.CLASS) return@firstNotNullOfOrNull null
                val psi = stub.psi as PsiClass
                if (psi.name != name.asString()) return@firstNotNullOfOrNull null
                psi
            }
            ?: return null
    }
    return cls
}

internal val ClassId.dollarQualifiedName: String
    get() = relativeClassName.pathSegments().joinToString(
        separator = "$",
        prefix = if (packageFqName.isRoot) "" else packageFqName.asString() + ".",
    )

internal val PsiClass.classId: ClassId? get() {
    val parent = PsiTreeUtil.getParentOfType(this, PsiClass::class.java)
        ?: return qualifiedName?.let { ClassId.topLevel(FqName(it)) }
    return parent.classId?.createNestedClassId(Name.identifier(name ?: return null))
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
    fun getSignature(method: PsiMethod): MethodSignature {
        val asm = ClassUtil.getAsmMethodSignature(method)
        // The shaded ClassUtil returns "()" (no return type) for some Kotlin ultra-light methods whose return type
        // cannot be resolved; fall back to a void descriptor in that case. The correct signature is still found via
        // the method's super methods, so this placeholder is only ever a harmless last resort.
        return MethodSignature(method.name, MethodDescriptor.of(if (asm.substringAfterLast(")").isEmpty()) "()V" else asm))
    }
}
