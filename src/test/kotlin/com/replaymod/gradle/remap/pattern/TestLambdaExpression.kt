package com.replaymod.gradle.remap.pattern

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

class TestLambdaExpression {
    @Test
    fun `should find simply lambda expression`() {
        TestData.remap("""
            class Test {
                private void test() {
                    a.pkg.A.supplier(() -> "test");
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private void pattern(String str) {
                return a.pkg.A.supplier(() -> str);
            }
        """.trimIndent(), """
            @remap.Pattern
            private void pattern(String str) {
                return matched(str);
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    matched("test");
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should find lambda expression with bound arguments`() {
        TestData.remap("""
            class Test {
                private void test() {
                    a.pkg.A.function(str -> str + "test");
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private void pattern(String str) {
                return a.pkg.A.function(s -> s + str);
            }
        """.trimIndent(), """
            @remap.Pattern
            private void pattern(String str) {
                return matched(str);
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    matched("test");
                }
            }
        """.trimIndent()
    }

    @Test
    @Disabled("Not yet implemented. Requires more complex replacement scheme.")
    fun `should preserve bound lambda argument names`() {
        TestData.remap("""
            class Test {
                private void test() {
                    a.pkg.A.function(str -> str + "test");
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private void pattern(String str) {
                return a.pkg.A.function(s -> s + str);
            }
        """.trimIndent(), """
            @remap.Pattern
            private void pattern(String str) {
                return matched(s -> s + str);
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    matched(str -> str + "test");
                }
            }
        """.trimIndent()
    }
}