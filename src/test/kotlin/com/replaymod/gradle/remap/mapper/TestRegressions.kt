package com.replaymod.gradle.remap.mapper

import com.replaymod.gradle.remap.util.TestData
import com.replaymod.gradle.remap.util.TestData.expectNoErrors
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestRegressions {
    @Test
    fun `custom exception handler extension point must be registered`() {
        // Code which does method resolution which taken into account checked exceptions requires that
        // the CustomExceptionHandler extension point is registered, which it doesn't seem to be by default.
        TestData.remap("""
            class test {
                <T> void on(java.util.List<T> list, T fun) {}
                java.util.List<java.io.Closeable> i = null;
                { on(i, this::f); }
                void f() throws java.io.IOException {}
            }
        """.trimIndent())
    }

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
