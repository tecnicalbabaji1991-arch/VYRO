plugins {
  alias(libs.plugins.android.library)
}

val sdkRoot = System.getenv("ANDROID_SDK_ROOT") ?: System.getenv("ANDROID_HOME")
val hasNativeToolchain = sdkRoot != null &&
  file("$sdkRoot/ndk").exists() &&
  file("$sdkRoot/cmake").exists()
val enableNativeBuild = providers.gradleProperty("vyro.enableNativeBuild")
  .map { it.toBoolean() }
  .orElse(hasNativeToolchain)
  .get()

android {
  namespace = "com.vyro.bridge.jni"
  compileSdk { version = release(36) { minorApiLevel = 1 } }

  defaultConfig {
    minSdk = 30
    if (enableNativeBuild) {
      externalNativeBuild {
        cmake {
          cppFlags("-std=c++17")
        }
      }
    }
  }

  if (enableNativeBuild) {
    externalNativeBuild {
      cmake {
        path = file("../../CMakeLists.txt")
        version = "3.22.1"
      }
    }
  }

  compileOptions {
    sourceCompatibility = JavaVersion.VERSION_11
    targetCompatibility = JavaVersion.VERSION_11
  }
}

dependencies {
  implementation(project(":core:common"))
  implementation(project(":core:model"))
  implementation(project(":core:contracts"))
  implementation(libs.kotlinx.coroutines.core)
  implementation(libs.kotlinx.coroutines.android)
  testImplementation(libs.junit)
  testImplementation(libs.kotlinx.coroutines.test)
}
