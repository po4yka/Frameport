---
name: hilt-di
description: >
  Use when adding or reviewing Hilt DI wiring in Frameport: @HiltAndroidApp, @AndroidEntryPoint,
  @HiltViewModel, @Module/@InstallIn modules, @Binds/@Provides for platform adapters behind interfaces,
  dispatcher and ApplicationScope qualifiers, scoping (@Singleton/@ActivityRetainedScoped/@ViewModelScoped),
  multi-module Hilt across :app/:core/:camera/:feature/:native modules, testing with
  @HiltAndroidTest/@TestInstallIn/fakes, and Navigation 3 + hiltViewModel() integration.
  Triggers on: new ViewModel needing injection, adding a real platform adapter (BLE, Wi-Fi, USB),
  adding @Module to any :camera:* or :feature:* module, writing @HiltAndroidTest instrumented tests,
  WorkManager Hilt wiring, or diagnosing scope-related crashes.
---

# Hilt DI — Frameport

Hilt 2.59.2 · KSP 2.3.4 · Kotlin 2.3.10 · AGP 9.2.1 · compileSdk 37 · Navigation 3 1.1.2

## When to use this skill

- Adding a new `@HiltViewModel` in any `:feature:*` module
- Binding a real platform adapter (`FujiBleClient`, `CameraWifiConnector`, `CameraUsbConnector`) behind its interface when the NoOp stub in `CameraBindingsModule` is replaced
- Adding `ApplicationScope` to `:core:common:di`
- Writing `@HiltAndroidTest` / `@TestInstallIn` fake modules for instrumented UI tests
- Wiring WorkManager with `@HiltWorker` and `HiltWorkerFactory`
- Debugging silent scoping bugs (`hiltViewModel()` in Navigation 3 entryProvider)
- Adding a new Gradle module that participates in the Hilt graph

---

## 1. Mandatory KSP setup (every participating module)

`kapt` is fully deprecated for Hilt 2.51+. With Kotlin 2.x / AGP 9.x it may not function at all. Every Gradle module that declares Hilt annotations must add:

```kotlin
// build.gradle.kts (each module)
plugins {
    alias(libs.plugins.ksp)
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.android.compiler)          // NOT kapt

    // test source sets only:
    testImplementation(libs.hilt.android.testing)
    kspTest(libs.hilt.android.compiler)

    androidTestImplementation(libs.hilt.android.testing)
    kspAndroidTest(libs.hilt.android.compiler)
}
```

In `:app/build.gradle.kts`, add inside the `hilt {}` block:

```kotlin
hilt {
    enableExperimentalClasspathAggregation = true  // reduces errors in 20+ module graph
}
```

---

## 2. Application entry point

`app/FrameportApplication.kt` is already annotated correctly:

```kotlin
@HiltAndroidApp
class FrameportApplication : Application() {
    // When WorkManager Hilt integration lands, inject HiltWorkerFactory here (see §8)
}
```

`@HiltAndroidApp` must appear exactly once in the project, on the `Application` subclass in `:app`.

---

## 3. Dispatcher qualifiers and ApplicationScope

`:core:common:di/Dispatchers.kt` defines `@IoDispatcher`, `@DefaultDispatcher`, `@MainDispatcher`. `ApplicationScope` is currently absent — add it here when any Singleton-level coroutine scope is needed (foreground service coordination, camera session management):

```kotlin
// core/common/src/main/kotlin/dev/po4yka/frameport/core/common/di/Dispatchers.kt

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope           // ADD when needed
```

In `app/di/AppDispatchersModule.kt`, add the scope provision. Dispatchers themselves are stateless — no scope annotation needed on their provisions. `ApplicationScope` must be `@Singleton`:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object AppDispatchersModule {

    @Provides @IoDispatcher
    fun providesIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides @DefaultDispatcher
    fun providesDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default

    @Provides @MainDispatcher
    fun providesMainDispatcher(): CoroutineDispatcher = Dispatchers.Main

    // Add when ApplicationScope is needed:
    @Provides @Singleton @ApplicationScope
    fun providesApplicationScope(
        @DefaultDispatcher dispatcher: CoroutineDispatcher,
    ): CoroutineScope = CoroutineScope(SupervisorJob() + dispatcher)
}
```

---

## 4. Binding platform adapters — @Binds vs @Provides

### Current scaffold pattern (NoOp stubs in `app/di/CameraBindingsModule.kt`)

The current `CameraBindingsModule` uses `@Provides` for NoOp objects — correct for the scaffold phase:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object CameraBindingsModule {
    @Provides @Singleton
    fun providesFujiBleClient(): FujiBleClient = NoOpFujiBleClient()
    // ...
}
```

### Migration to real implementations — @Binds

When a real implementation lands (e.g., `RealFujiBleClient` in `:camera:bluetooth`), migrate to `@Binds` in an abstract module. `@Binds` is zero-overhead — it emits no extra allocation, unlike `@Provides`:

```kotlin
// camera/bluetooth/src/main/kotlin/dev/po4yka/frameport/camera/bluetooth/di/BluetoothModule.kt

@Module
@InstallIn(ActivityRetainedComponent::class)   // NOT SingletonComponent — see §5
abstract class BluetoothModule {

    @Binds
    @ActivityRetainedScoped
    abstract fun bindFujiBleClient(impl: RealFujiBleClient): FujiBleClient
}
```

`RealFujiBleClient` must be constructor-injectable (`@Inject constructor(...)`).

If a module needs both `@Binds` (abstract) and `@Provides` (concrete), use a companion object inside the abstract class:

```kotlin
@Module
@InstallIn(ActivityRetainedComponent::class)
abstract class BluetoothModule {

    @Binds @ActivityRetainedScoped
    abstract fun bindFujiBleClient(impl: RealFujiBleClient): FujiBleClient

    companion object {
        @Provides
        fun providesSomeExternalType(): ExternalType = ExternalType.create()
    }
}
```

---

## 5. Scoping rules for Frameport

| Binding | Correct scope | Reason |
|---|---|---|
| `CoroutineDispatcher` (IO, Default, Main) | none (unscoped) | Stateless; new instance is fine |
| `ApplicationScope` | `@Singleton` | Lives for app lifetime |
| `CameraRepository`, `CameraConnectionManager` | `@Singleton` | Shared domain state |
| `FujiBleClient` real impl | `@ActivityRetainedScoped` | Holds `BluetoothGatt`; must not outlive Activity stack |
| `CameraWifiConnector` real impl | `@ActivityRetainedScoped` | Holds Android `Network` handle and socket |
| `CameraUsbConnector` real impl | `@ActivityRetainedScoped` | Holds USB device handle |
| `NativeFujiSdk` / `JniNativeFujiSdk` | `@Singleton` | Single JNI bridge instance |
| Per-ViewModel state | `@ViewModelScoped` | Distinct per ViewModel instance |
| Foreground service resources | `@ServiceScoped` | Released with Service |

**Scope annotation must be on the `@Binds`/`@Provides` method, not on the interface declaration or implementation class.**

```kotlin
// CORRECT
@Binds @Singleton
abstract fun bindCameraRepo(impl: CameraRepositoryImpl): CameraRepository

// WRONG — @Singleton on the interface is ignored or is a compile error
@Singleton
interface CameraRepository { ... }
```

**`@ActivityRetainedScoped` for objects with OS-level handles:** `ActivityRetainedComponent` outlives configuration changes but is destroyed with the Activity stack. Objects with `BluetoothGatt`, `Network`, or socket file descriptors must implement lifecycle-aware cleanup (close in `onDestroy` via a lifecycle observer or `Closeable` pattern). Do not use `@Singleton` for these — a singleton holding a `BluetoothGatt` reference will outlive the Activity, delaying resource release.

---

## 6. ViewModels — @HiltViewModel

Every `:feature:*` ViewModel uses `@HiltViewModel` with constructor injection. The ViewModel itself does not need a separate `@Module` entry — Hilt generates the factory automatically.

```kotlin
// feature/connection/src/main/kotlin/dev/po4yka/frameport/feature/connection/ConnectionViewModel.kt

@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val connectionManager: CameraConnectionManager,   // interface, not JniNativeFujiSdk
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
    private val savedStateHandle: SavedStateHandle,           // free from Hilt; no module needed
) : ViewModel() { ... }
```

**Layer invariant:** ViewModels inject domain interfaces (`CameraRepository`, `CameraConnectionManager`), never `NativeFujiSdk` or `JniNativeFujiSdk` directly. The JNI binding in `CameraBindingsModule` is the single correct injection point; it flows upward through domain-layer interfaces only.

### Navigation 3 + hiltViewModel() — ViewModel scoping

`hiltViewModel()` resolves the ViewModel from the nearest `LocalViewModelStoreOwner`. In Navigation 3, per-entry `LocalViewModelStoreOwner` wiring is provided by `rememberViewModelStoreNavEntryDecorator()` from the separate `androidx.lifecycle:lifecycle-viewmodel-navigation3` artifact (package `androidx.lifecycle.viewmodel.navigation3`). You must add this decorator to `NavDisplay`'s `entryDecorators` for `hiltViewModel()` to be scoped (and cleared) per `NavEntry`. See the `navigation3` skill for the full decorator wiring.

The current project's `FrameportNavHost` uses the plain-lambda form for `entryProvider`:

```kotlin
// app or feature nav graph host (current project pattern)

NavDisplay(
    backStack = backStack,
    onBack = { if (backStack.size > 1) backStack.removeLast() },
    entryProvider = { key ->
        NavEntry(key) { destination ->
            when (destination) {
                FrameportDestination.CameraConnect ->
                    CameraConnectRoute()  // hiltViewModel() inside resolves per-entry
                // ...
                else -> HomeRoute(onNavigate = backStack::navigate)
            }
        }
    },
)
```

`NavDisplay` adds **no** decorators by default, so you must supply them explicitly. Order matters — `rememberSaveableStateHolderNavEntryDecorator()` (so `rememberSaveable` state survives) **must be first**, before `rememberViewModelStoreNavEntryDecorator()`:

```kotlin
entryDecorators = listOf(
    rememberSaveableStateHolderNavEntryDecorator(),  // MUST be first
    rememberViewModelStoreNavEntryDecorator(),       // per-entry ViewModelStoreOwner
)
```

If after a navigation pop the ViewModel is not cleared, verify that `rememberViewModelStoreNavEntryDecorator()` is present in `entryDecorators`, the `androidx.lifecycle:lifecycle-viewmodel-navigation3` dependency is on the classpath, and `hiltViewModel()` is called inside the `NavEntry` content lambda, not outside it.

### ViewModel with runtime parameters — @HiltViewModel(assistedFactory)

For ViewModels needing a navigation argument (e.g., a camera session ID) unavailable at graph construction:

```kotlin
@HiltViewModel(assistedFactory = GalleryViewModel.Factory::class)
class GalleryViewModel @AssistedInject constructor(
    @Assisted val sessionId: String,
    private val galleryRepository: CameraMediaRepository,
) : ViewModel() {

    @AssistedFactory
    interface Factory {
        fun create(sessionId: String): GalleryViewModel
    }
}

// At call site:
val vm = hiltViewModel<GalleryViewModel, GalleryViewModel.Factory> { factory ->
    factory.create(sessionId)
}
```

---

## 7. Multi-module Hilt — :camera:* and :feature:* modules

Frameport's `:feature:*` and `:camera:*` modules are regular Gradle library modules (not Android Dynamic Feature Modules), so standard `@Module @InstallIn` works without `@EntryPoint` / component-dependencies patterns.

Rules:
- Every module with `@Module` or `@HiltViewModel` must have `ksp(hilt-android-compiler)` in its own `build.gradle.kts`.
- `@InstallIn(SingletonComponent::class)` is the typical target for domain-layer bindings; `ActivityRetainedComponent` for platform adapters.
- Module-local `@Qualifier` annotations belong in that module's `di/` package. Shared qualifiers (dispatchers, `ApplicationScope`) live in `:core:common:di`.
- `@EntryPoint` / `EntryPointAccessors.fromApplication()` are needed only when injecting into non-Hilt-managed classes (custom `View`, `ContentProvider`, legacy code). Avoid proliferating these.

---

## 8. WorkManager Hilt integration

When WorkManager is added to Frameport (not yet used):

1. Add entries to `gradle/libs.versions.toml` (neither exists yet):
   ```toml
   [versions]
   hiltWork = "1.3.0"   # androidx.hilt group, separate from com.google.dagger

   [libraries]
   androidx-hilt-work     = { module = "androidx.hilt:hilt-work",     version.ref = "hiltWork" }
   androidx-hilt-compiler-work = { module = "androidx.hilt:hilt-compiler", version.ref = "hiltWork" }
   ```
   Then in the module that hosts Workers:
   ```kotlin
   implementation(libs.androidx.hilt.work)
   ksp(libs.androidx.hilt.compiler.work)   // androidx.hilt:hilt-compiler — distinct from com.google.dagger:hilt-compiler
   ```

2. Annotate the Worker:
   ```kotlin
   @HiltWorker
   class MediaSyncWorker @AssistedInject constructor(
       @Assisted appContext: Context,
       @Assisted workerParams: WorkerParameters,
       private val mediaRepo: CameraMediaRepository,
   ) : CoroutineWorker(appContext, workerParams) { ... }
   ```

3. Remove the default WorkManager initializer from `AndroidManifest.xml` and wire `HiltWorkerFactory` in `FrameportApplication`:
   ```kotlin
   @HiltAndroidApp
   class FrameportApplication : Application(), Configuration.Provider {

       @Inject lateinit var workerFactory: HiltWorkerFactory

       override val workManagerConfiguration: Configuration
           get() = Configuration.Builder()
               .setWorkerFactory(workerFactory)
               .build()
   }
   ```

---

## 9. Testing

### Unit tests (ViewModel only) — no Hilt

Follow the existing pattern in `feature/connection/ConnectionViewModelTest.kt`: construct the ViewModel directly with fake constructor arguments. No Hilt component involved.

```kotlin
class ConnectionViewModelTest {
    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @Test
    fun `initial state is disconnected`() = runTest {
        val vm = ConnectionViewModel(
            connectionManager = FakeCameraConnectionManager(),
            ioDispatcher = testDispatcher,
            savedStateHandle = SavedStateHandle(),
        )
        // assert on vm.uiState
    }
}
```

### Instrumented / Compose UI tests — @HiltAndroidTest

```kotlin
@HiltAndroidTest
class ConnectionScreenTest {

    @get:Rule(order = 0)   // order = 0 is mandatory; Hilt must initialize before Compose
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createEmptyComposeRule()

    @Inject lateinit var fakeConnectionManager: FakeCameraConnectionManager

    @Before
    fun setUp() {
        hiltRule.inject()   // populates @Inject fields; MUST be called explicitly
    }

    @Test
    fun connectButton_triggersConnectionFlow() {
        composeRule.setContent {
            val vm = hiltViewModel<ConnectionViewModel>()
            ConnectionScreen(viewModel = vm)
        }
        // interact and assert
    }
}
```

`HiltAndroidRule` must be `order = 0`. Placing `ComposeTestRule` at `order = 0` and `HiltAndroidRule` at `order = 1` causes a not-ready Hilt component when the Compose rule starts the Activity.

### Fake modules — @TestInstallIn (preferred)

For project-wide fake adapters used across multiple test classes, use `@TestInstallIn` in the `androidTest` source set. This replaces the module for the entire source set and avoids per-class component regeneration (unlike `@UninstallModules`).

```kotlin
// app/src/androidTest/kotlin/dev/po4yka/frameport/di/FakeCameraModule.kt

@Module
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [CameraBindingsModule::class],
)
abstract class FakeCameraModule {

    @Binds @Singleton
    abstract fun bindFujiBleClient(fake: FakeFujiBleClient): FujiBleClient

    @Binds @Singleton
    abstract fun bindCameraWifiConnector(fake: FakeCameraWifiConnector): CameraWifiConnector

    @Binds @Singleton
    abstract fun bindNativeFujiSdk(fake: FakeNativeFujiSdk): NativeFujiSdk

    // ... bind remaining interfaces to fakes
}
```

Fake implementations must carry the same scope annotation as the production binding they replace, otherwise the graph is inconsistent.

Use `@UninstallModules` only when `@TestInstallIn` is insufficient (e.g., a single test class needs a different fake than the rest). It triggers new component generation per test class, multiplying KSP/Dagger processing time.

### @BindValue — inline field binding

For simple per-test overrides without a module:

```kotlin
@HiltAndroidTest
class GalleryScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @BindValue @JvmField   // @JvmField is required; private fields fail at compile time
    val fakeRepo: CameraMediaRepository = FakeCameraMediaRepository()
}
```

### HiltTestActivity and CustomTestRunner

For Compose UI tests, create an empty `@AndroidEntryPoint` Activity in the `androidTest` source set:

```kotlin
// app/src/androidTest/kotlin/dev/po4yka/frameport/HiltTestActivity.kt
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
```

Configure `CustomTestRunner` to use `HiltTestApplication`:

```kotlin
class CustomTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?,
        className: String?,
        context: Context?,
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
```

Register it in `app/build.gradle.kts`:

```kotlin
android {
    defaultConfig {
        testInstrumentationRunner = "dev.po4yka.frameport.CustomTestRunner"
    }
}
```

---

## 10. Key pitfalls

| Pitfall | Fix |
|---|---|
| Using `kapt` instead of `ksp` | Replace all `kapt(hilt-android-compiler)` with `ksp(hilt-android-compiler)`. With Kotlin 2.x/AGP 9.x, kapt may not function at all. |
| Missing `ksp(hilt-android-compiler)` in a non-app module | Every module declaring `@Module` or `@HiltViewModel` needs the KSP processor in its own `build.gradle.kts`. |
| Scope annotation on the interface, not the method | `@Singleton` on an `interface` declaration is ignored or a compile error. Annotate the `@Binds`/`@Provides` method. |
| `@Singleton` for objects holding OS handles (`BluetoothGatt`, `Network`, socket fd) | Use `@ActivityRetainedScoped`; implement lifecycle-aware cleanup so handles are released on Activity destruction. |
| ViewModel injecting `NativeFujiSdk` or `JniNativeFujiSdk` directly | Inject domain interfaces (`CameraConnectionManager`, `CameraRepository`) only. The JNI binding flows through the domain layer. |
| `hiltViewModel()` in Navigation 3 called outside the `NavEntry` content lambda | ViewModel resolves from the Activity `ViewModelStoreOwner` rather than the per-entry one. Always call `hiltViewModel()` inside the `NavEntry` content block. `NavDisplay` sets up per-entry `LocalViewModelStoreOwner` via its default `SceneSetupNavEntryDecorator`; do not pass `entryDecorators = emptyList()`. |
| `HiltAndroidRule` not at `order = 0` | Hilt component not ready when Compose rule starts Activity. Set `@get:Rule(order = 0)` on `HiltAndroidRule`. |
| Not calling `hiltRule.inject()` in `@Before` | `@Inject`-annotated fields in `@HiltAndroidTest` classes are not populated automatically. |
| `@BindValue` on a private field | Dagger requires at least package-private visibility; `@JvmField` is required. |
| `@UninstallModules` instead of `@TestInstallIn` | `@UninstallModules` regenerates a Hilt component per test class; use `@TestInstallIn` for project-wide fakes. |
| Forgetting `ApplicationScope` and using `GlobalScope` | `GlobalScope` is forbidden per project discipline. Add `ApplicationScope` qualifier to `:core:common:di` and bind it `@Singleton`. |
| `@Provides` (object fun) for project-owned interface bindings | Use `@Binds` (abstract fun) for zero-overhead interface-to-impl binding once the real implementation is constructor-injectable. |
| Missing `hiltViewModel()` import | Ensure `androidx.hilt:hilt-navigation-compose` (or the Navigation 3 equivalent) is on the classpath; the function is not in `hilt-android` itself. |

---

## References

- [Dependency injection with Hilt — Android Developers](https://developer.android.com/training/dependency-injection/hilt-android)
- [Hilt in multi-module apps — Android Developers](https://developer.android.com/training/dependency-injection/hilt-multi-module)
- [Hilt testing guide — Android Developers](https://developer.android.com/training/dependency-injection/hilt-testing)
- [Use Hilt with other Jetpack libraries — Android Developers](https://developer.android.com/training/dependency-injection/hilt-jetpack)
- [Best practices for coroutines in Android — Android Developers](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [Modular Navigation Recipe (Hilt + Navigation 3) — Android Developers](https://developer.android.com/guide/navigation/navigation-3/recipes/modular-hilt)
