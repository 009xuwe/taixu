plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "top.wkbin.taixu.core.database"
    resourcePrefix = "database_"
    compileSdk = 37
    defaultConfig { minSdk = 29 }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // 迁移测试（MigrationTestHelper）从测试资产读取导出的 schema JSON
    sourceSets {
        named("test") {
            assets.srcDir("$projectDir/schemas")
        }
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:security"))
    implementation(libs.bundles.room)
    ksp(libs.androidx.room.compiler)
    implementation(libs.koin.core)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.bundles.test.robolectric)
    testImplementation(libs.kotlinx.coroutines.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation("org.xerial:sqlite-jdbc:3.50.3.0")
    testImplementation(libs.bundles.asm.test)
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "true")
}
