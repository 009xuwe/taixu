plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.wkbin.taixu.feature.terminal"
    resourcePrefix = "terminal_"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.bundles.koin.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":feature:components"))
    implementation(project(":runtime"))
    // Termux TerminalView (pairs with runtime's terminal-emulator AAR).
    implementation(libs.termux.terminal.view)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.core)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
