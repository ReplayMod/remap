package com.replaymod.gradle.remap.mapper.mixin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestMixinAccessors {
    @Test
    fun `remaps @Invoker`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            interface MixinA {
                @org.spongepowered.asm.mixin.gen.Invoker
                void invokeAMethod();
                @org.spongepowered.asm.mixin.gen.Invoker("aMethod")
                void invokeBMethod();
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            interface MixinA {
                @org.spongepowered.asm.mixin.gen.Invoker("bMethod")
                void invokeAMethod();
                @org.spongepowered.asm.mixin.gen.Invoker
                void invokeBMethod();
            }
        """.trimIndent()
    }

    @Test
    fun `remaps @Invoker with non-standard method name`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            interface MixinA {
                @org.spongepowered.asm.mixin.gen.Invoker("aMethod")
                void arbitraryName();
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            interface MixinA {
                @org.spongepowered.asm.mixin.gen.Invoker("bMethod")
                void arbitraryName();
            }
        """.trimIndent()
    }

    @Test
    fun `remaps @Accessor`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            interface MixinA {
                @org.spongepowered.asm.mixin.gen.Accessor
                int isAField();
                @org.spongepowered.asm.mixin.gen.Accessor
                int getAField();
                @org.spongepowered.asm.mixin.gen.Accessor
                void setAField(int value);
                @org.spongepowered.asm.mixin.gen.Accessor("aField")
                int isBField();
                @org.spongepowered.asm.mixin.gen.Accessor("aField")
                int getBField();
                @org.spongepowered.asm.mixin.gen.Accessor("aField")
                void setBField(int value);
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            interface MixinA {
                @org.spongepowered.asm.mixin.gen.Accessor("bField")
                int isAField();
                @org.spongepowered.asm.mixin.gen.Accessor("bField")
                int getAField();
                @org.spongepowered.asm.mixin.gen.Accessor("bField")
                void setAField(int value);
                @org.spongepowered.asm.mixin.gen.Accessor
                int isBField();
                @org.spongepowered.asm.mixin.gen.Accessor
                int getBField();
                @org.spongepowered.asm.mixin.gen.Accessor
                void setBField(int value);
            }
        """.trimIndent()
    }

    @Test
    fun `does not change @Accessor method name even when it happens to be the same as a method in the target`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            interface MixinA {
                @org.spongepowered.asm.mixin.gen.Accessor
                a.pkg.A getA();
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            interface MixinA {
                @org.spongepowered.asm.mixin.gen.Accessor("b")
                b.pkg.B getA();
            }
        """.trimIndent()
    }
}