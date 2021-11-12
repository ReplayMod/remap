package com.replaymod.gradle.remap.mapper.mixin

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestMixinOverride {
    @Test
    fun `remaps overridden method`() {
        TestData.remap("""
            @org.spongepowered.asm.mixin.Mixin(a.pkg.A.class)
            abstract class MixinA extends a.pkg.AParent {
                @Override
                public a.pkg.AParent aParentMethod() {
                    return this;
                }
            }
        """.trimIndent()) shouldBe """
            @org.spongepowered.asm.mixin.Mixin(b.pkg.B.class)
            abstract class MixinA extends b.pkg.BParent {
                @Override
                public b.pkg.BParent bParentMethod() {
                    return this;
                }
            }
        """.trimIndent()
    }
}