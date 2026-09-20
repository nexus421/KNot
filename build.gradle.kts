plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    id("io.ktor.plugin") version "3.6.0"
}

group = "bayern.kickner"
version = "1.0.0"

repositories {
    mavenCentral()
    maven {
        name = "nexus421MavenReleases"
        url = uri("https://maven.kickner.bayern/releases")
    }
}

dependencies {
    implementation("bayern.kickner:Klogger:0.1.0")
    implementation("bayern.kickner:KotNexLib:4.4.1")

    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-cio")
    implementation("io.ktor:ktor-server-body-limit")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("jakarta.mail:jakarta.mail-api:2.1.5")
    runtimeOnly("org.eclipse.angus:angus-mail:2.0.5")
    // Ktor logs through SLF4J; without a provider its warnings would be lost and SLF4J complains at startup
    runtimeOnly("org.slf4j:slf4j-simple:2.0.18")

    testImplementation(kotlin("test"))
    testImplementation("io.ktor:ktor-server-test-host")
}

kotlin {
    jvmToolchain {
        languageVersion.set(JavaLanguageVersion.of(25))
        vendor.set(JvmVendorSpec.AMAZON)
    }
}

application {
    mainClass.set("bayern.kickner.knot.MainKt")
}

ktor {
    fatJar {
        archiveFileName.set("knot.jar")
    }
}

// Read at runtime for the startup mail and the log banner (Package.implementationVersion)
tasks.withType<Jar>().configureEach {
    manifest {
        attributes["Implementation-Version"] = project.version
    }
}

tasks.test {
    useJUnitPlatform()
}
