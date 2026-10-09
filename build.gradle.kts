import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.4.20"
    `maven-publish`
}

java.toolchain.languageVersion.set(JavaLanguageVersion.of(17))

group = "com.github.replaymod"
version = "SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.spongepowered.org/repository/maven-public/")
}

val testA = sourceSets.create("testA")
val testB = sourceSets.create("testB")

dependencies {
    api("org.jetbrains.kotlin:kotlin-compiler-embeddable:2.0.0")
    implementation(kotlin("stdlib"))
    implementation("org.ow2.asm:asm-tree:9.9.1")
    api("org.cadixdev:lorenz:0.5.0")
    runtimeOnly("net.java.dev.jna:jna:5.10.0") // don't strictly need this but IDEA spams log without

    testImplementation("org.junit.jupiter:junit-jupiter:5.7.1")
    testImplementation("io.kotest:kotest-assertions-core:4.6.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    testRuntimeOnly(testA.output)
    testRuntimeOnly(testB.output)
    testRuntimeOnly("org.spongepowered:mixin:0.8.4")
}

tasks.named<Jar>("jar") {
    archiveBaseName.set("remap")
}

publishing {
    publications {
        create("maven", MavenPublication::class) {
            from(components["java"])
        }
    }
}

tasks.test {
    useJUnitPlatform()
}

tasks.compileKotlin {
    compilerOptions {
        apiVersion.set(KotlinVersion.KOTLIN_2_2)
        languageVersion.set(KotlinVersion.KOTLIN_2_2)
    }
}
