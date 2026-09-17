plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "top.wkbin.taixu.feature.navigation"
    resourcePrefix = "navigation_"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    buildFeatures { compose = true }
    compileOptions {
        // miuix-nav 以 JVM 21 字节码发布，其 entry{}/rememberNavBackStack 为 inline 函数，
        // 无法内联进 JVM 17 目标——本模块必须对齐到 21（AGP/D8 会正常降级处理，不影响产物）
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21) }
}

dependencies {
    implementation(project(":core:common"))
    implementation(project(":core:model"))
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(project(":feature:components"))
    implementation(project(":feature:theme"))
    // LocalLiquidGlassBackdrop 的类型 LayerBackdrop 来自该库，类型推断需要它在 classpath 上
    implementation(libs.backdrop)
    implementation(project(":feature:home"))
    implementation(project(":feature:chat"))
    implementation(project(":feature:terminal"))
    implementation(project(":feature:workspace"))
    implementation(project(":feature:workflow"))
    implementation(project(":feature:settings"))
    implementation(project(":feature:developer"))
    implementation(project(":feature:custom_iteration"))
    implementation(project(":feature:browser"))
    implementation(project(":feature:git"))
    // miuix-nav：自研 Compose 导航运行时（零依赖 androidx.navigation3），
    // 内建 HyperOS 转场、1:1 跟手侧滑返回手势、按 entry 的 ViewModel/Saveable 状态作用域
    implementation(libs.miuix.nav)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
