package pkg

import a.pkg.A

class Kt : A() {
    override fun getSyntheticA(): A {
        return this
    }

    override fun setSyntheticA(arg: A) {
    }
}