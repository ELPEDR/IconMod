plugins { id("com.android.application") }

// Version comes from gradle.properties (APP_VERSION / APP_VERSION_CODE)
val appVersion = (project.findProperty("APP_VERSION") as String?) ?: "1.00"
val appVersionCode = ((project.findProperty("APP_VERSION_CODE") as String?) ?: "1").toInt()
android {
    namespace = "com.example.iconmod"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.example.iconmod"
        minSdk = 29
        targetSdk = 34
        versionCode = appVersionCode
        versionName = appVersion
    }
    buildFeatures { buildConfig = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies { compileOnly("de.robv.android.xposed:api:82") }
