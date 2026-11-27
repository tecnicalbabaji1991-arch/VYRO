pluginManagement {
  repositories {
    google {
      content {
        includeGroupByRegex("com\\.android.*")
        includeGroupByRegex("com\\.google.*")
        includeGroupByRegex("androidx.*")
      }
    }
    mavenCentral()
    gradlePluginPortal()
  }
}

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
  }
}

rootProject.name = "VYRO"

include(
  ":app",
  ":core:common",
  ":core:model",
  ":core:contracts",
  ":core:database",
  ":core:designsystem",
  ":feature:home",
  ":feature:media",
  ":feature:editor",
  ":platform:android-media",
  ":bridge:jni",
)
