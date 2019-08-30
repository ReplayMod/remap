plugins {
    id 'java'
    id 'maven-publish'
}

sourceCompatibility = '1.8'
targetCompatibility = '1.8'

group = 'com.github.replaymod'
version = 'SNAPSHOT'

repositories {
    mavenCentral()
}

dependencies {
    compile 'org.jetbrains.kotlin:kotlin-compiler:1.3.40'
    compile 'org.cadixdev:lorenz:0.5.0'
}

jar {
    archiveBaseName.set('remap')
}

publishing {
    publications {
        maven(MavenPublication) {
            from components.java
        }
    }
}
