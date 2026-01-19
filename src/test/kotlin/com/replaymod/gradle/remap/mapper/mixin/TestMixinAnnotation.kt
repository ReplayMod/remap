package com.replaymod.gradle.remap.mapper.mixin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestMixinAnnotation {
    @Test
    fun `remaps with class target`() {
        TestData.remap("""
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA { @Shadow private int aField; }
        """.trimIndent()) shouldBe """
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA { @Shadow private int bField; }
        """.trimIndent()
    }

    @Test
    fun `remaps with string target`() {
        TestData.remap("""
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A")
            class MixinA { @Shadow private int aField; }
        """.trimIndent()) shouldBe """
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B")
            class MixinA { @Shadow private int bField; }
        """.trimIndent()
    }

    @Test
    fun `remaps with inner class string target separated by dot`() {
        // FIXME should probably keep the dot?
        TestData.remap("""
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A.Inner")
            class MixinA { @Shadow private int aField; }
        """.trimIndent()) shouldBe """
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B${'$'}Inner")
            class MixinA { @Shadow private int bField; }
        """.trimIndent()
    }

    @Test
    fun `remaps with inner class string target separated by dollar`() {
        TestData.remap("""
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A${'$'}Inner")
            class MixinA { @Shadow private int aField; }
        """.trimIndent()) shouldBe """
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B${'$'}Inner")
            class MixinA { @Shadow private int bField; }
        """.trimIndent()
    }

    @Test
    fun `remaps with anonymous inner class target`() {
        TestData.remap("""
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A${'$'}1")
            class MixinA { @Shadow private int aAnonField; }
        """.trimIndent()) shouldBe """
            import org.spongepowered.asm.mixin.Shadow;
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B${'$'}1")
            class MixinA { @Shadow private int bAnonField; }
        """.trimIndent()
    }
}