package com.replaymod.gradle.remap.pattern

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestFieldReference {
    @Test
    fun `should match field on left side of assignment when replaced by field`() {
        TestData.remap("test/Test.java", """
            class Test {
                Test field;
                private void test() {
                    field = field;
                    this.field = this.field;
                    this.field.field = this.field.field;
                }
            }
        """.trimIndent(), """
            @remap.Pattern
            private test.Test pattern(test.Test obj) {
                return obj.field;
            }
        """.trimIndent(), """
            @remap.Pattern
            private test.Test pattern(test.Test obj) {
                return obj.matched;
            }
        """.trimIndent()) shouldBe """
            class Test {
                Test field;
                private void test() {
                    field = field;
                    this.matched = this.matched;
                    this.matched.matched = this.matched.matched;
                }
            }
        """.trimIndent()
    }
}