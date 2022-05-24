package com.replaymod.gradle.remap.pattern

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestVarArgs {
    @Test
    fun `should find varargs method`() {
        TestData.remap("""
            class Test {
                private void test() {
                    method();
                    method("1");
                    method("1", "2");
                    method("1", "2", null);
                    method(new String[0]);
                    method("1", "2", 3);
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(String...args) {
                return method(args);
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(String...args) {
                return matched(args);
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    matched();
                    matched("1");
                    matched("1", "2");
                    matched("1", "2", null);
                    matched(new String[0]);
                    method("1", "2", 3);
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should find varargs method with fixed leading argument`() {
        TestData.remap("""
            class Test {
                private void test() {
                    method(42);
                    method(42, "1");
                    method(42, "1", "2");
                    method(42, "1", "2", null);
                    method(42, new String[0]);
                    method(42, "1", "2", 3);
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(String...args) {
                return method(42, args);
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(String...args) {
                return matched(42, args);
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    matched(42);
                    matched(42, "1");
                    matched(42, "1", "2");
                    matched(42, "1", "2", null);
                    matched(42, new String[0]);
                    method(42, "1", "2", 3);
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should find varargs method with variable leading argument`() {
        TestData.remap("""
            class Test {
                private void test() {
                    method(42);
                    method(43, "1");
                    method(44, "1", "2");
                    method(45, "1", "2", null);
                    method(46, new String[0]);
                    method(47, "1", "2", 3);
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(int i, String...args) {
                return method(i, args);
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(int i, String...args) {
                return matched(i, args);
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    matched(42);
                    matched(43, "1");
                    matched(44, "1", "2");
                    matched(45, "1", "2", null);
                    matched(46, new String[0]);
                    method(47, "1", "2", 3);
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should allow leading argument to be removed`() {
        TestData.remap("""
            class Test {
                private void test() {
                    method(42);
                    method(42, "1");
                    method(42, "1", "2");
                    method(42, "1", "2", null);
                    method(42, new String[0]);
                    method(42, "1", "2", 3);
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(String...args) {
                return method(42, args);
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(String...args) {
                return matched(args);
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    matched();
                    matched("1");
                    matched("1", "2");
                    matched("1", "2", null);
                    matched(new String[0]);
                    method(42, "1", "2", 3);
                }
            }
        """.trimIndent()
    }

    @Test
    fun `should allow leading argument to be added`() {
        TestData.remap("""
            class Test {
                private void test() {
                    method();
                    method("1");
                    method("1", "2");
                    method("1", "2", null);
                    method(new String[0]);
                    method("1", "2", 3);
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(String...args) {
                return method(args);
            }
        """.trimIndent(), """
            @remap.Pattern
            private String pattern(String...args) {
                return matched(42, args);
            }
        """.trimIndent()) shouldBe """
            class Test {
                private void test() {
                    matched(42);
                    matched(42, "1");
                    matched(42, "1", "2");
                    matched(42, "1", "2", null);
                    matched(42, new String[0]);
                    method("1", "2", 3);
                }
            }
        """.trimIndent()
    }
}