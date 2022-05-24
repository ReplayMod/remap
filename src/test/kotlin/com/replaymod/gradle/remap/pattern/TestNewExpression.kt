package com.replaymod.gradle.remap.pattern

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestNewExpression {
    @Test
    fun `should find regular constructor`() {
        TestData.remap("""
            class Test {
                private void test() {
                    new a.pkg.A(null);
                    new a.pkg.A().new a.pkg.A.Inner();
                    new a.pkg.AParent(null);
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private a.pkg.A matchNew(a.pkg.A arg) {
                return new a.pkg.A(arg);
            }
        """.trimIndent(), """
            @remap.Pattern
            private b.pkg.B matchNew(b.pkg.B arg) {
                return arg;
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    null;
                    new b.pkg.B().new b.pkg.B.Inner();
                    new b.pkg.BParent(null);
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should find inner class constructor`() {
        TestData.remap("""
            class Test {
                private void test() {
                    new a.pkg.A(null);
                    new a.pkg.A().new Inner();
                    new a.pkg.AParent(null);
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private a.pkg.A.Inner matchNew(a.pkg.A arg) {
                return arg.new a.pkg.A.Inner();
            }
        """.trimIndent(), """
            @remap.Pattern
            private b.pkg.B matchNew(b.pkg.B arg) {
                return arg;
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    new b.pkg.B(null);
                    new b.pkg.B();
                    new b.pkg.BParent(null);
                }
            }
        """.trimIndent()
    }
}