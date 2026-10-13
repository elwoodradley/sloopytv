plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    // XmlPull interfaces at compile time only: Android provides them in the framework, desktop
    // builds and tests add kxml2 themselves.
    compileOnly(libs.kxml2)
    testImplementation(libs.kxml2)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit)
}
