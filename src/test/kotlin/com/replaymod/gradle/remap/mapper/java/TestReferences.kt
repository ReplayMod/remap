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
                A.NestedA test() {
                    return new A.NestedA();
                }
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B;
            class test {
                B.NestedB test() {
                    return new B.NestedB();
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps imported inner class references`() {
        TestData.remap("""
            import a.pkg.A.NestedA;
            class test {
                NestedA test() {
                    return new NestedA();
                }
            }
        """.trimIndent()) shouldBe """
            import b.pkg.B.NestedB;
            class test {
                NestedB test() {
                    return new NestedB();
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
                a.pkg.A.NestedA test() {
                    return new a.pkg.A.NestedA();
                }
                a.pkg.AGeneric<a.pkg.A> test() {
                    return new a.pkg.AGeneric();
                }
            }
        """.trimIndent()) shouldBe """
            class test {
                b.pkg.B test() {
                    return new b.pkg.B();
                }
                b.pkg.B.NestedB test() {
                    return new b.pkg.B.NestedB();
                }
                b.pkg.BGeneric<b.pkg.B> test() {
                    return new b.pkg.BGeneric();
                }
            }
        """.trimIndent()
    }

    @Test
    fun `remaps overrides`() {
        TestData.remap("""
            class Test extends a.pkg.A {
                @Override public a.pkg.A getA() { return create(); }
                @Override public void aMethod() {}
                @Override public void aOverloaded(int arg) {}
                
                private a.pkg.A myAnon = new a.pkg.A() {
                    @Override public void aMethod() {}
                };
            }
        """.trimIndent()) shouldBe """
            class Test extends b.pkg.B {
                @Override public b.pkg.B getB() { return create(); }
                @Override public void bMethod() {}
                @Override public void bOverloaded(int arg) {}
                
                private b.pkg.B myAnon = new b.pkg.B() {
                    @Override public void bMethod() {}
                };
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