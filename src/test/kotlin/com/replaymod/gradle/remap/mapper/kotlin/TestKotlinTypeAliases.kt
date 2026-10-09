package com.replaymod.gradle.remap.mapper.kotlin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TestKotlinTypeAliases {
    @Test
    fun `remaps simple alias`() {
        TestData.remapKt("""
            typealias A = a.pkg.A
            val test: A = A()
        """.trimIndent()) shouldBe """
            typealias A = b.pkg.B
            val test: A = A()
        """.trimIndent()
    }

    @Test
    fun `remaps outer class of inner class alias`() {
        TestData.remapKt("""
            typealias Inner = a.pkg.A.Inner
            val test: Inner = TODO()
        """.trimIndent()) shouldBe """
            typealias Inner = b.pkg.B.Inner
            val test: Inner = TODO()
        """.trimIndent()
    }

    @Test
    fun `remaps inner class alias`() {
        TestData.remapKt("""
            typealias NestedA = a.pkg.A.NestedA
            val test: NestedA = NestedA()
        """.trimIndent()) shouldBe """
            typealias NestedA = b.pkg.B.NestedB
            val test: NestedA = NestedA()
        """.trimIndent()
    }

    @Test
    fun `does not remap aliased SAM constructor`() {
        TestData.remapKt("""
            typealias Interface = a.pkg.AInterface
            val test = Interface { }
        """.trimIndent()) shouldBe """
            typealias Interface = b.pkg.BInterface
            val test = Interface { }
        """.trimIndent()
    }
}