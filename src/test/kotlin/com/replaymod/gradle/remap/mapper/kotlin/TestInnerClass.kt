package com.replaymod.gradle.remap.mapper.kotlin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestInnerClass {
    @Test
    fun `remaps non inner method`() {
        TestData.remapKt("""
            fun apply(x: a.pkg.A) = x.notAInnerMethod()
        """.trimIndent()) shouldBe """
            fun apply(x: b.pkg.B) = x.notBInnerMethod()
        """.trimIndent()
    }

    @Test
    fun `remaps method in inner class`() {
        TestData.remapKt("""
            fun apply(x: a.pkg.A.InnerA) = x.aMethod()
        """.trimIndent()) shouldBe """
            fun apply(x: b.pkg.B.InnerB) = x.bMethod()
        """.trimIndent()
    }
}