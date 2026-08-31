plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.compose.compiler) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
}

subprojects {
    val communityCard = providers.systemProperty("communityCard")
    tasks.withType<Test>().configureEach {
        communityCard.orNull?.let { path -> systemProperty("communityCard", path) }
    }
}
