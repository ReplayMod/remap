package a.pkg;

public class AParent {
    public AParent aParentMethod() {
        return this;
    }

    public AParent aSpecializableMethod() {
        return this;
    }

    public AParent aSpecializableMethodWithChangingSignature() {
        return this;
    }

    public AParent() {}
    public AParent(A arg) {}
}
