package com.replaymod.gradle.remap.mapper.kotlin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TestKotlinGenerics {
    @Test
    fun `remaps generic type argument`() {
        TestData.remapKt("""
            val test: List<a.pkg.A> = TODO()
        """.trimIndent()) shouldBe """
            val test: List<b.pkg.B> = TODO()
        """.trimIndent()
    }

    @Test
    fun `remaps generic type argument with import`() {
        TestData.remapKt("""
            import a.pkg.A
            val test: List<A> = TODO()
        """.trimIndent()) shouldBe """
            import b.pkg.B
            val test: List<B> = TODO()
        """.trimIndent()
    }

    @Test
    fun `remaps generic type`() {
        TestData.remapKt("""
            val test: a.pkg.A.GenericA<Int> = TODO()
        """.trimIndent()) shouldBe """
            val test: b.pkg.B.GenericB<Int> = TODO()
        """.trimIndent()
    }

    @Test
    fun `remaps generic type with import`() {
        TestData.remapKt("""
            import a.pkg.A.GenericA
            val test: GenericA<Int> = TODO()
        """.trimIndent()) shouldBe """
            import b.pkg.B.GenericB
            val test: GenericB<Int> = TODO()
        """.trimIndent()
    }
}