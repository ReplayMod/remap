package com.replaymod.gradle.remap.mapper.kotlin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TestKotlinImports {
    @Test
    fun `remaps simple import`() {
        TestData.remapKt("""
            import a.pkg.A
            val test: A = TODO()
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val test: B = TODO()
        """.trimIndent()
    }

    @Test
    fun `remaps outer class of inner class import`() {
        TestData.remapKt("""
            import a.pkg.A.Inner
            val test: Inner = TODO()
        """.trimIndent()) shouldBe """
            import b.pkg.B.Inner
            val test: Inner = TODO()
        """.trimIndent()
    }

    @Test
    fun `remaps inner class import`() {
        TestData.remapKt("""
            import a.pkg.A.NestedA
            val test: NestedA = TODO()
        """.trimIndent()) shouldBe """
            import b.pkg.B.NestedB
            val test: NestedB = TODO()
        """.trimIndent()
    }

    @Test
    fun `remaps import alias`() {
        TestData.remapKt("""
            import a.pkg.A as AAlias
            import a.pkg.A.NestedA as NestedA
            import a.pkg.A.GenericA as GenericA
            val test: NestedA = NestedA()
            val test: AAlias.NestedA = AAlias.NestedA()
            val test: GenericA<NestedA> = GenericA<NestedA>()
            val test: a.pkg.A.NestedA = a.pkg.A.NestedA()
        """.trimIndent()) shouldBe """
            import b.pkg.B as AAlias
            import b.pkg.B.NestedB as NestedA
            import b.pkg.B.GenericB as GenericA
            val test: NestedA = NestedA()
            val test: AAlias.NestedB = AAlias.NestedB()
            val test: GenericA<NestedA> = GenericA<NestedA>()
            val test: b.pkg.B.NestedB = b.pkg.B.NestedB()
        """.trimIndent()
    }

    @Test
    fun `remaps imported method`() {
        TestData.remapKt("""
            import a.pkg.A.createA
            val test = createA()
        """.trimIndent()) shouldBe """
            import b.pkg.B.createB
            val test = createB()
        """.trimIndent()
    }

    @Test
    fun `remaps ambiguous imported method when all referenced elements remap to the same name`() {
        TestData.remapKt("""
            import a.pkg.A.aStaticOverload
            val test = aStaticOverload() + aStaticOverload(1)
        """.trimIndent()) shouldBe """
            import b.pkg.B.bStaticOverload
            val test = bStaticOverload() + bStaticOverload(1)
        """.trimIndent()
    }

    @Test
    fun `remaps ambiguous imported method when only one is actually used`() {
        TestData.remapKt("""
            import a.pkg.A.aAmbiguousMethod
            val test = aAmbiguousMethod()
        """.trimIndent()) shouldBe """
            import b.pkg.B.bAmbiguousMethodWithoutInt
            val test = bAmbiguousMethodWithoutInt()
        """.trimIndent()

        TestData.remapKt("""
            import a.pkg.A.aAmbiguousMethod
            val test = aAmbiguousMethod(0)
        """.trimIndent()) shouldBe """
            import b.pkg.B.bAmbiguousMethodWithInt
            val test = bAmbiguousMethodWithInt(0)
        """.trimIndent()
    }

    @Test
    fun `refuses to remap ambiguous imported method`() {
        val (_, errors) = TestData.remapKtWithErrors("""
            import a.pkg.A.aAmbiguousMethod
            val test = aAmbiguousMethod() + aAmbiguousMethod(0)
        """.trimIndent())
        errors shouldHaveSize 1
        val (line, error) = errors[0]
        line shouldBe 0
        error shouldContain "aAmbiguousMethod"
        error shouldContain "bAmbiguousMethodWithInt"
        error shouldContain "bAmbiguousMethodWithoutInt"
    }
}