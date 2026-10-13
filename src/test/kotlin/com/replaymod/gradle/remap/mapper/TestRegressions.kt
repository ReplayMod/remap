package com.replaymod.gradle.remap.mapper

import com.replaymod.gradle.remap.util.TestData
import com.replaymod.gradle.remap.util.TestData.expectNoErrors
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestRegressions {
    @Test
    fun `readAsm must not try to read source-only files`() {
        TestData.transformer.remap(mapOf(
            "TestMixin.java" to """
            @org.spongepowered.asm.mixin.Mixin(pkg.TestTarget.class)
            class TestMixin {
                @org.spongepowered.asm.mixin.injection.Inject(method = "target")
                private void test() {}
            }
            """.trimIndent(),
            "TestTarget.java" to """
                package pkg;
                class TestTarget {
                    void target() {}
                }
            """.trimIndent()
        )).expectNoErrors()
    }

    @Test
    fun `must remap injections even if target doesn't have mappings`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(pkg.UnmappedChild.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "getA")
                private void test() {}
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(pkg.UnmappedChild.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "getB")
                private void test() {}
            }
        """.trimIndent()
    }
}
