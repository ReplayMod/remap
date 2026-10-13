package pkg;

import b.pkg.B;

public class UnmappedChild extends B {
    @Override
    public B getB() {
        return super.getB();
    }
}
