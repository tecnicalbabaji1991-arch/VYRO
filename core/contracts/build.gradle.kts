plugins {
  alias(libs.plugins.android.library)
}

android {
  namespace = "com.vyro.core.contracts"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 30
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(project(":core:common"))
  implementation(project(":core:model"))
  implementation(libs.kotlinx.coroutines.core)
  testImplementation(libs.junit)
}
