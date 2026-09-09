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
        assets.directories.add("../tools/mvu-probe/build/android-assets")
    }
}

android.sourceSets.named("main") {
    assets.directories.add("../tools/mvu-probe/build/app-assets")
    assets.directories.add("../tools/web-runtime/build/app-assets")
}

val installWebDependencies by tasks.registering(Exec::class) {
    workingDir(rootProject.file("tools/web-runtime"))
    inputs.files("../tools/web-runtime/package.json", "../tools/web-runtime/package-lock.json")
    outputs.file(rootProject.file("tools/web-runtime/node_modules/.package-lock.json"))
    if (System.getProperty("os.name").startsWith("Windows")) commandLine("cmd", "/c", "npm", "ci")
    else commandLine("npm", "ci")
}
val prepareWebRuntime by tasks.registering(Exec::class) {
    dependsOn(installWebDependencies)
    workingDir(rootProject.file("tools/web-runtime"))
    inputs.files(rootProject.fileTree("tools/web-runtime") { exclude("build/**", "node_modules/**") })
    outputs.dir(rootProject.file("tools/web-runtime/build/app-assets"))
    commandLine("node", "build.mjs")
}
tasks.named("preBuild") { dependsOn(prepareWebRuntime) }

// Android and desktop tests execute the same Kotlin host with the matching native engine.
configurations.matching { it.name.endsWith("UnitTestRuntimeClasspath") }.configureEach {
    resolutionStrategy.dependencySubstitution {
        substitute(module("io.github.dokar3:quickjs-kt-android"))
            .using(module("io.github.dokar3:quickjs-kt-jvm:${libs.versions.quickjs.get()}"))
    }
}

val installMvuDependencies by tasks.registering(Exec::class) {
    workingDir(rootProject.file("tools/mvu-probe"))
    inputs.files("../tools/mvu-probe/package.json", "../tools/mvu-probe/package-lock.json")
    outputs.file(rootProject.file("tools/mvu-probe/node_modules/.package-lock.json"))
    if (System.getProperty("os.name").startsWith("Windows")) commandLine("cmd", "/c", "npm", "ci")
    else commandLine("npm", "ci")
}

val prepareMvuRuntime by tasks.registering(Exec::class) {
    dependsOn(installMvuDependencies)
    workingDir(rootProject.file("tools/mvu-probe"))
    inputs.files(rootProject.fileTree("tools/mvu-probe") {
        exclude("build/**", "node_modules/**")
    })
    outputs.dir(rootProject.file("tools/mvu-probe/build/app-assets"))
    outputs.file(rootProject.file("tools/mvu-probe/build/android-assets/mvu/runtime.js"))
    commandLine("node", "build.mjs")
}
tasks.named("preBuild") { dependsOn(prepareMvuRuntime) }

android.sourceSets.configureEach {
    if (name == "test" || name == "androidTest") kotlin.directories.add("src/sharedTest/java")
}

tasks.withType<Test>().configureEach {
    systemProperty("mvuProbeAssets", rootProject.layout.projectDirectory.dir("tools/mvu-probe/build/android-assets").asFile.path)
    systemProperty("webRuntimeAssets", rootProject.layout.projectDirectory.dir("tools/web-runtime/build/app-assets/web").asFile.path)
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
    implementation(libs.webkit)
    implementation(project(":content-core"))
    implementation(project(":conversation-core"))
    implementation(project(":model-gateway"))
    implementation(libs.activity.compose)
    implementation(libs.datastore.preferences)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.quickjs)
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
