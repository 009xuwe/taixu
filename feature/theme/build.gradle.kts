plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.wkbin.taixu.feature.theme"
    resourcePrefix = "theme_"
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
    implementation(libs.androidx.core.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui)
    // AndroidLiquidGlass (Kyant0)：澄明(液态玻璃)主题的毛玻璃折射效果
    implementation(libs.backdrop)
    implementation(libs.kotlinx.coroutines.core)

    debugImplementation(libs.androidx.compose.ui.tooling)
}
