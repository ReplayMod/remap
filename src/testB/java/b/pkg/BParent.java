package b.pkg;

public class BParent {
    public BParent bParentMethod() {
        return this;
    }

    public BParent bSpecializableMethod() {
        return this;
    }

    public BParent bSpecializableMethodWithChangingSignature(int newArgument) {
        return this;
    }
}
