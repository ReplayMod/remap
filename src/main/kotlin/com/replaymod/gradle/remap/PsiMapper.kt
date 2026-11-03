@file:OptIn(KaExperimentalApi::class)

package com.replaymod.gradle.remap

import com.intellij.lang.ASTNode
import com.intellij.lang.jvm.JvmModifier
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.openapi.util.text.StringUtil
import com.intellij.psi.*
import com.intellij.psi.util.ClassUtil
import com.replaymod.gradle.remap.PsiUtils.getSignature
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MappingTreeView.ClassMappingView
import net.fabricmc.mappingio.tree.MappingTreeView.FieldMappingView
import net.fabricmc.mappingio.tree.MappingTreeView.MethodMappingView
import org.jetbrains.kotlin.analysis.api.KaExperimentalApi
import org.jetbrains.kotlin.analysis.api.KaSession
import org.jetbrains.kotlin.analysis.api.javaInterop.javaMethodName
import org.jetbrains.kotlin.analysis.api.javaInterop.mapToJvmTypeDescriptor
import org.jetbrains.kotlin.analysis.api.javaInterop.namedClassSymbol
import org.jetbrains.kotlin.analysis.api.resolution.resolveSuccessfulSymbol
import org.jetbrains.kotlin.analysis.api.resolution.resolveSuccessfulSymbols
import org.jetbrains.kotlin.analysis.api.symbols.*
import org.jetbrains.kotlin.analysis.api.symbols.KaClassLikeSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaAnnotatedSymbol
import org.jetbrains.kotlin.analysis.api.symbols.markers.KaNamedSymbol
import org.jetbrains.kotlin.analysis.api.types.*
import org.jetbrains.kotlin.lexer.KtTokens
import org.jetbrains.kotlin.load.java.propertyNameByGetMethodName
import org.jetbrains.kotlin.load.java.propertyNameBySetMethodName
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.psi.*
import org.jetbrains.kotlin.psi.psiUtil.endOffset
import org.jetbrains.kotlin.psi.psiUtil.getNonStrictParentOfType
import org.jetbrains.kotlin.psi.psiUtil.quoteIfNeeded
import org.jetbrains.kotlin.psi.psiUtil.startOffset
import org.objectweb.asm.Opcodes
import org.objectweb.asm.tree.ClassNode
import java.util.*
import kotlin.collections.mutableSetOf

internal class PsiMapper(
        private val mappingTree: MappingTreeView,
        private val remappedProject: Project?,
        private val file: PsiFile,
        private val changes: Changes,
) {
    private val aliased = mutableSetOf<String>()

    private fun replaceIdentifier(parent: PsiElement, with: String) {
        var child = parent.firstChild
        while (child != null) {
            if (child is PsiIdentifier
                    || child is ASTNode && child.elementType == KtTokens.IDENTIFIER) {
                changes.replace(child, with)
                return
            }
            child = child.nextSibling
        }
    }

    context(_: KaSession, _: MixinContext)
    private fun mapField(expr: PsiElement, field: KaNamedSymbol) {
        val fieldName = field.name
        val mapped = findFieldMapping(field)?.dstName
        if (mapped == null || mapped == fieldName.asString()) return
        replaceIdentifier(expr, mapped)

        if (expr is PsiJavaCodeReferenceElement
            && !expr.isQualified // qualified access is fine
            && !isSwitchCase(expr) // referencing constants in case statements is fine
        ) {
            changes.error(expr, "Implicit member reference to remapped field \"$fieldName\". " +
                    "This can cause issues if the remapped reference becomes shadowed by a local variable and is therefore forbidden. " +
                    "Use \"this.$fieldName\" instead.")
        }
    }

    context(_: KaSession, mixinContext: MixinContext)
    private fun findFieldMapping(field: KaNamedSymbol): FieldMappingView? {
        val declaringClass = field.containingSymbol as KaClassSymbol
        var classId = declaringClass.classId ?: return null

        val mixinTargetClass = if (mixinContext.isMixinClass(classId)) mixinContext.targetClass else null
        // If this method is declared in a mixin class, we want to consider the hierarchy of the target as well
        if (mixinTargetClass != null) {
            // but only if the field conceptually belongs to the target class
            if (field !is KaAnnotatedSymbol || !field.annotations.contains(ClassId.topLevel(FqName(CLASS_SHADOW)))) {
                return null // otherwise, it belongs to the mixin and never gets remapped
            }

            classId = mixinTargetClass.classId!!
        }

        return mappingTree.getClass(classId)?.getField(field.name.asString(), null)
    }

    context(_: MixinContext)
    private fun mapField(expr: PsiElement, field: PsiField) {
        val fieldName = field.name
        val mapped = findMapping(field)?.dstName
        if (mapped == null || mapped == fieldName) return
        replaceIdentifier(expr, mapped)

        if (expr is PsiJavaCodeReferenceElement
                && !expr.isQualified // qualified access is fine
                && !isSwitchCase(expr) // referencing constants in case statements is fine
        ) {
            changes.error(expr, "Implicit member reference to remapped field \"$fieldName\". " +
                    "This can cause issues if the remapped reference becomes shadowed by a local variable and is therefore forbidden. " +
                    "Use \"this.$fieldName\" instead.")
        }
    }

    context(mixinContext: MixinContext)
    private fun findMapping(field: PsiField): FieldMappingView? {
        val declaringClass = field.containingClass ?: return null
        var classId = declaringClass.classId ?: return null

        val mixinTargetClass = if (mixinContext.isMixinClass(classId)) mixinContext.targetClass else null
        // If this field is declared in a mixin class, we want to consider the hierarchy of the target instead
        if (mixinTargetClass != null) {
            // but only if the field conceptually belongs to the target class
            if (!field.hasAnnotation(CLASS_SHADOW)) {
                return null // otherwise, it belongs to the mixin and never gets remapped
            }

            classId = mixinTargetClass.classId!!
        }

        return mappingTree.getClass(classId)?.getField(field.name, null)
    }

    context(_: KaSession, _: MixinContext)
    private fun mapFunction(expr: PsiElement, symbol: KaNamedFunctionSymbol) {
        val mapping = findMethodMapping(symbol)
        val mapped = mapping?.dstName
        if (mapped != null && mapped != symbol.name.toString()) {
            val maybeGetter = propertyNameByGetMethodName(Name.identifier(mapped))
            if (maybeGetter != null // must have getter-style name
                    && expr.parent is KtCallExpression // must be in the context of a call (as opposed to definition)
                    && symbol.valueParameters.isEmpty() // getters cannot take any arguments
                    && symbol.returnType != builtinTypes.unit // and must return some value
                    && !symbol.isStatic // synthetic properties cannot be static
                    // `super.getDebugInfo()` is a special case which cannot be replaced with a synthetic property
                    && expr.parent.parent.let { it !is KtDotQualifiedExpression || it.firstChild !is KtSuperExpression }
                    // cannot use synthetic properties if there is a field of the same name
                    && !isSyntheticPropertyShadowedByField(maybeGetter.identifier, mapping, expr)
                    // cannot use synthetic properties outside of kotlin files (cause they're a kotlin thing)
                    && expr.containingFile  is KtFile) {
                // E.g. `entity.canUsePortal()` maps to `entity.isNonBoss()` but when we're using kotlin and the target
                // isn't (as should be the case for remapped names), we can also write that as `entity.isNonBoss` (i.e.
                // as a synthetic property).
                // This is the reverse to the operation in [map(PsiElement, SyntheticJavaPropertyDescriptor)].
                changes.replace(expr.parent, maybeGetter.identifier)
                return
            }

            val parent = expr.parent
            if (parent is KtCallExpression) {
                val argumentList = parent.valueArgumentList
                val maybeSetter = getSyntheticPropertyForSetter(expr, symbol, mapping)
                if (maybeSetter != null && argumentList != null) {
                    val leftParen = argumentList.leftParenthesis!!
                    val rightParen = argumentList.rightParenthesis!!
                    changes.replace(TextRange(
                        expr.startOffset,
                        leftParen.endOffset,
                    ), "$maybeSetter =" + if (leftParen.nextSibling is PsiWhiteSpace) "" else " ")
                    changes.replace(rightParen, "")
                    return
                }
            }

            replaceIdentifier(expr, mapped)
        }
    }

    context(_: MixinContext)
    private fun mapMethod(expr: PsiElement, method: PsiMethod) {
        if (method.isConstructor) {
            return
        }

        val mapping = findMapping(method)
        val mapped = mapping?.dstName
        if (mapped != null && mapped != method.name) {
            replaceIdentifier(expr, mapped)
        }
    }

    context(_: KaSession)
    private fun getSyntheticPropertyForSetter(expr: PsiElement, method: KaNamedFunctionSymbol, mapping: MethodMappingView): String? {
        // Check if the setter method qualifies for synthetic property conversion
        if (method.returnType != builtinTypes.unit) return null
        if (method.isStatic) return null
        val parameter = method.valueParameters.singleOrNull() ?: return null
        val type = parameter.returnType.mapToJvmTypeDescriptor()

        // `super.setDebugInfo(value)` is a special case which cannot be replaced with a synthetic property
        if (expr.parent.parent.let { it is KtDotQualifiedExpression && it.firstChild is KtSuperExpression }) {
            return null
        }

        val setterName = mapping.dstName ?: return null

        for (withIsPrefix in listOf(false, true)) {
            if (withIsPrefix && type != "Z") continue // only boolean types may use the `is` prefix
            val propertyName = propertyNameBySetMethodName(Name.identifier(setterName), withIsPrefix) ?: continue
            val property = propertyName.identifier

            val getterName = getterNameByPropertyName(property, withIsPrefix)
            val getterDesc = "()$type"
            mapping.owner.methods.find {
                it.dstName == getterName && it.srcDesc == getterDesc
            } ?: continue

            if (isSyntheticPropertyShadowedByField(property, mapping, expr)) {
                return null
            }

            return property
        }

        return null
    }

    private fun getterNameByPropertyName(propertyName: String, withIsPrefix: Boolean): String =
        if (withIsPrefix) propertyName else "get" + propertyName.replaceFirstChar { it.uppercaseChar() }

    context(_: KaSession, _: MixinContext)
    private fun mapSyntheticJavaProperty(expr: PsiElement, property: KaSyntheticJavaPropertySymbol) {
        val assignment = findAssignment(expr)
        if (assignment != null) {
            mapSetter(expr, assignment, property)
        } else {
            mapGetter(expr, property)
        }
    }

    private fun findAssignment(expr: PsiElement): KtBinaryExpression? {
        val parent = expr.parent
        // Our parent will either be the assignment (`expr = value`) or a qualified expression (`receiver.expr = value`)
        val leftSide = if (parent is KtQualifiedExpression) {
            if (parent.selectorExpression != expr) return null // we are on the wrong side: `expr.selector = value`
            parent
        } else {
            expr
        }
        val assignment = leftSide.parent as? KtBinaryExpression ?: return null // not an assignment after all
        if (assignment.left != leftSide) return null // turns out we are on the right side of the assignment
        if (assignment.operationToken != KtTokens.EQ) return null // not actually an assignment
        return assignment
    }

    context(_: KaSession, mixinContext: MixinContext)
    private fun mapGetter(expr: PsiElement, property: KaSyntheticJavaPropertySymbol) {
        val getter = property.javaGetterSymbol
        val mapping = findMethodMapping(getter)
        val mappedGetter = mapping?.dstName ?: return
        if (mappedGetter != getter.name.asString()) {
            val maybeMapped = propertyNameByGetMethodName(Name.identifier(mappedGetter))
            if (maybeMapped == null || isSyntheticPropertyShadowedByField(maybeMapped.identifier, mapping, expr)) {
                // Can happen if a method is a synthetic property in the current mapping (e.g. `isNonBoss`) but not
                // in the target mapping (e.g. `canUsePortal()`)
                // This is the reverse to the operation in [map(PsiElement, PsiMethod)].
                replaceIdentifier(expr, "$mappedGetter()")
            } else {
                val mapped = maybeMapped.identifier
                replaceIdentifier(expr, mapped)
            }
        }
    }

    context(_: KaSession, mixinContext: MixinContext)
    private fun mapSetter(expr: PsiElement, assignment: KtBinaryExpression, property: KaSyntheticJavaPropertySymbol) {
        val getter = property.javaGetterSymbol
        val setter = property.javaSetterSymbol ?: return
        val mappingGetter = findMethodMapping(getter)
        val mappingSetter = findMethodMapping(setter)
        val mappedGetter = mappingGetter?.dstName // if getter is gone, we need to switch to method invocation
        val mappedSetter = mappingSetter?.dstName ?: return
        if (mappedGetter == null || mappedSetter != setter.name.asString()) {
            val maybeMapped = if (mappedGetter != null) {
                val isBooleanField = mappingGetter.srcDesc?.endsWith("Z") == true
                val withIsPrefix = isBooleanField && mappedGetter.startsWith("is")
                val propertyByGetter = propertyNameByGetMethodName(Name.identifier(mappedGetter))
                val propertyBySetter = propertyNameBySetMethodName(Name.identifier(mappedSetter), withIsPrefix)
                if (propertyByGetter == propertyBySetter) {
                    propertyBySetter
                } else {
                    null // remapped setter does not match remapped getter, use accessor method instead
                }
            } else {
                null
            }
            if (maybeMapped == null || isSyntheticPropertyShadowedByField(maybeMapped.identifier, mappingSetter, expr)) {
                val op = assignment.operationReference
                changes.replace(TextRange(
                    expr.startOffset,
                    // If there is a single whitespace after the operation element, eat it, otherwise don't
                    op.endOffset + if ((op.nextSibling as? PsiWhiteSpace)?.textLength == 1) 1 else 0
                ), "$mappedSetter(")
                val right = assignment.right!!
                changes.replace(TextRange(right.endOffset, right.endOffset), ")")
            } else {
                val mapped = maybeMapped.identifier
                replaceIdentifier(expr, mapped)
            }
        }
    }

    context(_: KaSession, mixinContext: MixinContext)
    private fun findMethodMapping(method: KaNamedFunctionSymbol): MethodMappingView? {
        var declaringClass: KaClassSymbol?
        declaringClass = method.containingDeclaration as? KaClassSymbol ?: return null
        val parentQueue = ArrayDeque<KaClassSymbol>()
        parentQueue.offer(declaringClass)

        val mixinTargetClass = if (mixinContext.isMixinClass(declaringClass)) mixinContext.targetClass else null
        // If this method is declared in a mixin class, we want to consider the hierarchy of the target as well
        if (mixinTargetClass != null) {
            parentQueue.offer(mixinTargetClass.namedClassSymbol!!)
            // but only if the method conceptually belongs to the target class
            val isShadow = method.annotations.contains(ClassId.topLevel(FqName(CLASS_SHADOW)))
            val isOverwrite = method.annotations.contains(ClassId.topLevel(FqName(CLASS_OVERWRITE)))
            val isOverride = method.annotations.contains(ClassId.topLevel(FqName(CLASS_OVERRIDE)))
            if (!isShadow && !isOverwrite && !isOverride) {
                return null // otherwise, it belongs to the mixin and never gets remapped
            }
        }

        val signatures = mutableSetOf<MethodSignature>()
        signatures.add(getSignature(method))
        // If the method overrides a method with generic parameters or return type, we need to look for the signature
        // of the overridden method as well.
        // E.g. the overridden method may accept a generic `T` (where `T` is generic at the class level, with `T : Any`)
        // and as such has `java/lang/Object` in its signature, while the overriding class may specify `T` as `MyObject`
        // and therefore its overriding method is specialized to accept MyObj and as such has `MyObj` in its signature
        // (and Java would generate a bridge method to do the actual overriding, but we don't see that one here).
        method.allOverriddenSymbols.flatMapTo(signatures) { sym ->
            when (sym) {
                is KaNamedFunctionSymbol -> listOf(getSignature(sym))
                is KaKotlinPropertySymbol -> {
                    val name = method.name.asString()
                    val getter = sym.getter
                    val setter = sym.setter
                    listOfNotNull(
                        if (getter != null && getter.javaMethodName == method.name.asString()) getSignature(name, getter) else null,
                        if (setter != null && setter.javaMethodName == method.name.asString()) getSignature(name, setter) else null,
                    )
                }
                else -> throw UnsupportedOperationException("$sym")
            }
        }

        if (signatures.isEmpty()) {
            return null
        }

        var mapping: ClassMappingView? = null
        while (true) {
            if (mapping != null) {
                val mapped = signatures.firstNotNullOfOrNull { mapping.getMethod(it.name, it.desc) }
                if (mapped != null) {
                    return mapped
                }
                mapping = null
            }
            while (mapping == null) {
                declaringClass = parentQueue.poll()
                if (declaringClass == null) return null

                for (superType in declaringClass.superTypes) {
                    parentQueue.offer(superType.symbol as? KaNamedClassSymbol ?: continue)
                }

                mapping = mappingTree.getClass(declaringClass.classId ?: continue)
            }
        }
    }

    context(mixinContext: MixinContext)
    private fun findMapping(method: PsiMethod): MethodMappingView? {
        var declaringClass: PsiClass? = method.containingClass ?: return null
        val parentQueue = ArrayDeque<PsiClass>()
        parentQueue.offer(declaringClass)

        val classId = declaringClass!!.classId
        val mixinTargetClass = if (classId != null && mixinContext.isMixinClass(classId)) mixinContext.targetClass else null
        // If this method is declared in a mixin class, we want to consider the hierarchy of the target as well
        if (mixinTargetClass != null) {
            parentQueue.offer(mixinTargetClass)
            // but only if the method conceptually belongs to the target class
            val isShadow = method.getAnnotation(CLASS_SHADOW) != null
            val isOverwrite = method.getAnnotation(CLASS_OVERWRITE) != null
            val isOverride = method.getAnnotation(CLASS_OVERRIDE) != null
            if (!isShadow && !isOverwrite && !isOverride) {
                return null // otherwise, it belongs to the mixin and never gets remapped
            }
        }

        val signatures = mutableSetOf<MethodSignature>()
        signatures.add(getSignature(method))
        // If the method overrides a method with generic parameters or return type, we need to look for the signature
        // of the overridden method as well.
        // E.g. the overridden method may accept a generic `T` (where `T` is generic at the class level, with `T : Any`)
        // and as such has `java/lang/Object` in its signature, while the overriding class may specify `T` as `MyObject`
        // and therefore its overriding method is specialized to accept MyObj and as such has `MyObj` in its signature
        // (and Java would generate a bridge method to do the actual overriding, but we don't see that one here).
        method.findSuperMethods().mapTo(signatures) { getSignature(it) }

        if (signatures.isEmpty()) {
            return null
        }

        var mapping: ClassMappingView? = null
        while (true) {
            if (mapping != null) {
                val mapped = signatures.firstNotNullOfOrNull { mapping.getMethod(it.name, it.desc) }
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

                mapping = mappingTree.getClass(declaringClass.classId ?: continue)
            }
        }
    }

    private fun findExactMapping(declaringClass: PsiClass, name: String, desc: String): MethodMappingView? {
        val parentQueue = ArrayDeque<PsiClass>()
        parentQueue.offer(declaringClass)

        while (true) {
            val currentClass = parentQueue.poll() ?: return null

            val mapping = currentClass.classId?.let { mappingTree.getClass(it) }
            if (mapping != null) {
                val mapped = mapping.getMethod(name, desc)
                if (mapped != null) {
                    return mapped
                }
            }

            val superClass = currentClass.superClass
            if (superClass != null) {
                parentQueue.offer(superClass)
            }
            for (anInterface in currentClass.interfaces) {
                parentQueue.offer(anInterface)
            }
        }
    }

    private fun mapClassLike(expr: PsiElement, classId: ClassId) {
        val mapping = mappingTree.getClass(classId) ?: return
        val mapped = mapping.dstClassId
        if (mapped == classId) return

        // Fully qualified, obvious case
        if (expr.text == classId.asFqNameString()) {
            changes.replace(expr, mapped.asFqNameString())
            return
        }

        val qualifiedExpr = when {
            // Java
            expr is PsiJavaCodeReferenceElement -> expr.referenceNameElement
            // Kotlin
            else -> expr
        }
        val parent: PsiElement? = expr.parent
        val parentParent: PsiElement? = parent?.parent
        val qualifierExpr = when {
            // Java
            expr is PsiJavaCodeReferenceElement -> expr.qualifier
            // Kotlin
            // In types
            parent is KtUserType && parent.qualifier != expr -> parent.qualifier
            // In general code
            parent is KtQualifiedExpression && parent.receiverExpression != expr -> parent.receiverExpression
            // In general code when calling the constructor
            parent is KtCallExpression && parentParent is KtDotQualifiedExpression
                && parentParent.receiverExpression != parent -> parentParent.receiverExpression
            else -> null
        }

        // Fully qualified, tricky cases
        val simpleName = classId.shortClassName.asString()
        val qualifierName = classId.asFqNameString().substringBeforeLast(".")
        if (qualifiedExpr?.text == simpleName && qualifierExpr?.text == qualifierName) {
            if (changes.isUnchanged(qualifierExpr)) {
                changes.replace(qualifierExpr, mapped.asFqNameString().substringBeforeLast("."))
            }
            changes.replace(qualifiedExpr, mapped.shortClassName.asString())
            return
        }

        if (qualifierExpr == null && expr.text in aliased) {
            return
        }

        // FIXME this incorrectly filters things like "Packet<?>" and doesn't filter same-name type aliases
        // if (expr.text != name.substring(name.lastIndexOf('.') + 1)) {
        //     return // type alias, will be remapped at its definition
        // }
        replaceIdentifier(expr, mapped.shortClassName.asString())
    }

    context(_: MixinContext)
    private fun map(expr: PsiElement, resolved: PsiElement?) {
        when (resolved) {
            is PsiField -> mapField(expr, resolved)
            is PsiMethod -> mapMethod(expr, resolved)
            is PsiClass -> mapClassLike(expr, resolved.classId ?: return)
        }
    }

    context(_: MixinContext)
    private fun findMappedName(element: PsiElement): String? {
        return when (element) {
            is PsiField -> findMapping(element)?.dstName
            is PsiMethod -> findMapping(element)?.dstName
            else -> null
        }
    }

    context(_: KaSession, _: MixinContext)
    private fun map(expr: PsiElement, symbol: KaSymbol) {
        when (symbol) {
            is KaPackageSymbol -> {}
            is KaClassLikeSymbol -> mapClassLike(expr, symbol.classId ?: return)
            is KaConstructorSymbol -> mapClassLike(expr, (symbol.containingSymbol as KaClassLikeSymbol).classId ?: return)
            is KaSamConstructorSymbol -> if (symbol.returnType.abbreviation == null) mapClassLike(expr, symbol.functionalInterface.classId ?: return)
            is KaNamedFunctionSymbol -> mapFunction(expr, symbol)
            is KaSyntheticJavaPropertySymbol -> mapSyntheticJavaProperty(expr, symbol)
            is KaKotlinPropertySymbol -> {}
            is KaJavaFieldSymbol -> mapField(expr, symbol)
            is KaEnumEntrySymbol -> mapField(expr, symbol)
            is KaParameterSymbol -> {}
            is KaTypeParameterSymbol -> {}
            is KaLocalVariableSymbol -> {}
            is KaAnonymousFunctionSymbol -> {}
            is KaBackingFieldSymbol -> {}
            else -> throw UnsupportedOperationException("$symbol at `${expr.text.quoteIfNeeded()}`")
        }
    }

    context(_: KaSession, _: MixinContext)
    private fun findMappedName(symbol: KaSymbol): String? {
        return when (symbol) {
            is KaPackageSymbol -> null
            is KaClassLikeSymbol -> null
            is KaConstructorSymbol -> null
            is KaNamedFunctionSymbol -> findMethodMapping(symbol)?.dstName
            is KaSyntheticJavaPropertySymbol -> null
            is KaKotlinPropertySymbol -> null
            is KaJavaFieldSymbol -> findFieldMapping(symbol)?.dstName
            is KaEnumEntrySymbol -> findFieldMapping(symbol)?.dstName
            is KaParameterSymbol -> null
            is KaTypeParameterSymbol -> null
            is KaLocalVariableSymbol -> null
            is KaAnonymousFunctionSymbol -> null
            is KaBackingFieldSymbol -> null
            else -> throw UnsupportedOperationException("$symbol")
        }
    }

    context(_: KaSession)
    private fun isSyntheticPropertyShadowedByField(propertyName: String, mapping: MethodMappingView, expr: PsiElement): Boolean {
        val cls = findPsiClass(mapping.owner.dstClassId, remappedProject ?: file.project) ?: return false
        val field = cls.findFieldByName(propertyName, true) ?: return false

        val exprClass = expr.getNonStrictParentOfType<KtClassOrObject>()?.classSymbol
        val fieldClass = mapping.owner.srcClassId
        val canAccessProtected = exprClass != null && exprClass.defaultType.isSubtypeOf(fieldClass)

        return field.hasModifier(JvmModifier.PUBLIC) || (canAccessProtected && field.hasModifier(JvmModifier.PROTECTED))
    }

    // Note: Supports only Mixins with a single target (ignores others)
    private fun mapMixinTarget(annotation: PsiAnnotation): Pair<PsiClass, PsiElement>? {
        for (pair in annotation.parameterList.attributes) {
            val name = pair.name
            if (name == null || "value" == name) {
                val value = pair.value
                if (value !is PsiClassObjectAccessExpression) continue
                val type = value.operand
                val reference = type.innermostComponentReferenceElement ?: continue
                val psiClass = reference.resolve() as PsiClass? ?: continue
                return Pair(psiClass, value)
            }
            if ("targets" == name) {
                val value = pair.value
                if (value !is PsiLiteral) continue
                val specifiedName = value.value as? String ?: continue
                val psiClass = possibleClassIdsForMixinString(specifiedName)
                    .firstNotNullOfOrNull { findPsiClass(it, file.project) }
                    ?: continue
                val mapping = mappingTree.getClass(psiClass.classId ?: continue)
                val mapped = mapping?.dstName?.replace('/', '.')
                if (mapped != null && mapped != specifiedName) {
                    changes.replace(value, "\"$mapped\"")
                }
                return Pair(psiClass, value)
            }
        }
        return null
    }

    context(mixinContext: MixinContext)
    private fun mapMixinAccessor(method: PsiMethod) {
        val accessorAnnotation = method.getAnnotation(CLASS_ACCESSOR)
        val invokerAnnotation = method.getAnnotation(CLASS_INVOKER)
        val annotation = accessorAnnotation ?: invokerAnnotation ?: return

        val methodName = method.name
        val targetByName = when {
            methodName.startsWith("invoke") -> methodName.substring(6)
            methodName.startsWith("call") -> methodName.substring(4)
            methodName.startsWith("is") -> methodName.substring(2)
            methodName.startsWith("get") || methodName.startsWith("set") -> methodName.substring(3)
            else -> null
        }?.replaceFirstChar { it.lowercase() }

        val target = annotation.parameterList.attributes.find {
            it.name == null || it.name == "value"
        }?.literalValue ?: targetByName ?: throw IllegalArgumentException("Cannot determine accessor target for $method")

        val mapped = if (invokerAnnotation != null) {
            val targetClass = mixinContext.targetClass ?: return
            findExactMapping(targetClass, target, ClassUtil.getAsmMethodSignature(method))?.dstName
        } else {
            val mapping = mixinContext.targetClass?.classId?.let { mappingTree.getClass(it) }
            mapping?.getField(target, null)?.dstName
        }
        if (mapped != null && mapped != target) {
            // Update accessor target
            changes.replace(annotation.parameterList, if (mapped == targetByName) {
                // Mapped name matches implied target, can just remove the explict target
                ""
            } else {
                // Mapped name does not match implied target, need to set the target as annotation value
                "(\"" + StringUtil.escapeStringCharacters(mapped) + "\")"
            })
        }
    }

    context(_: MixinContext)
    private fun mapMixinMethodTarget(method: PsiMethod) {
        val methodAttrib = method.annotations.firstNotNullOfOrNull { annotation ->
            if (annotation.qualifiedName == CLASS_MIXIN_EXTRAS_DEFINITION) return@firstNotNullOfOrNull null
            annotation.findDeclaredAttributeValue("method")
        }
        for ((literalExpr, literalValue) in methodAttrib?.resolvedLiteralValues ?: emptyList()) {
            mapMixinMethodTarget(literalExpr, literalValue)
        }
    }

    context(mixinContext: MixinContext)
    private fun mapMixinMethodTarget(literalExpr: PsiLiteralExpression, literalValue: String) {
        val (targetName, targetDesc) = if ('(' in literalValue) {
            val split = literalValue.indexOf('(')
            literalValue.substring(0, split) to literalValue.substring(split)
        } else {
            literalValue to null
        }
        val mapped = mapMixinMethodTarget(targetName, targetDesc, literalExpr)
        if (mapped != null && mapped != literalValue && changes.isUnchanged(literalExpr)) {
            changes.replace(literalExpr, '"'.toString() + mapped + '"'.toString())
        }
    }

    context(mixinContext: MixinContext)
    private fun mapMixinMethodTarget(
        targetClassNode: ClassNode,
        targetName: String,
        targetDesc: String?,
        expr: PsiLiteralExpression,
    ): String? {
        val targetClass = mixinContext.targetClass ?: return null
        val targetMethods = targetClassNode.methods.filter { it.name == targetName }

        val targetMethod = if (targetDesc != null) {
            targetMethods.find { it.desc == targetDesc }
        } else {
            if (targetMethods.size > 1) {
                changes.error(expr,
                    "Ambiguous mixin method \"$targetName\" may refer to any of: " +
                            targetMethods.joinToString { "\"${it.name}${it.desc}\"" })
            }
            targetMethods.firstOrNull()
        }

        val mappedName = targetMethod?.let { findExactMapping(targetClass, it.name, it.desc) }?.dstName ?: targetName

        val remappedTargetClassNode = mixinContext.remappedTargetClassAsm!!
        val mappedMethods = remappedTargetClassNode.methods.filter { it.name == mappedName }
        val matchingMethods =
            if (targetMethod == null) mappedMethods
            else mappedMethods.filter { it.access and Opcodes.ACC_SYNTHETIC == targetMethod.access and Opcodes.ACC_SYNTHETIC }

        return mappedName + when {
            // If we can't find the mapped target method, it might be added by a third-party patch
            // at runtime (or the mapping is missing).
            // To be safe, we'll use the full  descriptor if we have one, so we don't inject
            // into the wrong thing.
            mappedMethods.isEmpty() -> (targetMethod?.desc ?: targetDesc)?.let { remapMethodDesc(it) } ?: ""
            // If there's exactly one such method, the descriptor can be omitted.
            mappedMethods.size == 1 -> ""
            // If we have multiple such methods, but only one is a good match,
            // we need to include its descriptor.
            matchingMethods.size == 1 -> matchingMethods.single().desc
            // If there's multiple such methods, we need to include the descriptor.
            else -> {
                val mappedDesc = (targetMethod?.desc ?: targetDesc)?.let { remapMethodDesc(it) }
                if (mappedMethods.none { it.desc == mappedDesc }) {
                    changes.error(
                        expr,
                        "Mixin target \"$targetName\" cannot be automatically remapped " +
                                "as there are multiple methods with the same remapped name: " +
                                mappedMethods.joinToString { "\"${it.name}${it.desc}\"" })
                }
                mappedDesc ?: ""
            }
        }
    }

    context(mixinContext: MixinContext)
    private fun mapMixinMethodTarget(
        targetName: String,
        targetDesc: String?,
        expr: PsiLiteralExpression,
    ): String? {
        // Prefer using the ASM-based method.
        // The fallback below won't work for synthetic methods as those aren't visible at the PSI level.
        val targetClassNode = mixinContext.targetClassAsm
        if (targetClassNode != null) {
            return mapMixinMethodTarget(targetClassNode, targetName, targetDesc, expr)
        }

        val targetClass = mixinContext.targetClass ?: return null
        val targetMethods = targetClass.findMethodsByName(targetName, false)
        val targetMethod = if (targetDesc != null) {
            targetMethods.find {
                ClassUtil.getAsmMethodSignature(it) == targetDesc
            }
        } else {
            if (targetMethods.size > 1) {
                changes.error(expr, "Ambiguous mixin method \"$targetName\" may refer to any of: ${targetMethods.joinToString {
                    "\"${it.name}${ClassUtil.getAsmMethodSignature(it)}\""
                }}")
            }
            targetMethods.firstOrNull()
        }
        val mappedName = targetMethod?.let { findMapping(it) }?.dstName ?: targetName

        val mapping = targetClass.classId?.let { mappingTree.getClass(it) }
        val ambiguousName = (mapping?.methods?.count { it.dstName == mappedName } ?: 0) > 1
        return mappedName + when {
            ambiguousName && targetMethod != null ->
                remapMethodDesc(ClassUtil.getAsmMethodSignature(targetMethod))
            targetDesc != null -> remapMethodDesc(targetDesc)
            else -> ""
        }
    }

    private fun remapInternalType(internalType: String): String =
            StringBuilder().apply { remapInternalType(internalType, this) }.toString()

    private fun remapInternalType(internalType: String, result: StringBuilder): ClassMappingView? {
        if (internalType[0] == 'L') {
            val type = ClassId.fromString(internalType.substring(1, internalType.length - 1).replace('$', '.'))
            val mapping = mappingTree.getClass(type)
            if (mapping != null) {
                result.append('L').append(mapping.dstName).append(';')
                return mapping
            }
        }
        result.append(internalType)
        return null
    }

    context(_: MixinContext)
    private fun remapMixinTarget(target: String): String {
        return when {
            target.startsWith('(') -> remapMethodDesc(target)
            target.contains(':') || target.contains('(') -> remapFullyQualifiedMethodOrField(target)
            target[0] == 'L' -> remapInternalType(target)
            else -> remapInternalType("L$target;").drop(1).dropLast(1)
        }
    }

    context(mixinContext: MixinContext)
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

        val ownerPsi = findPsiClass(ClassId.fromString(owner.drop(1).dropLast(1).replace('$', '.')), file.project)
        val methodPsi = if (method) {
            val desc = signature.substring(argsBegin)
            ownerPsi?.findMethodsByName(name, true)?.find { ClassUtil.getAsmMethodSignature(it) == desc }
        } else {
            null
        }
        val fieldPsi = if (method) {
            null
        } else {
            generateSequence(ownerPsi) { it.superClass }
                .firstNotNullOfOrNull { psiClass ->
                    val canAccessPrivateFields = psiClass == ownerPsi && psiClass == mixinContext.targetClass
                    psiClass.fields.find { psiField ->
                        psiField.name == name && (canAccessPrivateFields || !psiField.hasModifier(JvmModifier.PRIVATE))
                    }
                }
        }

        val builder = StringBuilder(signature.length + 32)
        val mapping = remapInternalType(owner, builder)
        var mapped: String? = null
        if (methodPsi != null) {
            mapped = findMapping(methodPsi)?.dstName
        }
        if (fieldPsi != null) {
            val mapping = fieldPsi.containingClass?.classId?.let { mappingTree.getClass(it) }
            if (mapping != null) {
                mapped = mapping.getField(name, null)?.dstName
            }
        }
        if (mapped == null && mapping != null) {
            mapped = (if (method) {
                mapping.getMethod(name, signature.substring(argsBegin))
            } else {
                mapping.getField(name, null)
            })?.dstName
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

    context(_: MixinContext)
    private fun remapMethodDesc(desc: String): String =
        remapFullyQualifiedMethodOrField("Ldummy;dummy$desc").dropWhile { it != '(' }

    context(_: MixinContext)
    private fun remapAtTarget(annotation: PsiAnnotation) {
        for (attribute in annotation.parameterList.attributes) {
            if ("target" != attribute.name) continue
            val (value, signature) = attribute.resolvedLiteralValue ?: continue
            val newSignature = remapMixinTarget(signature)
            if (newSignature != signature && changes.isUnchanged(value)) {
                changes.replace(value, "\"$newSignature\"")
            }
        }
    }

    context(_: MixinContext)
    private fun remapMixinExtrasDefinition(annotation: PsiAnnotation) {
        for (attribute in annotation.parameterList.attributes) {
            when (attribute.name) {
                "field", "method" -> {
                    val (value, signature) = attribute.resolvedLiteralValue ?: continue
                    val newSignature = remapMixinTarget(signature)
                    if (newSignature != signature && changes.isUnchanged(value)) {
                        changes.replace(value, "\"$newSignature\"")
                    }
                }
                else -> {}
            }
        }
    }

    private val allDirectJavaReferences by lazy {
        buildSet {
            file.accept(object : JavaRecursiveElementVisitor() {
                override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                    val target = reference.resolve()
                    if (target != null) {
                        add(target)
                    }
                    return super.visitReferenceElement(reference)
                }
            })
        }
    }

    context(_: KaSession)
    fun remapFile() {
        if (file is KtFile) {
            // FIXME we don't currently support Kotlin mixins
            context(MixinContext.EMPTY) {
                remapKtFile()
            }
        } else {
            remapJavaFile()
        }
    }

    private fun remapJavaFile() {
        file.accept(object : JavaRecursiveElementVisitor() {
            private var mixinContextOrNull: MixinContext? = null
            private val mixinContext: MixinContext
                get() = mixinContextOrNull ?: MixinContext.EMPTY

            override fun visitClass(psiClass: PsiClass) {
                val mixinAnnotation = psiClass.getAnnotation(CLASS_MIXIN)
                val mixinContext = mixinAnnotation?.let {
                    mapMixinTarget(mixinAnnotation)
                }?.let { (targetClass, targetExpr) ->
                    val targetClassNode = targetClass.getAsmTree()
                    val targetClassId = targetClass.classId
                    val mapping = targetClassId?.let { mappingTree.getClass(it) }
                    val remappedClassId = mapping?.dstClassId ?: targetClassId
                    val remappedTargetClass = remappedClassId?.let { findPsiClass(it, remappedProject ?: file.project) }
                    var remappedTargetClassNode = remappedTargetClass?.getAsmTree()
                    if (targetClassNode != null && remappedTargetClassNode == null) {
                        changes.error(targetExpr, "Failed to find bytecode for $remappedClassId in remapped classpath.")
                        remappedTargetClassNode = ClassNode()
                    }
                    MixinContext(
                        psiClass,
                        targetClass,
                        targetClassNode,
                        remappedTargetClass,
                        remappedTargetClassNode,
                    )
                }

                if (mixinContext != null) {
                    check(mixinContextOrNull == null) { "Nested mixin classes are not supported." }
                    mixinContextOrNull = mixinContext
                    super.visitClass(psiClass)
                    mixinContextOrNull = null
                } else {
                    super.visitClass(psiClass)
                }
            }

            override fun visitAnnotation(annotation: PsiAnnotation) {
                val name = annotation.qualifiedName
                context(mixinContext) {
                    when (name) {
                        CLASS_AT -> remapAtTarget(annotation)
                        CLASS_MIXIN_EXTRAS_DEFINITION -> remapMixinExtrasDefinition(annotation)
                    }
                }
                super.visitAnnotation(annotation)
            }

            override fun visitField(field: PsiField) {
                if (changes.isUnchanged(field)) {
                    context(mixinContext) {
                        mapField(field, field)
                    }
                }
                super.visitField(field)
            }

            override fun visitMethod(method: PsiMethod) {
                context(mixinContext) {
                    mapMixinAccessor(method)
                    mapMixinMethodTarget(method)

                    if (changes.isUnchanged(method)) {
                        mapMethod(method, method)
                    }
                }
                super.visitMethod(method)
            }

            override fun visitReferenceElement(reference: PsiJavaCodeReferenceElement) {
                if (changes.isUnchanged(reference)) {
                    context(mixinContext) {
                        map(reference, reference.resolve())
                    }
                }
                super.visitReferenceElement(reference)
            }

            override fun visitImportStaticReferenceElement(reference: PsiImportStaticReferenceElement) {
                if (changes.isUnchanged(reference)) {
                    var resolved = reference.resolve()
                    if (resolved == null) {
                        val possibleTargets = reference.multiResolve(false)
                            .mapNotNull { if (it.isValidResult) it.element else null }
                        if (!possibleTargets.isEmpty()) {
                            val usedTargets = possibleTargets.filter { it in allDirectJavaReferences }
                            val targets = usedTargets.mapNotNull { psi ->
                                val mapped = context(mixinContext) { findMappedName(psi) } ?: return@mapNotNull null
                                Pair(psi, mapped)
                            }
                            val mappedNames = targets.mapTo(mutableSetOf()) { it.second }
                            if (mappedNames.size > 1) {
                                changes.error(reference, "\"${reference.text}\" is ambiguous and remaps to " +
                                        "multiple different names: " + mappedNames.joinToString { "\"${it}\"" })
                            } else if (mappedNames.size == 1) {
                                resolved = targets.first().first
                            }
                        }
                    }
                    context(mixinContext) {
                        map(reference, resolved)
                    }
                }
                super.visitImportStaticReferenceElement(reference)
            }
        })
    }

    context(_: KaSession, _: MixinContext)
    private fun remapKtFile() {
        require(file is KtFile)

        for (importDirective in file.importDirectives) {
            val alias = importDirective.aliasName
            if (alias != null) {
                aliased.add(alias)
            }
        }

        val allDirectKtReferences by lazy {
            buildSet {
                file.accept(object : KtTreeVisitor<Void?>() {
                    override fun visitReferenceExpression(expression: KtReferenceExpression, data: Void?): Void? {
                        val symbol = expression.resolveSuccessfulSymbol()
                        if (symbol != null) {
                            add(symbol)
                        }
                        return super.visitReferenceExpression(expression, data)
                    }
                })
            }
        }

        file.accept(object : KtTreeVisitor<Void>() {
            override fun visitNamedFunction(function: KtNamedFunction, data: Void?): Void? {
                if (changes.isUnchanged(function)) {
                    map(function, function.symbol)
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
                if (changes.isUnchanged(expression)) {
                    var symbol = expression.resolveSuccessfulSymbol()
                    if (symbol == null) {
                        val possibleTargets = expression.resolveSuccessfulSymbols()
                        if (!possibleTargets.isEmpty()) {
                            val usedTargets = possibleTargets.filter { it in allDirectKtReferences }
                            val targets = usedTargets.mapNotNull { symbol ->
                                val mapped = findMappedName(symbol) ?: return@mapNotNull null
                                Pair(symbol, mapped)
                            }
                            val mappedNames = targets.mapTo(mutableSetOf()) { it.second }
                            if (mappedNames.size > 1) {
                                changes.error(expression, "\"${expression.text}\" is ambiguous and remaps to " +
                                        "multiple different names: " + mappedNames.joinToString { "\"${it}\"" })
                            } else if (mappedNames.size == 1) {
                                symbol = targets.first().first
                            }
                        }
                    }
                    if (symbol != null) {
                        map(expression, symbol)
                    }
                }
                return super.visitReferenceExpression(expression, data)
            }
        }, null)
    }

    companion object {
        private const val CLASS_MIXIN = "org.spongepowered.asm.mixin.Mixin"
        private const val CLASS_SHADOW = "org.spongepowered.asm.mixin.Shadow"
        private const val CLASS_OVERWRITE = "org.spongepowered.asm.mixin.Overwrite"
        private const val CLASS_ACCESSOR = "org.spongepowered.asm.mixin.gen.Accessor"
        private const val CLASS_INVOKER = "org.spongepowered.asm.mixin.gen.Invoker"
        private const val CLASS_AT = "org.spongepowered.asm.mixin.injection.At"
        private const val CLASS_MIXIN_EXTRAS_DEFINITION = "com.llamalad7.mixinextras.expression.Definition"
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

private class MixinContext(
    val mixinClass: PsiClass?,
    val targetClass: PsiClass?,
    val targetClassAsm: ClassNode?,
    val remappedTargetClass: PsiClass?,
    val remappedTargetClassAsm: ClassNode?,
) {
    private val mixinClassId = mixinClass?.classId

    fun isMixinClass(symbol: KaClassSymbol) = symbol.classId.let { it != null && isMixinClass(it) }
    fun isMixinClass(classId: ClassId) = mixinClassId == classId

    companion object {
        val EMPTY = MixinContext(null, null, null, null, null)
    }
}
