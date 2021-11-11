package com.replaymod.gradle.remap.mapper.kotlin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
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
    @Disabled("not yet implemented")
    fun `converts synthetic property to setter if no longer synthetic`() {
        TestData.remapKt("""
            import a.pkg.A
            fun test() {
                A().nonSyntheticA = A()
                A().isNonSyntheticBooleanA = true
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            fun test() {
                B().setterB(B())
                B().setterBooleanB(true)
            }
        """.trimIndent()
    }

    @Test
    @Disabled("not yet implemented")
    fun `converts setter to synthetic property if now synthetic`() {
        TestData.remapKt("""
            import a.pkg.A
            fun test() {
                A().setterA(A())
                A().setterBooleanA(true)
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            fun test() {
                B().nonSyntheticB = B()
                B().isNonSyntheticBooleanB = true
            }
        """.trimIndent()
    }
}
