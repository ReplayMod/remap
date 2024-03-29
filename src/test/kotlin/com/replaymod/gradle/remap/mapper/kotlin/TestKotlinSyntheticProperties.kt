package com.replaymod.gradle.remap.mapper.kotlin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestKotlinSyntheticProperties {
    @Test
    fun `remaps synthetic getter`() {
        TestData.remapKt("""
            import a.pkg.A
            val v = A().syntheticA
            val b = A().isSyntheticBooleanA
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val v = B().syntheticB
            val b = B().isSyntheticBooleanB
        """.trimIndent()
    }

    @Test
    fun `remaps synthetic setter`() {
        TestData.remapKt("""
            import a.pkg.A
            fun test() {
                A().syntheticA = A()
                A().isSyntheticBooleanA = true
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            fun test() {
                B().syntheticB = B()
                B().isSyntheticBooleanB = true
            }
        """.trimIndent()
    }

    @Test
    fun `converts synthetic property to getter if no longer synthetic`() {
        TestData.remapKt("""
            import a.pkg.A
            val v = A().nonSyntheticA
            val b = A().isNonSyntheticBooleanA
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val v = B().getterB()
            val b = B().getterBooleanB()
        """.trimIndent()
    }

    @Test
    fun `converts getter to synthetic property if now synthetic`() {
        TestData.remapKt("""
            import a.pkg.A
            val v = A().getterA()
            val b = A().getterBooleanA()
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val v = B().nonSyntheticB
            val b = B().isNonSyntheticBooleanB
        """.trimIndent()
    }

    @Test
    fun `converts synthetic property to setter if no longer synthetic`() {
        TestData.remapKt("""
            import a.pkg.A
            fun test() {
                A().nonSyntheticA = A()
                A().isNonSyntheticBooleanA =
                    // Comment
                    true // More comment
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            fun test() {
                B().setterB(B())
                B().setterBooleanB(
                    // Comment
                    true) // More comment
            }
        """.trimIndent()
    }

    @Test
    fun `converts setter to synthetic property if now synthetic`() {
        TestData.remapKt("""
            import a.pkg.A
            fun test() {
                A().setterA(A())
                A().setterBooleanA(
                    // Comment
                    true) // More comment
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            fun test() {
                B().nonSyntheticB = B()
                B().isNonSyntheticBooleanB =
                    // Comment
                    true // More comment
            }
        """.trimIndent()
    }

    @Test
    fun `does not convert getter to synthetic property if it would be shadowed by a field with the same name`() {
        TestData.remapKt("""
            import a.pkg.A
            val v = A().getConflictingFieldWithoutConflict()
            class C : A() {
                val v = getProtectedFieldWithoutConflict()
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val v = B().getConflictingField()
            class C : B() {
                val v = getProtectedField()
            }
        """.trimIndent()
    }

    @Test
    fun `does convert getter to synthetic property if the field which it would be shadowed by is inaccessible`() {
        TestData.remapKt("""
            import a.pkg.A
            val v = A().getA()
            val v = A().getProtectedFieldWithoutConflict()
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val v = B().b
            val v = B().protectedField
        """.trimIndent()
    }

    @Test
    fun `convert synthetic property to getter if it would be shadowed by a field with the same name`() {
        TestData.remapKt("""
            import a.pkg.A
            val v = A().conflictingFieldWithoutConflict
            class C : A() {
                val v = protectedFieldWithoutConflict
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val v = B().getConflictingField()
            class C : B() {
                val v = getProtectedField()
            }
        """.trimIndent()
    }

    @Test
    fun `does not convert synthetic property to getter if the field which it would be shadowed by is inaccessible`() {
        TestData.remapKt("""
            import a.pkg.A
            val v = A().a
            val v = A().protectedFieldWithoutConflict
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val v = B().b
            val v = B().protectedField
        """.trimIndent()
    }

    @Test
    fun `remaps synthetic property even when overwritten in kotlin subclass`() {
        TestData.remapKt("""
            import pkg.Kt
            val v = Kt().syntheticA
            fun test() { Kt().syntheticA = Kt() }
        """.trimIndent()) shouldBe """
            import pkg.Kt
            val v = Kt().syntheticB
            fun test() { Kt().syntheticB = Kt() }
        """.trimIndent()
    }

    @Test
    fun `does not replace super calls with synthetic properties`() {
        TestData.remapKt("""
            import a.pkg.A
            class C : A() {
                init {
                    super.getSyntheticA()
                    super.isSyntheticBooleanA()
                    super.setSyntheticA(A())
                    super.setSyntheticBooleanA(true)
                }
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            class C : B() {
                init {
                    super.getSyntheticB()
                    super.isSyntheticBooleanB()
                    super.setSyntheticB(B())
                    super.setSyntheticBooleanB(true)
                }
            }
        """.trimIndent()
    }
}
