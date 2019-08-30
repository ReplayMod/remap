package com.replaymod.gradle.remap

import com.intellij.psi.*
import com.intellij.psi.util.TypeConversionUtil
import org.cadixdev.bombe.type.ArrayType
import org.cadixdev.bombe.type.FieldType
import org.cadixdev.bombe.type.MethodDescriptor
import org.cadixdev.bombe.type.ObjectType
import org.cadixdev.bombe.type.Type
import org.cadixdev.bombe.type.VoidType
import org.cadixdev.bombe.type.signature.MethodSignature

internal object PsiUtils {
    fun getSignature(method: PsiMethod): MethodSignature = MethodSignature(method.name, getDescriptor(method))

    private fun getDescriptor(method: PsiMethod): MethodDescriptor = MethodDescriptor(
            method.parameterList.parameters.map { getFieldType(it.type) },
            getType(method.returnType)
    )

    private fun getFieldType(type: PsiType?): FieldType = when (val erasedType = TypeConversionUtil.erasure(type)) {
        is PsiPrimitiveType -> FieldType.of(erasedType.kind.binaryName)
        is PsiArrayType -> {
            val array = erasedType as PsiArrayType?
            ArrayType(array!!.arrayDimensions, getFieldType(array.deepComponentType))
        }
        is PsiClassType -> {
            val resolved = erasedType.resolve() ?: throw NullPointerException("Failed to resolve type $erasedType")
            val qualifiedName = resolved.qualifiedName
                    ?: throw NullPointerException("Type $erasedType has no qualified name.")
            ObjectType(qualifiedName)
        }
        else -> throw IllegalArgumentException("Cannot translate type " + erasedType!!)
    }

    private fun getType(type: PsiType?): Type = if (TypeConversionUtil.isVoidType(type)) {
        VoidType.INSTANCE
    } else {
        getFieldType(type)
    }
}
