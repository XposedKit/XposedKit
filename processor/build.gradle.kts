plugins {
    `maven-publish`
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(libs.symbol.processing.api)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            artifactId = "xposedkit-processor"
            afterEvaluate {
                from(components["java"])
            }
        }
    }
}