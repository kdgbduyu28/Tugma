plugins {
    alias(libs.plugins.kotlin.jvm)
    application
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":sonos-core"))
    implementation(project(":cast-core"))
}

application {
    mainClass.set("com.timborja.tugma.cli.MainKt")
    applicationName = "tugma"
}

tasks.named<JavaExec>("run") {
    standardInput = System.`in`
}
