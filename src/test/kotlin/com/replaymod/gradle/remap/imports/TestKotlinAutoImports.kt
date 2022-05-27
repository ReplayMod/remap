package com.replaymod.gradle.remap.imports

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestKotlinAutoImports {
    @Test
    fun `should not touch Kotlin files (yet)`() {
        TestData.remap("test.kt", """
            package test
            
            import java.util.ArrayList
            
            class Test {
            }
        """.trimIndent()) shouldBe """
            package test
            
            import java.util.ArrayList
            
            class Test {
            }
        """.trimIndent()
    }
}