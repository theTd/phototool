plugins {
    kotlin("jvm") version "2.0.0"
}

group = "org.starrel"
version = "1.0.0-SNAPSHOT"

repositories {
    mavenCentral()
}

val kotlinScriptUtilVersion = "1.8.22"

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-cli-jvm:0.3.6")

    // https://mvnrepository.com/artifact/commons-io/commons-io
    implementation("commons-io:commons-io:2.17.0")

    // scripting
    api(kotlin("script-util", kotlinScriptUtilVersion))
    api(kotlin("scripting-jvm-host"))
//    api(kotlin("scripting-dependencies"))

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
}
