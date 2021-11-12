package pkg

import b.pkg.B

class Kt : B() {
    override fun getSyntheticB(): B {
        return this
    }

    override fun setSyntheticB(arg: B) {
    }
}