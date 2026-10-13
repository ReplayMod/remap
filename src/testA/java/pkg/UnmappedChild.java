package pkg;

import a.pkg.A;

public class UnmappedChild extends A {
    @Override
    public A getA() {
        return super.getA();
    }
}
