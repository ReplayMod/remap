package a.pkg;

import java.util.function.Function;
import java.util.function.Supplier;

public class A extends AParent implements AInterface {
    private A a;
    public A aPub;
    public static A aPubStatic;
    private int aField;

    public A() {
    }

    public A(A arg) {
    }

    public static A create() { return new A(); }
    public static A createA() { return new A(); }

    public void aMethod() {
        aInterfaceMethod();
    }

    public A getA() {
        return this;
    }

    public A getSyntheticA() {
        return this;
    }

    public void setSyntheticA(A arg) {
    }

    public boolean isSyntheticBooleanA() {
        return false;
    }

    public void setSyntheticBooleanA(boolean arg) {
    }

    public A getNonSyntheticA() {
        return this;
    }

    public void setNonSyntheticA(A arg) {
    }

    public boolean isNonSyntheticBooleanA() {
        return false;
    }

    public void setNonSyntheticBooleanA(boolean arg) {
    }

    public A getterA() {
        return this;
    }

    public void setterA(A arg) {
    }

    public boolean getterBooleanA() {
        return false;
    }

    public void setterBooleanA(boolean arg) {
    }

    public int conflictingField;
    public int getConflictingFieldWithoutConflict() {
        return conflictingField;
    }

    protected int protectedField;
    public int getProtectedFieldWithoutConflict() {
        return protectedField;
    }

    public void aOverloaded() {
    }

    public void aOverloaded(int arg) {
    }

    public void aOverloaded(boolean arg) {
    }

    public void commonOverloaded(Object arg) {
    }

    public void commonOverloaded(A arg) {
    }

    public void unmappedOverloaded(Object arg) {
    }

    public void unmappedOverloaded(A arg) {
    }

    @Override
    public void aInterfaceMethod() {
        A aLocal = getA();
        new A() {
            private int aAnonField;

            @Override
            public A getA() {
                return aLocal;
            }
        };
    }

    @Override
    public A aSpecializableMethod() {
        return this;
    }

    @Override
    public A aSpecializableMethodWithChangingSignature() {
        return this;
    }

    public static A aStaticOverload() { return create(); }
    public static A aStaticOverload(int i) { return create(); }

    public static A aAmbiguousMethod() { return create(); }
    public static A aAmbiguousMethod(int i) { return create(); }

    public static void supplier(Supplier<String> supplier) {
    }

    public static void function(Function<String, String> func) {
    }

    public class Inner {
        private int aField;
    }

    public static class NestedA extends A {
    }

    public static class GenericA<T> {
        public void aMethod(T t) {}
    }
}
