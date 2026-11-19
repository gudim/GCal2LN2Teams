plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp.library)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.sqldelight)
}

kotlin {
    jvmToolchain(21)

    jvm()

    android {
        namespace = "app.gcaltolotus.shared"
        compileSdk = libs.versions.android.compileSdk.get().toInt()
        minSdk = libs.versions.android.minSdk.get().toInt()
        // Нужно, чтобы строки Compose Resources попали в APK.
        androidResources { enable = true }
    }

    // iosX64 (симулятор на Intel-маках) не объявлен: Compose Multiplatform 1.11+ его больше не
    // поддерживает, а в Kotlin эта цель устарела.
    listOf(iosArm64(), iosSimulatorArm64()).forEach { target ->
        target.binaries.framework {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.compose.runtime)
            implementation(libs.compose.foundation)
            implementation(libs.compose.material3)
            implementation(libs.compose.ui)
            implementation(libs.compose.components.resources)
            implementation(libs.compose.ui.backhandler)

            implementation(libs.ktor.client.core)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.kotlinx.datetime)
            implementation(libs.sqldelight.runtime)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.android.driver)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.okhttp)
            implementation(libs.sqldelight.sqlite.driver)
        }
        iosMain.dependencies {
            implementation(libs.ktor.client.darwin)
            implementation(libs.sqldelight.native.driver)
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "app.gcaltolotus.shared.resources"
}

sqldelight {
    databases {
        create("SyncDatabase") {
            packageName.set("app.gcaltolotus.db")
        }
    }
}

// Тесты читают приёмочную спецификацию движка прямо из репозитория (spec/golden/*.json,
// spec/links-export.md), без копий. Путь передаётся системным свойством gcaltolotus.specDir.
// Фикстуры ответов API (spec/fixtures/*.json) для тестов адаптеров — свойство gcaltolotus.fixturesDir.
val specDir = rootProject.layout.projectDirectory.dir("../spec")
val fixturesDir = rootProject.layout.projectDirectory.dir("../spec/fixtures")
tasks.withType<Test>().configureEach {
    inputs.dir(specDir).withPropertyName("specDir").withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.dir(fixturesDir).withPropertyName("fixturesDir").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("gcaltolotus.specDir", specDir.asFile.canonicalPath)
    systemProperty("gcaltolotus.fixturesDir", fixturesDir.asFile.canonicalPath)
}
