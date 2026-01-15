package com.replaymod.gradle.remap.mapper.java

import com.replaymod.gradle.remap.util.TestData
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.junit.jupiter.api.Test

class TestReferences {
    @Test
    fun `remaps simple class references`() {
        TestData.remap("""
            import a.pkg.A;
            class test {
                A test() {
                    return new A();
                }
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B;
            class test {
                B test() {
                    return new B();
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps qualified inner class references`() {
        TestData.remap("""
            import a.pkg.A;
            class test {
                A.InnerA test() {
                    return new A.InnerA();
                }
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B;
            class test {
                B.InnerB test() {
                    return new B.InnerB();
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps imported inner class references`() {
        TestData.remap("""
            import a.pkg.A.InnerA;
            class test {
                InnerA test() {
                    return new InnerA();
                }
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B.InnerB;
            class test {
                InnerB test() {
                    return new InnerB();
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps fully qualified class references`() {
        TestData.remap("""
            class test {
                a.pkg.A test() {
                    return new a.pkg.A();
                }
                a.pkg.A.InnerA test() {
                    return new a.pkg.A.InnerA();
                }
            }
        """.trimIndent()) shouldBe """
            class test {
                b.pkg.B test() {
                    return new b.pkg.B();
                }
                b.pkg.B.InnerB test() {
                    return new b.pkg.B.InnerB();
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps method override with narrowed argument type`() {
        TestData.remap("""
            class Test extends a.pkg.A.GenericA<a.pkg.A> {
                @Override
                public void aMethod(a.pkg.A t) {
                }
            }
        """.trimIndent()) shouldBe """
            class Test extends b.pkg.B.GenericB<b.pkg.B> {
                @Override
                public void bMethod(b.pkg.B t) {
                }
            }
        """.trimIndent()
    }
}