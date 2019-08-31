package com.replaymod.gradle.remap

import com.replaymod.gradle.remap.PsiUtils.getSignature
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.psi.*
import java.util.*

internal class PsiMapper(private val map: MappingSet, private val file: PsiFile) {
    private val mixinMappings = mutableMapOf<String, ClassMapping<*, *>>()
    private var error: Boolean = false
    private val changes = TreeMap<TextRange, String>(Comparator.comparing<TextRange, Int> { it.startOffset })

    private fun error(at: PsiElement, message: String) {
        val line = StringUtil.offsetToLineNumber(file.text, at.textOffset)
        System.err.println(file.name + ":" + line + ": " + message)
        error = true
    }

    private fun replace(e: PsiElement, with: String) {
        changes[e.textRange] = with
    }

    private fun replaceIdentifier(parent: PsiElement, with: String) {
        for (child in parent.children) {
            if (child is PsiIdentifier) {
                replace(child, with)
                return
            }
        }
    }

    private fun valid(e: PsiElement): Boolean {
        val range = e.textRange
        val before = changes.ceilingKey(range)
        return before == null || !before.intersects(range)
    }

    private fun getResult(text: String): String? {
        if (error) {
            return null
        }
        var result = text
        for ((key, value) in changes.descendingMap()) {
            result = key.replace(result, value)
        }
        return result
    }

    private fun map(expr: PsiElement, field: PsiField) {
        val fieldName = field.name ?: return
        val declaringClass = field.containingClass ?: return
        val name = declaringClass.qualifiedName ?: return
        var mapping: ClassMapping<*, *>? = this.mixinMappings[name]
        if (mapping == null) {
            mapping = map.findClassMapping(name)
        }
        if (mapping == null) return
        val mapped = mapping.findFieldMapping(fieldName)?.deobfuscatedName
        if (mapped == null || mapped == fieldName) return
        replaceIdentifier(expr, mapped)

        if (expr is PsiJavaCodeReferenceElement
                && !expr.isQualified // qualified access is fine
                && !isSwitchCase(expr) // referencing constants in case statements is fine
        ) {
            error(expr, "Implicit member reference to remapped field \"$fieldName\". " +
                    "This can cause issues if the remapped reference becomes shadowed by a local variable and is therefore forbidden. " +
                    "Use \"this.$fieldName\" instead.")
        }
    }

    private fun map(expr: PsiElement, method: PsiMethod) {
        if (method.isConstructor) return

        var declaringClass: PsiClass? = method.containingClass ?: return
        val parentQueue = ArrayDeque<PsiClass>()
        parentQueue.offer(declaringClass)
        var mapping: ClassMapping<*, *>? = null

        var name = declaringClass!!.qualifiedName
        if (name != null) {
            mapping = mixinMappings[name]
        }
        while (true) {
            if (mapping != null) {
                val mapped = mapping.findMethodMapping(getSignature(method))?.deobfuscatedName
                if (mapped != null) {
                    if (mapped != method.name) {
                        replaceIdentifier(expr, mapped)
                    }
                    return
                }
                mapping = null
            }
            while (mapping == null) {
                declaringClass = parentQueue.poll()
                if (declaringClass == null) return

                val superClass = declaringClass.superClass
                if (superClass != null) {
                    parentQueue.offer(superClass)
                }
                for (anInterface in declaringClass.interfaces) {
                    parentQueue.offer(anInterface)
                }

                name = declaringClass.qualifiedName
                if (name == null) continue
                mapping = map.findClassMapping(name)
            }
        }
    }

    private fun map(expr: PsiElement, resolved: PsiQualifiedNamedElement) {
        val name = resolved.qualifiedName ?: return
        val mapping = map.findClassMapping(name) ?: return
        var mapped = mapping.deobfuscatedName
        if (mapped == name) return
        mapped = mapped.replace('/', '.')

        if (expr.text == name) {
            replace(expr, mapped)
            return
        }
        replaceIdentifier(expr, mapped.substring(mapped.lastIndexOf('.') + 1))
    }

    private fun map(expr: PsiElement, resolved: PsiElement?) {
        when (resolved) {
            is PsiField -> map(expr, resolved)
            is PsiMethod -> map(expr, resolved)
            is PsiClass, is PsiPackage -> map(expr, resolved as PsiQualifiedNamedElement)
        }
    }

    // Note: Supports only Mixins with a single target (ignores others) and only ones specified via class literals
    private fun getMixinTarget(annotation: PsiAnnotation): PsiClass? {
        for (pair in annotation.parameterList.attributes) {
            val name = pair.name
            if (name != null && "value" != name) continue
            val value = pair.value
            if (value !is PsiClassObjectAccessExpression) continue
            val type = value.operand
            val reference = type.innermostComponentReferenceElement ?: continue
            return reference.resolve() as PsiClass?
        }
        return null
    }

    private fun remapAccessors(mapping: ClassMapping<*, *>) {
        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                val annotation = method.getAnnotation(CLASS_ACCESSOR) ?: return

                val methodName = method.name
                val targetByName = when {
                    methodName.startsWith("is") -> methodName.substring(2)
                    methodName.startsWith("get") || methodName.startsWith("set") -> methodName.substring(3)
                    else -> null
                }?.decapitalize()

                val target = annotation.parameterList.attributes.find {
                    it.name == null || it.name == "value"
                }?.literalValue ?: targetByName ?: throw IllegalArgumentException("Cannot determine accessor target for $method")

                val mapped = mapping.findFieldMapping(target)?.deobfuscatedName
                if (mapped != null && mapped != target) {
                    // Update accessor target
                    replace(annotation.parameterList, if (mapped == targetByName) {
                        // Mapped name matches implied target, can just remove the explict target
                        ""
                    } else {
                        // Mapped name does not match implied target, need to set the target as annotation value
                        "(\"" + StringUtil.escapeStringCharacters(mapped) + "\")"
                    })
                }
            }
        })
    }

    private fun remapInjectsAndRedirects(mapping: ClassMapping<*, *>) {
        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                val annotation = method.getAnnotation(CLASS_INJECT) ?: method.getAnnotation(CLASS_REDIRECT) ?: return

                for (attribute in annotation.parameterList.attributes) {
                    if ("method" != attribute.name) continue
                    // Note: mixin supports multiple targets, we do not (yet)
                    val literalValue = attribute.literalValue ?: continue
                    var mapped: String?
                    if (literalValue.contains("(")) {
                        mapped = mapping.findMethodMapping(MethodSignature.of(literalValue))?.deobfuscatedName
                    } else {
                        mapped = null
                        for (methodMapping in mapping.methodMappings) {
                            if (methodMapping.obfuscatedName == literalValue) {
                                val name = methodMapping.deobfuscatedName
                                if (mapped != null && mapped != name) {
                                    error(attribute, "Ambiguous mixin method \"$literalValue\" maps to \"$mapped\" and \"$name\"")
                                }
                                mapped = name
                            }
                        }
                    }
                    if (mapped != null && mapped != literalValue) {
                        val value = attribute.value!!
                        replace(value, '"'.toString() + mapped + '"'.toString())
                    }
                }
            }
        })
    }

    private fun remapInternalType(internalType: String, result: StringBuilder): ClassMapping<*, *>? {
        if (internalType[0] == 'L') {
            val type = internalType.substring(1, internalType.length - 1).replace('/', '.')
            val mapping = map.findClassMapping(type)
            if (mapping != null) {
                result.append('L').append(mapping.fullDeobfuscatedName).append(';')
                return mapping
            }
        }
        result.append(internalType)
        return null
    }

    private fun remapFullyQualifiedMethodOrField(signature: String): String {
        val ownerEnd = signature.indexOf(';')
        var argsBegin = signature.indexOf('(')
        var argsEnd = signature.indexOf(')')
        val method = argsBegin != -1
        if (!method) {
            argsEnd = signature.indexOf(':')
            argsBegin = argsEnd
        }
        val owner = signature.substring(0, ownerEnd + 1)
        val name = signature.substring(ownerEnd + 1, argsBegin)
        val returnType = signature.substring(argsEnd + 1)

        val builder = StringBuilder(signature.length + 32)
        val mapping = remapInternalType(owner, builder)
        var mapped: String? = null
        if (mapping != null) {
            mapped = (if (method) {
                mapping.findMethodMapping(MethodSignature.of(signature.substring(ownerEnd + 1)))
            } else {
                mapping.findFieldMapping(name)
            })?.deobfuscatedName
        }
        builder.append(mapped ?: name)
        if (method) {
            builder.append('(')
            val args = signature.substring(argsBegin + 1, argsEnd)
            var i = 0
            while (i < args.length) {
                val c = args[i]
                if (c != 'L') {
                    builder.append(c)
                    i++
                    continue
                }
                val end = args.indexOf(';', i)
                val arg = args.substring(i, end + 1)
                remapInternalType(arg, builder)
                i = end
                i++
            }
            builder.append(')')
        } else {
            builder.append(':')
        }
        remapInternalType(returnType, builder)
        return builder.toString()
    }

    private fun remapAtTargets() {
        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
                if (CLASS_AT != annotation.qualifiedName) {
                    super.visitAnnotation(annotation)
                    return
                }

                for (attribute in annotation.parameterList.attributes) {
                    if ("target" != attribute.name) continue
                    val signature = attribute.literalValue ?: continue
                    val newSignature = remapFullyQualifiedMethodOrField(signature)
                    if (newSignature != signature) {
                        val value = attribute.value!!
                        replace(value, "\"$newSignature\"")
                    }
                }
            }
        })
    }

    fun remapFile(): String? {
        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitClass(psiClass: PsiClass) {
                val annotation = psiClass.getAnnotation(CLASS_MIXIN) ?: return

                remapAtTargets()

                val target = getMixinTarget(annotation) ?: return
                val qualifiedName = target.qualifiedName ?: return

                val mapping = map.findClassMapping(qualifiedName) ?: return

                mixinMappings[psiClass.qualifiedName!!] = mapping

                if (!mapping.fieldMappings.isEmpty()) {
                    remapAccessors(mapping)
                }
                if (!mapping.methodMappings.isEmpty()) {
                    remapInjectsAndRedirects(mapping)
                }
            }
        })

        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitField(field: PsiField) {
                if (valid(field)) {
                    map(field, field)
                }
                super.visitField(field)
            }

            override fun visitMethod(method: PsiMethod) {
                if (valid(method)) {
                    map(method, method)
                }
                super.visitMethod(method)
            }

            override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                if (valid(reference)) {
                    map(reference, reference.resolve())
                }
                super.visitReferenceElement(reference)
            }
        })

        return getResult(file.text)
    }

    companion object {
        private const val CLASS_MIXIN = "org.spongepowered.asm.mixin.Mixin"
        private const val CLASS_ACCESSOR = "org.spongepowered.asm.mixin.gen.Accessor"
        private const val CLASS_AT = "org.spongepowered.asm.mixin.injection.At"
        private const val CLASS_INJECT = "org.spongepowered.asm.mixin.injection.Inject"
        private const val CLASS_REDIRECT = "org.spongepowered.asm.mixin.injection.Redirect"

        private fun isSwitchCase(e: PsiElement): Boolean {
            if (e is PsiSwitchLabelStatement) {
                return true
            }
            val parent = e.parent
            return parent != null && isSwitchCase(parent)
        }
    }
}
