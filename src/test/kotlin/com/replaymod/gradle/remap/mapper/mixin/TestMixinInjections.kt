package com.replaymod.gradle.remap.mapper.mixin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TestMixinInjections {
    private fun remaps(annotation: String) {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                @$annotation(method = "aMethod")
                private void test() {}
                @$annotation(method = "aInterfaceMethod")
                private void testInterface() {}
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                @$annotation(method = "bMethod")
                private void test() {}
                @$annotation(method = "bInterfaceMethod")
                private void testInterface() {}
            }
        """.trimIndent()
    }

    @Test
    fun `remaps @Inject method`() = remaps("org.spongepowered.asm.mixin.injection.Inject")

    @Test
    fun `remaps @ModifyArg method`() = remaps("org.spongepowered.asm.mixin.injection.ModifyArg")

    @Test
    fun `remaps @ModifyArgs method`() = remaps("org.spongepowered.asm.mixin.injection.ModifyArgs")

    @Test
    fun `remaps @ModifyConstant method`() = remaps("org.spongepowered.asm.mixin.injection.ModifyConstant")

    @Test
    fun `remaps @ModifyVariable method`() = remaps("org.spongepowered.asm.mixin.injection.ModifyVariable")

    @Test
    fun `remaps @Redirect method`() = remaps("org.spongepowered.asm.mixin.injection.Redirect")

    @Test
    fun `throws when injecting into ambiguous method`() {
        val (_, errors) = TestData.remapWithErrors("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "aOverloaded")
                private void test() {}
            }
        """.trimIndent())
        errors shouldHaveSize 1
        val (line, error) = errors[0]
        line shouldBe 2
        error shouldContain "aOverloaded"
        error shouldContain "(I)V"
        error shouldContain "(Z)V"
    }

    @Test
    fun `remaps qualified method`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "aOverloaded()V")
                private void test() {}
                @org.spongepowered.asm.mixin.injection.Inject(method = "aOverloaded(I)V")
                private void testArg() {}
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "bOverloaded()V")
                private void test() {}
                @org.spongepowered.asm.mixin.injection.Inject(method = "bOverloaded(I)V")
                private void testArg() {}
            }
        """.trimIndent()
    }

    @Test
    fun `remaps qualified method argument`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "commonOverloaded(La/pkg/A;)V")
                private void test() {}
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "commonOverloaded(Lb/pkg/B;)V")
                private void test() {}
            }
        """.trimIndent()
    }

    @Test
    fun `remaps qualified method argument without mappings for target`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "unmappedOverloaded(La/pkg/A;)V")
                private void test() {}
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "unmappedOverloaded(Lb/pkg/B;)V")
                private void test() {}
            }
        """.trimIndent()
    }

    @Test
    fun `remaps constructor target`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "<init>()V")
                private void test() {}
                @org.spongepowered.asm.mixin.injection.Inject(method = "<init>(La/pkg/A;)V")
                private void testArg() {}
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                @org.spongepowered.asm.mixin.injection.Inject(method = "<init>()V")
                private void test() {}
                @org.spongepowered.asm.mixin.injection.Inject(method = "<init>(Lb/pkg/B;)V")
                private void testArg() {}
            }
        """.trimIndent()
    }

    @Test
    fun `remaps method in constant`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                private static final String TARGET = "aMethod";
                @org.spongepowered.asm.mixin.injection.Inject(method = TARGET)
                private void test1() {}
                @org.spongepowered.asm.mixin.injection.Inject(method = TARGET)
                private void test2() {}
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                private static final String TARGET = "bMethod";
                @org.spongepowered.asm.mixin.injection.Inject(method = TARGET)
                private void test1() {}
                @org.spongepowered.asm.mixin.injection.Inject(method = TARGET)
                private void test2() {}
            }
        """.trimIndent()
    }

    @Test
    fun `remaps @At target`() {
        TestData.remap("""
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                @Inject(method = "aMethod", at = @At(target = "La/pkg/A;aInterfaceMethod()V"))
                private void test() {}
            }
        """.trimIndent()) shouldBe """
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                @Inject(method = "bMethod", at = @At(target = "Lb/pkg/B;bInterfaceMethod()V"))
                private void test() {}
            }
        """.trimIndent()
    }

    @Test
    fun `remaps @At target without mappings for target`() {
        TestData.remap("""
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                @Inject(method = "aMethod", at = @At(target = "La/pkg/A;unmappedOverloaded(La/pkg/A;)V"))
                private void test() {}
            }
        """.trimIndent()) shouldBe """
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                @Inject(method = "bMethod", at = @At(target = "Lb/pkg/B;unmappedOverloaded(Lb/pkg/B;)V"))
                private void test() {}
            }
        """.trimIndent()
    }

    @Test
    fun `remaps @At target in constant`() {
        TestData.remap("""
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            class MixinA {
                private static final String TARGET = "La/pkg/A;aInterfaceMethod()V";
                @Inject(method = "aMethod", at = @At(target = TARGET))
                private void test1() {}
                @Inject(method = "aMethod", at = @At(target = TARGET))
                private void test2() {}
            }
        """.trimIndent()) shouldBe """
            import org.spongepowered.asm.mixin.injection.At;
            import org.spongepowered.asm.mixin.injection.Inject;
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            class MixinA {
                private static final String TARGET = "Lb/pkg/B;bInterfaceMethod()V";
                @Inject(method = "bMethod", at = @At(target = TARGET))
                private void test1() {}
                @Inject(method = "bMethod", at = @At(target = TARGET))
                private void test2() {}
            }
        """.trimIndent()
    }
}