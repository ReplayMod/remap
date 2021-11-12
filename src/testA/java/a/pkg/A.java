package a.pkg;

public class A extends AParent implements AInterface {
    private A a;
    private int aField;

    public A() {
    }

    public A(A arg) {
    }

    public static A create() { return new A(); }

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
        new A() {};
    }

    public class Inner {
        private int aField;
    }

    public class InnerA {
    }

    public class GenericA<T> {}
}
