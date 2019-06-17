package com.replaymod.gradle.remap;

import com.intellij.psi.PsiArrayType;
import com.intellij.psi.PsiClass;
import com.intellij.psi.PsiClassType;
import com.intellij.psi.PsiMethod;
import com.intellij.psi.PsiPrimitiveType;
import com.intellij.psi.PsiType;
import com.intellij.psi.util.TypeConversionUtil;
import org.cadixdev.bombe.type.ArrayType;
import org.cadixdev.bombe.type.FieldType;
import org.cadixdev.bombe.type.MethodDescriptor;
import org.cadixdev.bombe.type.ObjectType;
import org.cadixdev.bombe.type.Type;
import org.cadixdev.bombe.type.VoidType;
import org.cadixdev.bombe.type.signature.MethodSignature;

import java.util.Arrays;
import java.util.stream.Collectors;

class PsiUtils {
    static MethodSignature getSignature(PsiMethod method) {
        return new MethodSignature(method.getName(), getDescriptor(method));
    }

    private static MethodDescriptor getDescriptor(PsiMethod method) {
        return new MethodDescriptor(
                Arrays.stream(method.getParameterList().getParameters())
                        .map(it -> getFieldType(it.getType()))
                        .collect(Collectors.toList()),
                getType(method.getReturnType())
        );
    }

    private static FieldType getFieldType(PsiType type) {
        type = TypeConversionUtil.erasure(type);
        if (type instanceof PsiPrimitiveType) {
            return FieldType.of(((PsiPrimitiveType) type).getKind().getBinaryName());
        } else if (type instanceof PsiArrayType) {
            PsiArrayType array = (PsiArrayType) type;
            return new ArrayType(array.getArrayDimensions(), getFieldType(array.getDeepComponentType()));
        } else if (type instanceof PsiClassType) {
            PsiClass resolved = ((PsiClassType) type).resolve();
            if (resolved == null) throw new NullPointerException("Failed to resolve type " + type);
            String qualifiedName = resolved.getQualifiedName();
            if (qualifiedName == null) throw new NullPointerException("Type " + type + " has no qualified name.");
            return new ObjectType(qualifiedName);
        } else {
            throw new IllegalArgumentException("Cannot translate type " + type);
        }
    }

    private static Type getType(PsiType type) {
        if (TypeConversionUtil.isVoidType(type)) {
            return VoidType.INSTANCE;
        } else {
            return getFieldType(type);
        }
    }

}
