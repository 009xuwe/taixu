plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.wkbin.taixu.feature.onboarding"
    resourcePrefix = "onboarding_"
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
    implementation(project(":core:model"))
    implementation(project(":core:datastore"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.bundles.koin.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(project(":feature:components"))
    debugImplementation(libs.androidx.compose.ui.tooling)
}
