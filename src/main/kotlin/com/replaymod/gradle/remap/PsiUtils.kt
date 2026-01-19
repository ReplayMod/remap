package com.replaymod.gradle.remap

import org.cadixdev.bombe.type.ArrayType
import org.cadixdev.bombe.type.FieldType
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.ObjectType
import org.cadixdev.bombe.type.Type
import org.cadixdev.bombe.type.VoidType
import org.cadixdev.bombe.type.signature.MethodSignature
import org.jetbrains.kotlin.com.intellij.ide.highlighter.JavaClassFileType
import org.jetbrains.kotlin.com.intellij.openapi.fileTypes.FileType
import org.jetbrains.kotlin.com.intellij.openapi.project.Project
import org.jetbrains.kotlin.com.intellij.openapi.util.Key
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.openapi.vfs.VirtualFile
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.com.intellij.psi.impl.compiled.ClsFileImpl
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.PsiTreeUtil
import org.jetbrains.kotlin.com.intellij.psi.util.TypeConversionUtil
import org.jetbrains.kotlin.com.intellij.testFramework.LightVirtualFile
import org.jetbrains.kotlin.com.intellij.util.containers.ContainerUtil
import org.objectweb.asm.ClassReader
import org.objectweb.asm.ClassVisitor
import org.objectweb.asm.ClassWriter
import org.objectweb.asm.Opcodes
import java.nio.charset.StandardCharsets

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

        val classFileName = fqn.name.joinToString("$") + ".class"
        val virtualFile = outerCls.containingFile.virtualFile.parent.findChild(classFileName)
            ?: continue

        return getPsiInnerClass(project, virtualFile)
    }

    return null
}

private val PSI_INNER_CLASS_KEY = Key<MutableMap<Project, PsiClass>>("PSI_INNER_CLASS_KEY")
private fun getPsiInnerClass(project: Project, virtualFile: VirtualFile): PsiClass {
    val cacheMap = virtualFile.getUserData(PSI_INNER_CLASS_KEY)
        ?: virtualFile.putUserDataIfAbsent(PSI_INNER_CLASS_KEY, ContainerUtil.createConcurrentWeakKeySoftValueMap())
    return cacheMap.getOrPut(project) { readPsiInnerClass(project, virtualFile) }
}

private fun readPsiInnerClass(project: Project, virtualFile: VirtualFile): PsiClass {
    val bytes = virtualFile.contentsToByteArray()

    // ClassFileViewProvider will refuse to read inner classes,
    // so we'll create a copy of the class file which looks like an outer class.
    val classReader = ClassReader(bytes)
    val classWriter = ClassWriter(classReader, 0)
    val className = classReader.className
    classReader.accept(object : ClassVisitor(Opcodes.ASM9, classWriter) {
        override fun visitOuterClass(owner: String, name: String?, descriptor: String?) {
        }
        override fun visitInnerClass(name: String, outerName: String?, innerName: String?, access: Int) {
            if (name == className) return
            super.visitInnerClass(name, outerName, innerName, access)
        }
    }, 0)
    val fakeBytes = classWriter.toByteArray()
    val charset = StandardCharsets.ISO_8859_1 // allows mapping all bytes to chars and back again without loss
    val fakeFile = LightVirtualFile(virtualFile.name, JavaClassFileType.INSTANCE, fakeBytes.toString(charset))
    fakeFile.charset = charset

    val psiManager = PsiManager.getInstance(project)
    val viewProvider = object : ClassFileViewProvider(psiManager, fakeFile, false) {
        // Bypass FilesIndexFacade.isInLibraryClasses check
        override fun createFile(project: Project, file: VirtualFile, fileType: FileType): PsiFile? {
            return ClsFileImpl(this)
        }
    }
    val psiFile = viewProvider.getPsi(viewProvider.baseLanguage)
    return (psiFile as PsiJavaFile).classes.single()
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

    private fun getFieldType(type: PsiType?): FieldType? = when (val erasedType = TypeConversionUtil.erasure(type)) {
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
    }

    private fun getType(type: PsiType?): Type? = if (TypeConversionUtil.isVoidType(type)) {
        VoidType.INSTANCE
    } else {
        getFieldType(type)
    }
}
