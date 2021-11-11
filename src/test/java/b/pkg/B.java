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
