Patch for https://github.com/kohesive/injekt to make singleton factory thread safe

## Usage
Add `jitpack.io` repository to your root `build.gradle.kts` file:
```gradle.kts
dependencyResolutionManagement {
    repositories {
        ...
        maven(url = "https://www.jitpack.io")
    }
}
```

Add library to dependencies
```gradle.kts
dependencies {
    implementation("com.github.MeLoseAgain:kotlin-injekt:f64a1f340d")
}
```

Call `patchInjekt()` at the start of your application. For example:
```kotlin
class App : Application() {

    override fun onCreate() {
        patchInjekt()
        // Rest of the code below
    }
}
```
