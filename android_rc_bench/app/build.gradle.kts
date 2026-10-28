plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "dev.drone.rcbench"
    compileSdk = 36
    defaultConfig {
        applicationId = "dev.drone.rcbench"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1-disarmed"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { abortOnError = true; warningsAsErrors = false }
    testOptions { unitTests.isReturnDefaultValues = false }
}
dependencies { testImplementation("junit:junit:4.13.2") }
