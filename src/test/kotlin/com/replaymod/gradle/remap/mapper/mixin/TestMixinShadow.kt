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
    fun `remaps shadow method in target hierarchy and references to it`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                public abstract void aInterfaceMethod();
                private void test() { this.aInterfaceMethod(); }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                public abstract void bInterfaceMethod();
                private void test() { this.bInterfaceMethod(); }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps shadow targeting synthetic field`() {
        TestData.remap($$"""
            @org.spongepowered.asm.mixin.Mixin(targets = "a.pkg.A$1")
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                a.pkg.A val$aLocal;
                private void test() { this.val$aLocal.aInterfaceMethod(); }
            }
        """.trimIndent()) shouldBe $$"""
            @org.spongepowered.asm.mixin.Mixin(targets = "b.pkg.B$1")
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Shadow
                b.pkg.B val$bLocal;
                private void test() { this.val$bLocal.bInterfaceMethod(); }
            }
        """.trimIndent()
    }

    @Test
    fun `does not rename non-shadow fields and methods`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Unique
                private a.pkg.A a;
                @org.spongepowered.asm.mixin.Unique
                protected abstract a.pkg.A getA();
                private a.pkg.A test() { this.getA(); return this.a; }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            abstract class MixinA {
                @org.spongepowered.asm.mixin.Unique
                private b.pkg.B a;
                @org.spongepowered.asm.mixin.Unique
                protected abstract b.pkg.B getA();
                private b.pkg.B test() { this.getA(); return this.a; }
            }
        """.trimIndent()
    }
}