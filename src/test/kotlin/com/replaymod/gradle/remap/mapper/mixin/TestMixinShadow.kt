package com.replaymod.gradle.remap.mapper.mixin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestMixinShadow {
    @Test
    fun `remaps shadow method and references to it`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                protected abstract a.pkg.A getA();
                private void test() { this.getA(); }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                protected abstract b.pkg.B getB();
                private void test() { this.getB(); }
            }
        """.trimIndent()
    }


    @Test
    fun `resolve shadow names in anonymous classes`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A$2")
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                protected abstract void aMethodAnon();
                private void test() { this.aMethodAnon(); }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B$2")
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                protected abstract void bMethodAnon();
                private void test() { this.bMethodAnon(); }
            }
        """.trimIndent()
    }
}