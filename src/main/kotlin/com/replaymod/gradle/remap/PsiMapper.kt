package com.replaymod.gradle.remap

import com.replaymod.gradle.remap.PsiUtils.getSignature
import org.cadixdev.bombe.type.signature.MethodSignature
import org.cadixdev.lorenz.MappingSet
import org.cadixdev.lorenz.model.ClassMapping
import org.cadixdev.lorenz.model.MethodMapping
import org.jetbrains.kotlin.asJava.getRepresentativeLightMethod
import org.jetbrains.kotlin.com.intellij.lang.ASTNode
import org.jetbrains.kotlin.com.intellij.lang.jvm.JvmModifier
import org.jetbrains.kotlin.com.intellij.openapi.util.TextRange
import org.jetbrains.kotlin.com.intellij.openapi.util.text.StringUtil
import org.jetbrains.kotlin.com.intellij.psi.*
import org.jetbrains.kotlin.com.intellij.psi.search.GlobalSearchScope
import org.jetbrains.kotlin.com.intellij.psi.util.ClassUtil
import org.jetbrains.kotlin.descriptors.CallableMemberDescriptor
import org.jetbrains.kotlin.descriptors.FunctionDescriptor
import org.jetbrains.kotlin.js.resolve.diagnostics.findPsi
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.resolve.BindingContext
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor
import org.jetbrains.kotlin.synthetic.SyntheticJavaPropertyDescriptor.Companion.propertyNameByGetMethodName
import java.util.*

internal class PsiMapper(
        private val map: MappingSet,
        private val file: PsiFile,
        private val patterns: PsiPatterns?
) {
    private val mixinMappings = mutableMapOf<String, ClassMapping<*, *>>()
    private val errors = mutableListOf<Pair<Int, String>>()
    private val changes = TreeMap<TextRange, String>(compareBy<TextRange> { it.startOffset }.thenBy { it.endOffset })

    private fun error(at: PsiElement, message: String) {
        val line = StringUtil.offsetToLineNumber(file.text, at.textOffset)
        errors.add(Pair(line, message))
    }

    private fun replace(e: PsiElement, with: String) = replace(e.textRange, with)
    private fun replace(textRange: TextRange, with: String) {
        changes.compute(textRange) { _, replacement ->
            if (replacement != null) {
                replacement + with
            } else {
                with
            }
        }
    }

    private fun replaceIdentifier(parent: PsiElement, with: String) {
        var child = parent.firstChild
        while (child != null) {
            if (child is PsiIdentifier
                    || child is ASTNode && child.elementType == KtTokens.IDENTIFIER) {
                replace(child, with)
                return
            }
            child = child.nextSibling
        }
    }

    private fun valid(e: PsiElement): Boolean = valid(e.textRange)

    private fun valid(range: TextRange): Boolean {
        val before = changes.floorKey(range) ?: TextRange.EMPTY_RANGE
        val after = changes.ceilingKey(range) ?: TextRange.EMPTY_RANGE
        return !before.intersectsStrict(range) && !after.intersectsStrict(range)
    }

    private fun getResult(text: String): Pair<String, List<Pair<Int, String>>> {
        var result = text
        for ((key, value) in changes.descendingMap()) {
            result = key.replace(result, value)
        }
        return Pair(result, errors)
    }

    private fun map(expr: PsiElement, field: PsiField) {
        val fieldName = field.name ?: return
        val declaringClass = field.containingClass ?: return
        val name = declaringClass.dollarQualifiedName ?: return
        var mapping: ClassMapping<*, *>? = this.mixinMappings[declaringClass.qualifiedName ?: return]
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
        if (method.isConstructor) {
            if (expr is KtSimpleNameExpression) {
                map(expr, method.containingClass ?: return)
            }
            return
        }

        val mapped = findMapping(method)?.deobfuscatedName
        if (mapped != null && mapped != method.name) {
            val maybeGetter = propertyNameByGetMethodName(Name.identifier(mapped))
            if (maybeGetter != null // must have getter-style name
                    && !method.hasParameters() // getters cannot take any arguments
                    && method.returnType != PsiType.VOID // and must return some value
                    && !method.hasModifier(JvmModifier.STATIC) // synthetic properties cannot be static
                    // `super.getDebugInfo()` is a special case which cannot be replaced with a synthetic property
                    && expr.parent.parent.let { it !is KtDotQualifiedExpression || it.firstChild !is KtSuperExpression }
                    // cannot use synthetic properties outside of kotlin files (cause they're a kotlin thing)
                    && expr.containingFile  is KtFile) {
                // E.g. `entity.canUsePortal()` maps to `entity.isNonBoss()` but when we're using kotlin and the target
                // isn't (as should be the case for remapped names), we can also write that as `entity.isNonBoss` (i.e.
                // as a synthetic property).
                // This is the reverse to the operation in [map(PsiElement, SyntheticJavaPropertyDescriptor)].
                replace(expr.parent, maybeGetter.identifier)
                return
            }
            replaceIdentifier(expr, mapped)
        }
    }

    private fun map(expr: PsiElement, method: KtNamedFunction) {
        val psiMethod = method.getRepresentativeLightMethod()
        val mapped = findMapping(psiMethod ?: return)?.deobfuscatedName
        if (mapped != null && mapped != method.name) {
            replaceIdentifier(expr, mapped)
        }
    }

    private fun map(expr: PsiElement, property: SyntheticJavaPropertyDescriptor) {
        val getter = property.getMethod.findPsi() as? PsiMethod ?: return
        val mappedGetter = findMapping(getter)?.deobfuscatedName ?: return
        if (mappedGetter != getter.name) {
            val maybeMapped = propertyNameByGetMethodName(Name.identifier(mappedGetter))
            if (maybeMapped == null) {
                // Can happen if a method is a synthetic property in the current mapping (e.g. `isNonBoss`) but not
                // in the target mapping (e.g. `canUsePortal()`)
                // TODO probably also want to convert in the opposite direction, though that's a lot harder
                replaceIdentifier(expr, "$mappedGetter()")
            } else {
                val mapped = maybeMapped.identifier
                replaceIdentifier(expr, mapped)
            }
        }
    }

    // See caller for why this exists
    private fun map(expr: PsiElement, method: FunctionDescriptor) {
        for (overriddenDescriptor in method.overriddenDescriptors) {
            val overriddenPsi = overriddenDescriptor.findPsi()
            if (overriddenPsi != null) {
                map(expr, overriddenPsi) // found a psi element, continue as usually
            } else {
                map(expr, overriddenDescriptor) // recursion
            }
        }
    }

    private fun findMapping(method: PsiMethod): MethodMapping? {
        var declaringClass: PsiClass? = method.containingClass ?: return null
        val parentQueue = ArrayDeque<PsiClass>()
        parentQueue.offer(declaringClass)
        var mapping: ClassMapping<*, *>? = null

        var name = declaringClass!!.qualifiedName
        if (name != null) {
            // If this method is declared in a mixin class, we want to consider the hierarchy of the target as well
            mapping = mixinMappings[name]
            // but only if the method conceptually belongs to the target class
            val isShadow = method.getAnnotation(CLASS_SHADOW) != null
            val isOverwrite = method.getAnnotation(CLASS_OVERWRITE) != null
            val isOverride = method.getAnnotation(CLASS_OVERRIDE) != null
            if (mapping != null && !isShadow && !isOverwrite && !isOverride) {
                return null // otherwise, it belongs to the mixin and never gets remapped
            }
        }
        while (true) {
            if (mapping != null) {
                val mapped = mapping.findMethodMapping(getSignature(method))
                if (mapped != null) {
                    return mapped
                }
                mapping = null
            }
            while (mapping == null) {
                declaringClass = parentQueue.poll()
                if (declaringClass == null) return null

                val superClass = declaringClass.superClass
                if (superClass != null) {
                    parentQueue.offer(superClass)
                }
                for (anInterface in declaringClass.interfaces) {
                    parentQueue.offer(anInterface)
                }

                name = declaringClass.dollarQualifiedName
                if (name == null) continue
                mapping = map.findClassMapping(name)
            }
        }
    }

    private fun map(expr: PsiElement, resolved: PsiQualifiedNamedElement) {
        val name = resolved.qualifiedName ?: return
        val dollarName = (if (resolved is PsiClass) resolved.dollarQualifiedName else name) ?: return
        val mapping = map.findClassMapping(dollarName) ?: return
        var mapped = mapping.fullDeobfuscatedName
        if (mapped == dollarName) return
        mapped = mapped.replace('/', '.').replace('$', '.')

        if (expr.text == name) {
            replace(expr, mapped)
            return
        }
        val parent: PsiElement? = expr.parent
        if ((parent is KtUserType || parent is KtQualifiedExpression) && parent.text == name) {
            if (valid(parent)) {
                replace(parent, mapped)
            }
            return
        }
        // FIXME this incorrectly filters things like "Packet<?>" and doesn't filter same-name type aliases
        // if (expr.text != name.substring(name.lastIndexOf('.') + 1)) {
        //     return // type alias, will be remapped at its definition
        // }
        replaceIdentifier(expr, mapped.substring(mapped.lastIndexOf('.') + 1))
    }

    private fun map(expr: PsiElement, resolved: PsiElement?) {
        when (resolved) {
            is PsiField -> map(expr, resolved)
            is PsiMethod -> map(expr, resolved)
            is KtNamedFunction -> map(expr, resolved.getRepresentativeLightMethod())
            is PsiClass, is PsiPackage -> map(expr, resolved as PsiQualifiedNamedElement)
        }
    }

    // Note: Supports only Mixins with a single target (ignores others)
    private fun getMixinTarget(annotation: PsiAnnotation): Pair<PsiClass, ClassMapping<*, *>>? {
        for (pair in annotation.parameterList.attributes) {
            val name = pair.name
            if (name == null || "value" == name) {
                val value = pair.value
                if (value !is PsiClassObjectAccessExpression) continue
                val type = value.operand
                val reference = type.innermostComponentReferenceElement ?: continue
                val psiClass = reference.resolve() as PsiClass? ?: continue
                val qualifiedName = psiClass.dollarQualifiedName ?: continue
                val mapping = map.findClassMapping(qualifiedName) ?: continue
                return Pair(psiClass, mapping)
            }
            if ("targets" == name) {
                val value = pair.value
                if (value !is PsiLiteral) continue
                val qualifiedName = value.value as? String ?: continue
                val mapping = map.findPotentialInnerClassMapping(qualifiedName) ?: continue
                val mapped = mapping.fullDeobfuscatedName?.replace('/', '.')
                if (mapped != qualifiedName) {
                    replace(value, "\"$mapped\"")
                }
                val psiClass = JavaPsiFacade.getInstance(file.project).findClass(
                    qualifiedName.replace('$', '.'),
                    GlobalSearchScope.allScope(file.project),
                ) ?: continue
                return Pair(psiClass, mapping)
            }
        }
        return null
    }

    private fun remapAccessors(mapping: ClassMapping<*, *>) {
        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                val annotation = method.getAnnotation(CLASS_ACCESSOR) ?: method.getAnnotation(CLASS_INVOKER) ?: return

                val methodName = method.name
                val targetByName = when {
                    methodName.startsWith("invoke") -> methodName.substring(6)
                    methodName.startsWith("is") -> methodName.substring(2)
                    methodName.startsWith("get") || methodName.startsWith("set") -> methodName.substring(3)
                    else -> null
                }?.decapitalize()

                val target = annotation.parameterList.attributes.find {
                    it.name == null || it.name == "value"
                }?.literalValue ?: targetByName ?: throw IllegalArgumentException("Cannot determine accessor target for $method")

                val mapped = if (methodName.startsWith("invoke")) {
                    mapping.methodMappings.find { it.obfuscatedName == target }?.deobfuscatedName
                } else {
                    mapping.findFieldMapping(target)?.deobfuscatedName
                }
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

    private fun remapMixinInjections(targetClass: PsiClass, mapping: ClassMapping<*, *>) {
        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitMethod(method: PsiMethod) {
                val annotation = method.getAnnotation(CLASS_INJECT)
                        ?: method.getAnnotation(CLASS_MODIFY_ARG)
                        ?: method.getAnnotation(CLASS_MODIFY_ARGS)
                        ?: method.getAnnotation(CLASS_MODIFY_CONSTANT)
                        ?: method.getAnnotation(CLASS_MODIFY_VARIABLE)
                        ?: method.getAnnotation(CLASS_REDIRECT)
                        ?: return

                for (attribute in annotation.parameterList.attributes) {
                    if ("method" != attribute.name) continue
                    // Note: mixin supports multiple targets, we do not (yet)
                    val (literalExpr, literalValue) = attribute.resolvedLiteralValue ?: continue
                    val (targetName, targetDesc) = if ('(' in literalValue) {
                        MethodSignature.of(literalValue).let { it.name to it.descriptor.toString() }
                    } else {
                        literalValue to null
                    }
                    val targetMethods = targetClass.findMethodsByName(targetName, false)
                    val targetMethod = if (targetDesc != null) {
                        targetMethods.find {
                            ClassUtil.getAsmMethodSignature(it) == targetDesc
                        }
                    } else {
                        if (targetMethods.size > 1) {
                            error(attribute, "Ambiguous mixin method \"$targetName\" may refer to any of: ${targetMethods.joinToString { 
                                "\"${it.name}${ClassUtil.getAsmMethodSignature(it)}\"" 
                            }}")
                        }
                        targetMethods.firstOrNull()
                    }
                    val mappedName = targetMethod?.let(::findMapping)?.deobfuscatedName ?: targetName

                    val ambiguousName = mapping.methodMappings.count { it.deobfuscatedName == mappedName } > 1
                    val mapped = mappedName + when {
                        ambiguousName && targetMethod != null ->
                            remapMethodDesc(ClassUtil.getAsmMethodSignature(targetMethod))
                        targetDesc != null -> remapMethodDesc(targetDesc)
                        else -> ""
                    }

                    if (mapped != literalValue) {
                        replace(literalExpr, '"'.toString() + mapped + '"'.toString())
                    }
                }
            }
        })
    }

    private fun remapInternalType(internalType: String): String =
            StringBuilder().apply { remapInternalType(internalType, this) }.toString()

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

    private fun remapMixinTarget(target: String): String {
        return if (target.contains(':') || target.contains('(')) {
            remapFullyQualifiedMethodOrField(target)
        } else {
            if (target[0] == 'L') {
                remapInternalType(target)
            } else {
                remapInternalType("L$target;").drop(1).dropLast(1)
            }
        }
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

        val ownerPsi = JavaPsiFacade.getInstance(file.project).findClass(
            owner.drop(1).dropLast(1).replace('/', '.').replace('$', '.'),
            GlobalSearchScope.allScope(file.project),
        )
        val methodPsi = if (method) {
            val desc = signature.substring(argsBegin)
            ownerPsi?.findMethodsByName(name, true)?.find { ClassUtil.getAsmMethodSignature(it) == desc }
        } else {
            null
        }

        val builder = StringBuilder(signature.length + 32)
        val mapping = remapInternalType(owner, builder)
        var mapped: String? = null
        if (methodPsi != null) {
            mapped = findMapping(methodPsi)?.deobfuscatedName
        }
        if (mapped == null && mapping != null) {
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

    private fun remapMethodDesc(desc: String): String =
        remapFullyQualifiedMethodOrField("Ldummy;dummy$desc").dropWhile { it != '(' }

    private fun remapAtTargets() {
        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitAnnotation(annotation: PsiAnnotation) {
                if (CLASS_AT != annotation.qualifiedName) {
                    super.visitAnnotation(annotation)
                    return
                }

                for (attribute in annotation.parameterList.attributes) {
                    if ("target" != attribute.name) continue
                    val (value, signature) = attribute.resolvedLiteralValue ?: continue
                    val newSignature = remapMixinTarget(signature)
                    if (newSignature != signature && valid(value)) {
                        replace(value, "\"$newSignature\"")
                    }
                }
            }
        })
    }

    private fun applyPatternMatch(matcher: PsiPattern.Matcher) {
        val changes = matcher.toChanges()
        if (changes.all { valid(it.first) }) {
            changes.forEach { (range, text) -> replace(range, text)}
        } else if (changes.any { it.first !in this.changes }) {
            System.err.println("Conflicting pattern changes in $file")
            System.err.println("Proposed changes:")
            changes.forEach { println("${it.first}: \"${it.second}\" (${if (valid(it.first)) "accepted" else "rejected"})") }
            System.err.println("Current changes:")
            this.changes.forEach { println("${it.key}: \"${it.value}\"") }
        }
    }

    fun remapFile(bindingContext: BindingContext): Pair<String, List<Pair<Int, String>>> {
        if (patterns != null) {
            file.accept(object : JavaRecursiveElementVisitor() {
                override fun visitCodeBlock(block: PsiCodeBlock) {
                    patterns.find(block).forEach { applyPatternMatch(it) }
                }

                override fun visitExpression(expression: PsiExpression) {
                    patterns.find(expression).forEach { applyPatternMatch(it) }
                }
            })
        }

        file.accept(object : JavaRecursiveElementVisitor() {
            override fun visitClass(psiClass: PsiClass) {
                val annotation = psiClass.getAnnotation(CLASS_MIXIN) ?: return

                remapAtTargets()

                val (targetClass, mapping) = getMixinTarget(annotation) ?: return

                mixinMappings[psiClass.qualifiedName!!] = mapping

                if (!mapping.fieldMappings.isEmpty()) {
                    remapAccessors(mapping)
                }
                if (!mapping.methodMappings.isEmpty()) {
                    remapMixinInjections(targetClass, mapping)
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

        if (file is KtFile) {
            file.accept(object : KtTreeVisitor<Void>() {
                override fun visitNamedFunction(function: KtNamedFunction, data: Void?): Void? {
                    if (valid(function)) {
                        map(function, function)
                    }
                    return super.visitNamedFunction(function, data)
                }

                override fun visitDotQualifiedExpression(expression: KtDotQualifiedExpression, data: Void?): Void? {
                    // Dot qualified expressions such as "a.pkg.A.Inner" we want to remap back to front because the
                    // latter parts are more specific.
                    // I.e. we start with the inner class, and only if there is no mapping for that, do we try to remap
                    // the outer class.
                    expression.selectorExpression?.accept(this)
                    expression.receiverExpression.accept(this)
                    return null
                }

                override fun visitUserType(type: KtUserType, data: Void?): Void? {
                    // Same as visitDotQualifiedExpression but for typealias declarations
                    type.referenceExpression?.accept(this)
                    type.qualifier?.accept(this)
                    type.typeArgumentList?.accept(this)
                    return null
                }

                override fun visitReferenceExpression(expression: KtReferenceExpression, data: Void?): Void? {
                    if (valid(expression)) {
                        val target = bindingContext[BindingContext.REFERENCE_TARGET, expression]
                        if (target is SyntheticJavaPropertyDescriptor) {
                            map(expression, target)
                        } else if (target != null && (target as? CallableMemberDescriptor)?.kind != CallableMemberDescriptor.Kind.SYNTHESIZED) {
                            val targetPsi = target.findPsi()
                            if (targetPsi != null) {
                                map(expression, targetPsi)
                            } else if (target is FunctionDescriptor) {
                                // Appears to be the case if we're referencing an overwritten function in a previously
                                // compiled kotlin file
                                // E.g. A.f overwrites B.f overwrites C.f
                                //      C is a Minecraft class, B is a previously compiled (and already remapped) kotlin
                                //      class and we're currently in A.f trying to call `super.f()`.
                                //      `target` is a DeserializedSimpleFunctionDescriptor with no linked PSI element.
                                map(expression, target)
                            }
                        }
                    }
                    return super.visitReferenceExpression(expression, data)
                }
            }, null)
        }

        return getResult(file.text)
    }

    companion object {
        private const val CLASS_MIXIN = "org.spongepowered.asm.mixin.Mixin"
        private const val CLASS_SHADOW = "org.spongepowered.asm.mixin.Shadow"
        private const val CLASS_OVERWRITE = "org.spongepowered.asm.mixin.Overwrite"
        private const val CLASS_ACCESSOR = "org.spongepowered.asm.mixin.gen.Accessor"
        private const val CLASS_INVOKER = "org.spongepowered.asm.mixin.gen.Invoker"
        private const val CLASS_AT = "org.spongepowered.asm.mixin.injection.At"
        private const val CLASS_INJECT = "org.spongepowered.asm.mixin.injection.Inject"
        private const val CLASS_MODIFY_ARG = "org.spongepowered.asm.mixin.injection.ModifyArg"
        private const val CLASS_MODIFY_ARGS = "org.spongepowered.asm.mixin.injection.ModifyArgs"
        private const val CLASS_MODIFY_CONSTANT = "org.spongepowered.asm.mixin.injection.ModifyConstant"
        private const val CLASS_MODIFY_VARIABLE = "org.spongepowered.asm.mixin.injection.ModifyVariable"
        private const val CLASS_REDIRECT = "org.spongepowered.asm.mixin.injection.Redirect"
        private const val CLASS_OVERRIDE = "java.lang.Override"

        private fun isSwitchCase(e: PsiElement): Boolean {
            if (e is PsiSwitchLabelStatement) {
                return true
            }
            val parent = e.parent
            return parent != null && isSwitchCase(parent)
        }
    }
}
