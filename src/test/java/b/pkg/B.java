package b.pkg;

public class B extends BParent implements BInterface {
    private B b;
    private int bField;

    public B() {
    }

    public B(B arg) {
    }

    public static B create() { return new B(); }

    public void bMethod() {
        bInterfaceMethod();
    }

    public B getB() {
        return this;
    }

    public B getSyntheticB() {
        return this;
    }

    public void setSyntheticB(B arg) {
    }

    public boolean isSyntheticBooleanB() {
        return false;
    }

    public void setSyntheticBooleanB(boolean arg) {
    }

    public B getNonSyntheticB() {
        return this;
    }

    public void setNonSyntheticB(B arg) {
    }

    public boolean isNonSyntheticBooleanB() {
        return false;
    }

    public void setNonSyntheticBooleanB(boolean arg) {
    }

    public B getterB() {
        return this;
    }

    public void setterB(B arg) {
    }

    public boolean getterBooleanB() {
        return false;
    }

    public void setterBooleanB(boolean arg) {
    }

    public int conflictingField;
    public int getConflictingField() {
        return conflictingField;
    }

    public void bOverloaded() {
    }

    public void bOverloaded(int arg) {
    }

    public void bOverloaded(boolean arg) {
    }

    public void commonOverloaded(Object arg) {
    }

    public void commonOverloaded(B arg) {
    }

    public void unmappedOverloaded(Object arg) {
    }

    public void unmappedOverloaded(B arg) {
    }

    @Override
    public void bInterfaceMethod() {
        new B() {};
    }

    public class Inner {
        private int bField;
    }

    public class InnerB {
    }

    public class GenericB<T> {}
}
