plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.shadow)
}

//noinspection UseTomlInstead
dependencies {
    compileOnly("io.github.skylot:jadx-core:1.5.6")
    compileOnly("io.github.skylot:jadx-cli:1.5.6")
    compileOnly("io.github.skylot:jadx-gui:1.5.6")
    compileOnly("io.github.skylot:jadx-plugins-tools:1.5.6")
    compileOnly("org.slf4j:slf4j-api:2.0.19")
    compileOnly("com.google.code.gson:gson:2.14.0")
}

tasks {
    jar {
        archiveFileName.set("xposedkit-extension.jar")
    }

    val shadowJar = withType(com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar::class) {
        minimize()
        archiveClassifier.set("") // remove '-all' suffix
    }

    // copy result jar into "build/dist" directory
    register<Copy>("dist") {
        group = "jadx-plugin"
        dependsOn(shadowJar)
        dependsOn(withType(Jar::class))

        from(shadowJar)
        into(layout.buildDirectory.dir("dist"))
    }
}