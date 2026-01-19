package com.replaymod.gradle.remap.mapper.kotlin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestReferences {
    @Test
    fun `remaps simple class references`() {
        TestData.remapKt("""
            import a.pkg.A
            fun test(): A {
                return A()
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            fun test(): B {
                return B()
            }
        """.trimIndent()
    }

    @Test
    fun `remaps qualified inner class references`() {
        TestData.remapKt("""
            import a.pkg.A
            fun test(): A.InnerA {
                return A.InnerA()
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B
            fun test(): B.InnerB {
                return B.InnerB()
            }
        """.trimIndent()
    }

    @Test
    fun `remaps imported inner class references`() {
        TestData.remapKt("""
            import a.pkg.A.InnerA
            fun test(): InnerA {
                return InnerA()
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B.InnerB
            fun test(): InnerB {
                return InnerB()
            }
        """.trimIndent()
    }

    @Test
    fun `remaps fully qualified class references`() {
        TestData.remapKt("""
            fun test(): a.pkg.A {
                return a.pkg.A()
            }
            fun test(): a.pkg.A.InnerA {
                return a.pkg.A.InnerA()
            }
        """.trimIndent()) shouldBe """
            fun test(): b.pkg.B {
                return b.pkg.B()
            }
            fun test(): b.pkg.B.InnerB {
                return b.pkg.B.InnerB()
            }
        """.trimIndent()
    }

    @Test
    fun `remaps same-project kotlin class references`() {
        TestData.transformer.remap(mapOf(
            "test.kt" to """
                import a.pkg.UserA
                import a.pkg.UserA.InnerA
                fun test(): UserA {
                    return UserA()
                }
                fun test(): InnerA {
                    return InnerA()
                }
            """.trimIndent(),
            "a/pkg/UserA.kt" to "package a.pkg; class UserA { class InnerA {} }",
        ))["test.kt"]?.first shouldBe """
            import b.pkg.UserB
            import b.pkg.UserB.InnerB
            fun test(): UserB {
                return UserB()
            }
            fun test(): InnerB {
                return InnerB()
            }
        """.trimIndent()
    }

    @Test
    fun `remaps SAM constructors`() {
        TestData.remapKt("""
            import a.pkg.AInterface
            fun test(): AInterface {
                return AInterface {}
            }
        """.trimIndent()) shouldBe """
            import b.pkg.BInterface
            fun test(): BInterface {
                return BInterface {}
            }
        """.trimIndent()
    }
}