plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.zvensmoluya.tavernplayer"
    compileSdk = 37

    defaultConfig {
        applicationId = "io.github.zvensmoluya.tavernplayer"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    sourceSets.named("androidTest") {
        assets.directories.add("src/test/resources")
        assets.directories.add("build/manualAndroidTestAssets")
    }
}

val preparePressureCardAndroidTestAsset by tasks.registering(Copy::class) {
    from(rootProject.layout.projectDirectory.file("source/复杂压测卡.png")) { rename { "pressure-card.png" } }
    from(rootProject.layout.projectDirectory.file("source/古茗医生.png")) { rename { "doctor-card.png" } }
    from(rootProject.layout.projectDirectory.file("source/real复杂压测卡.png")) { rename { "second-pressure-card.png" } }
    from(rootProject.layout.projectDirectory.file("source/夏瑾 天琴座 Beta 3.4.json")) { rename { "community-preset.json" } }
    into(layout.buildDirectory.dir("manualAndroidTestAssets"))
}

tasks.matching { task ->
    task.name in setOf("generateDebugAndroidTestAssets", "mergeDebugAndroidTestAssets",
        "generateDebugAndroidTestLintModel", "lintAnalyzeDebugAndroidTest")
}.configureEach {
    dependsOn(preparePressureCardAndroidTestAsset)
}

dependencies {
    implementation(project(":content-core"))
    implementation(project(":conversation-core"))
    implementation(project(":model-gateway"))
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.play.services.code.scanner)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.mockwebserver)
    testImplementation(composeBom)
    testImplementation(libs.compose.ui.test.junit4)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(composeBom)
    androidTestImplementation(libs.compose.ui.test.junit4)
}
