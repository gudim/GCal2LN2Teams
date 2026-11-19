import java.util.Properties

plugins {
    // AGP 9 компилирует Kotlin сам (built-in Kotlin), плагин kotlin-android не нужен.
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
}

kotlin {
    jvmToolchain(21)
}

/** Client id регистрации Teams (публичный идентификатор, не секрет). */
val DEFAULT_TEAMS_CLIENT_ID = "bcc96adb-7ce5-40c6-a469-818ff24ddda5"

/** Значение из mobile/local.properties (не в git) или из Gradle-свойств (~/.gradle/gradle.properties, -P). */
val localProperties = Properties().apply {
    val file = rootProject.layout.projectDirectory.file("local.properties").asFile
    if (file.isFile) file.inputStream().use { load(it) }
}

fun buildProperty(name: String): String? =
    (localProperties.getProperty(name) ?: providers.gradleProperty(name).orNull)?.trim()

/** Строка как литерал Java для buildConfigField. */
fun javaString(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

android {
    namespace = "app.gcaltolotus"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "app.gcaltolotus"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        versionCode = 4
        versionName = "0.1.3"

        // Регистрация Teams в Azure: публичный клиент (Mobile and desktop, redirect http://localhost),
        // секрета в приложении нет. Client id публичен; свойство teams.clientId его переопределяет,
        // пустое значение — Teams «не настроен».
        buildConfigField(
            "String",
            "TEAMS_CLIENT_ID",
            javaString(buildProperty("teams.clientId") ?: DEFAULT_TEAMS_CLIENT_ID),
        )
    }

    // Релизный ключ: release.storeFile/storePassword/keyAlias/keyPassword в local.properties.
    // Без них release-сборка собирается неподписанной. Ключ не терять: обновление
    // установленного приложения возможно только сборкой с той же подписью.
    val releaseStore = buildProperty("release.storeFile")
    signingConfigs {
        if (releaseStore != null) {
            create("release") {
                storeFile = file(releaseStore)
                storePassword = buildProperty("release.storePassword")
                keyAlias = buildProperty("release.keyAlias")
                keyPassword = buildProperty("release.keyPassword")
            }
        }
    }

    buildTypes {
        release {
            if (releaseStore != null) signingConfig = signingConfigs.getByName("release")
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // Локальные JVM-тесты не трогают Android-фреймворк; заглушки android.jar не бросают исключения.
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation(project(":shared"))
    implementation(libs.androidx.activity.compose)
    // ColorScheme и dynamic*ColorScheme для Material You (та же версия, что в shared).
    implementation(libs.compose.material3)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.browser)
    implementation(libs.play.services.auth)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)
    implementation(libs.sqldelight.android.driver)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
