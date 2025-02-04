plugins {
    alias(libs.plugins.flixclusive.feature)
    alias(libs.plugins.flixclusive.compose)
    alias(libs.plugins.flixclusive.destinations)
}

android {
    namespace = "com.flixclusive.feature.mobile.about"

}

dependencies {
    implementation(projects.core.ui.mobile)
    implementation(projects.data.configuration)

    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui)
}