package com.replaymod.gradle.remap.mapper

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestMixinAnnotation {
    @Test
    fun `remaps with class target`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA { @Shadow private int aField; }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA { @Shadow private int bField; }
        """.trimIndent()
    }

    @Test
    fun `remaps with string target`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A")
            class MixinA { @Shadow private int aField; }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B")
            class MixinA { @Shadow private int bField; }
        """.trimIndent()
    }

    @Test
    fun `remaps with inner class string target separated by dot`() {
        // FIXME should probably keep the dot?
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A.Inner")
            class MixinA { @Shadow private int aField; }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B${'$'}Inner")
            class MixinA { @Shadow private int bField; }
        """.trimIndent()
    }

    @Test
    fun `remaps with inner class string target separated by dollar`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A${'$'}Inner")
            class MixinA { @Shadow private int aField; }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B${'$'}Inner")
            class MixinA { @Shadow private int bField; }
        """.trimIndent()
    }
}