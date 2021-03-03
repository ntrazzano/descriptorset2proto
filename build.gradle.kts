import com.google.protobuf.gradle.*
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.4.31"
    id("com.google.protobuf") version "0.8.15"
    application
}

group = "org."
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

sourceSets {
    test {
        resources {
            srcDirs ("${protobuf.protobuf.generatedFilesBaseDir}/test/")
        }
    }
}

protobuf {
    generateProtoTasks {
        ofSourceSet("test").forEach { task ->
            task.generateDescriptorSet = true
            task.builtins {
               remove("java")
            }
        }
    }
}

dependencies {
    implementation("commons-cli:commons-cli:1.4")

    implementation("javax.annotation:javax.annotation-api:1.2")
    implementation("io.grpc:grpc-protobuf:1.36.0")
    implementation("io.grpc:grpc-stub:1.36.0")

    testImplementation(kotlin("test-junit5"))
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.6.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.6.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile>() {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClassName = "MainKt"
}
