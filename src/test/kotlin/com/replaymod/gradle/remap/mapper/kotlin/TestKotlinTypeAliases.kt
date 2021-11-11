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
            val test: A = TODO()
        """.trimIndent()) shouldBe """
            typealias A = b.pkg.B
            val test: A = TODO()
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
            typealias InnerA = a.pkg.A.InnerA
            val test: InnerA = TODO()
        """.trimIndent()) shouldBe """
            typealias InnerA = b.pkg.B.InnerB
            val test: InnerA = TODO()
        """.trimIndent()
    }
}