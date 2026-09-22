plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.wkbin.taixu.feature.settings"
    resourcePrefix = "settings_"
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.activity.compose)
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.bundles.compose.ui)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.bundles.koin.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":feature:components"))
    implementation(project(":feature:theme"))
    // LocalLiquidGlassBackdrop 的类型 LayerBackdrop 来自该库，类型推断需要它在 classpath 上
    implementation(libs.backdrop)
    implementation(libs.bundles.coil)
    implementation(project(":core:datastore"))
    implementation(project(":core:database"))
    implementation(project(":core:network"))
    implementation(project(":runtime"))
    implementation(project(":tools"))
    implementation(project(":harness"))
    debugImplementation(libs.androidx.compose.ui.tooling)
    testImplementation(libs.junit)
}
