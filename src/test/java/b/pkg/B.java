package b.pkg;

public class B extends BParent implements BInterface {
    private int bField;

    public void bMethod() {
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

    @Override
    public void bInterfaceMethod() {
    }

    public class Inner {
        private int bField;
    }
}
