package com.replaymod.gradle.remap.imports

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test

class TestJavaAutoImportsFormatting {
    @Test
    fun `should separate java(x) from other imports with an empty line if possible`() {
        TestData.remap("""
            package test;
            
            
            
            
            
            
            class Test extends ArrayList implements Closeable, BInterface {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import b.pkg.BInterface;
            
            import java.io.Closeable;
            import java.util.ArrayList;
            
            class Test extends ArrayList implements Closeable, BInterface {
            }
        """.trimIndent()
    }

    @Test
    fun `should put new imports in single line if necessary to preserve original line count`() {
        TestData.remap("""
            package test;
            
            import test.Unused1;
            import test.Unused2;
            
            class Test extends ArrayList implements Closeable, BInterface {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import b.pkg.BInterface;
            import java.io.Closeable; import java.util.ArrayList;
            
            class Test extends ArrayList implements Closeable, BInterface {
            }
        """.trimIndent()
    }

    @Test
    fun `should always leave line after imports`() {
        TestData.remap("""
            package test;
            
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()) shouldBe """
            package test;
            import java.io.Closeable; import java.util.ArrayList;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()
    }

    @Test
    fun `should put imports in same line as package if required`() {
        TestData.remap("""
            package test;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()) shouldBe """
            package test; import java.io.Closeable; import java.util.ArrayList;
            
            class Test extends ArrayList implements Closeable {
            }
        """.trimIndent()
    }

    @Test
    fun `should remove unused imports from shared lines`() {
        TestData.remap("""
            package test;
            
            import java.io.Closeable; import java.util.ArrayList;
            
            class Test {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            
            
            class Test {
            }
        """.trimIndent()
    }

    @Test
    fun `should remove unused imports from end of shared lines`() {
        TestData.remap("""
            package test;
            
            import java.io.Closeable; import java.util.ArrayList;
            
            class Test implements Closeable {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import java.io.Closeable;
            
            class Test implements Closeable {
            }
        """.trimIndent()
    }

    @Test
    fun `should remove unused imports from start of shared lines`() {
        TestData.remap("""
            package test;
            
            import java.io.Closeable; import java.util.ArrayList;
            
            class Test extends ArrayList {
            }
        """.trimIndent()) shouldBe """
            package test;
            
            import java.util.ArrayList;
            
            class Test extends ArrayList {
            }
        """.trimIndent()
    }
}