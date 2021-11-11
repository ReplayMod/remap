package com.replaymod.gradle.remap.pattern

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TestChangeMerging {
    @Test
    fun `should work when mixed with remapping`() {
        TestData.remap("""
            class Test {
                private void test() {
                    a.pkg.A.create().aMethod();
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private void addWrapping(a.pkg.A a) {
                a.aMethod();
            }
        """.trimIndent(), """
            @remap.Pattern
            private void addWrapping(a.pkg.A a) {
                (((a.bMethod())));
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    (((b.pkg.B.create().bMethod())));
                }
            }
        """.trimIndent()
    }
}