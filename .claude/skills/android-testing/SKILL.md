---
name: android-testing
description: >
  Use when authoring or reviewing tests in any Frameport module — JVM unit tests for ViewModels, use-cases, or Flow pipelines (runTest, Turbine, MockK); Compose UI tests with createAndroidComposeRule and HiltAndroidRule; Robolectric tests for Android-framework code in :core:permissions or Room migrations; instrumented MediaStore tests in :camera:media; or configuring Hilt test infrastructure (@HiltAndroidTest, @TestInstallIn, HiltTestActivity). Also triggers when wiring libs.versions.toml entries for turbine, mockk, or compose-ui-test, or when asserting layer-boundary rules (no concrete BLE/Wi-Fi/USB/JNI classes in :feature:* tests).
---

# android-testing skill

## When to use

Invoke this skill when:
- Writing or reviewing a ViewModel unit test, use-case test, or Flow-emission test in any `:feature:*`, `:camera:*`, or `:core:*` module.
- Setting up or debugging Hilt test infrastructure (`@HiltAndroidTest`, `HiltAndroidRule`, `@TestInstallIn`, `HiltTestActivity`).
- Authoring Compose UI tests with `createAndroidComposeRule` and semantics-based assertions.
- Adding fakes to `:core:testing` (e.g., `FakeFujiBleClient`, `FakeCameraWifiConnector`).
- Configuring version catalog entries for `turbine`, `mockk`, or `ui-test-*` artifacts.
- Checking whether a test belongs in JVM (`test/`), Robolectric (`test/` with `@Config`), or instrumented (`androidTest/`) source sets.

---

## Module map

| Test type | Source set | Module examples |
|---|---|---|
| ViewModel / use-case / Flow unit | `test/` (JVM) | `:feature:connection`, `:camera:domain`, `:camera:bluetooth` |
| Robolectric (Android-framework, no BLE/Wi-Fi/JNI) | `test/` (JVM, `@RunWith(RobolectricTestRunner::class)`) | `:core:permissions`, `:camera:data` (Room migrations) |
| Compose UI | `androidTest/` (instrumented) | `:feature:connection`, `:feature:gallery`, `:feature:liveview` |
| MediaStore import pipeline | `androidTest/` (instrumented) | `:camera:media` |
| Native JNI bridge smoke tests | `androidTest/` | `:native:fuji-rust-android` |
| Shared fakes and helpers | `test/` source in `:core:testing` | — |

---

## Version catalog (libs.versions.toml)

The real project catalog (`gradle/libs.versions.toml`) already contains:

```toml
# Already present — do not duplicate
turbine         = "1.2.1"          # versions.turbine
mockk           = "1.14.6"         # versions.mockk  (single artifact: io.mockk:mockk)
coroutinesTest  = "1.10.2"         # versions.coroutinesTest

# Already present library aliases
turbine                       = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
mockk                         = { module = "io.mockk:mockk",           version.ref = "mockk" }
kotlinx-coroutines-test       = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutinesTest" }
androidx-compose-ui-test-junit4   = { module = "androidx.compose.ui:ui-test-junit4" }
androidx-compose-ui-test-manifest = { module = "androidx.compose.ui:ui-test-manifest" }
```

Add Robolectric when needed (not yet in the catalog):

```toml
# Add to [versions]:
robolectric = "4.14.1"    # verify latest at robolectric.org before adding
# Add to [libraries]:
robolectric = { module = "org.robolectric:robolectric", version.ref = "robolectric" }
```

In each module's `build.gradle.kts`:

```kotlin
dependencies {
    // JVM unit tests — use real catalog aliases
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)

    // Compose UI tests (instrumented)
    androidTestImplementation(platform(libs.androidx.compose.bom))       // BOM aligns versions
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.mockk)
    androidTestImplementation(libs.turbine)
    debugImplementation(libs.androidx.compose.ui.test.manifest)           // provides test Activity
}
```

Robolectric goes into `testImplementation(libs.robolectric)` and requires:

```kotlin
android {
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}
```

---

## ViewModel unit tests (JVM)

ViewModels in Frameport must not call JNI directly — verify this invariant holds before writing the test. Inject fakes from `:core:testing`.

### MainDispatcherRule

```kotlin
// :core:testing — shared across all modules
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher()
) : TestWatcher() {
    override fun starting(description: Description) = Dispatchers.setMain(testDispatcher)
    override fun finished(description: Description) = Dispatchers.resetMain()
}
```

### Pattern: StateFlow + Turbine

```kotlin
@RunWith(JUnit4::class)
class ConnectionViewModelTest {

    @get:Rule val mainDispatcherRule = MainDispatcherRule()

    private val fakeBleClient = FakeFujiBleClient()       // from :core:testing
    private lateinit var viewModel: ConnectionViewModel

    @Before
    fun setUp() {
        viewModel = ConnectionViewModel(fakeBleClient)
    }

    @Test
    fun `emits Scanning state when scan starts`() = runTest {
        viewModel.uiState.test {
            assertThat(awaitItem()).isInstanceOf(ConnectionUiState.Idle::class.java)

            viewModel.startScan()
            assertThat(awaitItem()).isInstanceOf(ConnectionUiState.Scanning::class.java)

            cancelAndIgnoreRemainingEvents()
        }
    }

    @Test
    fun `StateFlow conflated — only final state matters`() = runTest {
        val item = viewModel.uiState.testIn(backgroundScope)

        fakeBleClient.emitConnectionState(BleConnectionState.Connecting)
        fakeBleClient.emitConnectionState(BleConnectionState.Connected)

        // Use expectMostRecentItem() when intermediate values are not load-bearing
        assertThat(item.expectMostRecentItem()).isEqualTo(ConnectionUiState.Connected)
        item.cancelAndIgnoreRemainingEvents()
    }
}
```

Key rules:
- Attach the turbine **before** triggering state changes to avoid missing the initial emission.
- `cancelAndIgnoreRemainingEvents()` (not the old `cancelAndConsumeRemainingEvents()`) terminates collection without failing on leftover events.
- For `SharingStarted.WhileSubscribed` flows: the flow is inactive until a collector exists — use `testIn(backgroundScope)` or `Flow.test {}` before any action.

### Pattern: use-case / repository with typed errors

```kotlin
@Test
fun `returns PermissionDenied when BLUETOOTH_SCAN is missing`() = runTest {
    fakePermissionChecker.deny(Manifest.permission.BLUETOOTH_SCAN)

    val result = startBleScanUseCase()

    assertThat(result).isInstanceOf(Result.Failure::class.java)
    assertThat((result as Result.Failure).error)
        .isInstanceOf(CameraError.PermissionDenied.BluetoothScan::class.java)
}
```

Use `coEvery` / `coVerify` for any suspend function stub in MockK:

```kotlin
val mockRepo: CameraRepository = mockk()
coEvery { mockRepo.connect(any()) } returns Result.Success(Unit)

// ... test body ...

coVerify(exactly = 1) { mockRepo.connect(any()) }
unmockkAll()   // in @After
```

Never use plain `every` / `verify` for suspend functions — it compiles but silently skips the suspend path.

---

## Fakes in :core:testing

All shared test doubles live here. No concrete platform class (`BluetoothGatt`, `WifiManager`, `UsbManager`) belongs in `:core:testing`.

```kotlin
// :core:testing — canonical fake for BLE layer
class FakeFujiBleClient : FujiBleClient {

    private val _connectionState = MutableStateFlow<BleConnectionState>(BleConnectionState.Disconnected)
    val connectionState: StateFlow<BleConnectionState> = _connectionState.asStateFlow()

    private val _advertisements = MutableSharedFlow<BleCameraAdvertisement>()
    val advertisements: SharedFlow<BleCameraAdvertisement> = _advertisements.asSharedFlow()

    // Test-control helpers
    suspend fun emitConnectionState(state: BleConnectionState) { _connectionState.value = state }
    suspend fun emitAdvertisement(ad: BleCameraAdvertisement) { _advertisements.emit(ad) }

    // FujiBleClient interface implementation
    override fun observeState(): StateFlow<BleConnectionState> = connectionState
    override suspend fun connect(address: String) { /* controllable via emitConnectionState */ }
    override suspend fun disconnect() { _connectionState.value = BleConnectionState.Disconnected }
}
```

`FakeCameraWifiConnector` follows the same pattern with `MutableStateFlow<CameraWifiState>`.

Layer-boundary rule: `:feature:*` tests must only import fakes from `:core:testing` that implement `:camera:api` interfaces — never concrete classes from `:camera:bluetooth`, `:camera:wifi`, `:camera:usb`, or `:native:fuji-rust-android`. Gradle module dependency rules enforce this at compile time.

---

## Compose UI tests

### Setup: Hilt + Compose

HiltAndroidRule must be `order = 0` (outermost) so the Hilt component is ready before the Compose rule inflates the Activity.

```kotlin
@HiltAndroidTest
class ConnectionScreenTest {

    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<HiltTestActivity>()

    @Before
    fun setUp() {
        hiltRule.inject()
    }

    @Test
    fun `scan button is enabled in Idle state`() {
        composeRule.setContent {
            ConnectionScreen(/* ViewModel injected via Hilt */)
        }
        composeRule.onNodeWithTag("btn_start_scan").assertIsEnabled()
    }
}
```

`HiltTestActivity` is an `@AndroidEntryPoint`-annotated empty `ComponentActivity` that lives in `src/androidTest/` — not in `src/main/` (to avoid shipping it in the release APK):

```kotlin
// src/androidTest/java/dev/po4yka/frameport/HiltTestActivity.kt
@AndroidEntryPoint
class HiltTestActivity : ComponentActivity()
```

Use `@TestInstallIn` for global fake injection across all instrumented tests. Prefer it over `@UninstallModules` which forces a per-test-class Hilt component rebuild.

```kotlin
@TestInstallIn(
    components = [SingletonComponent::class],
    replaces = [BluetoothModule::class]
)
@Module
object FakeBluetoothModule {
    @Provides
    fun provideFujiBleClient(): FujiBleClient = FakeFujiBleClient()
}
```

### Semantics-based assertions

Prefer `onNodeWithTag` with `Modifier.testTag()` as the stable selector:

```kotlin
// In production composable:
Button(
    onClick = onScanClick,
    modifier = Modifier.testTag("btn_start_scan")
) { Text("Scan") }

// In test:
composeRule.onNodeWithTag("btn_start_scan").performClick()
composeRule.onNodeWithTag("state_label").assertTextEquals("Scanning…")
```

Use `useUnmergedTree = true` when the text belongs to a child inside a merged semantics subtree and the top-level node swallows it.

### Synchronization

```kotlin
// Disable auto-advance to test animations frame-by-frame:
composeRule.mainClock.autoAdvance = false
composeRule.mainClock.advanceTimeBy(500L)

// Wait for an async condition (e.g., ViewModel emits after BLE scan):
composeRule.waitUntil(timeoutMillis = 3_000L) {
    composeRule.onAllNodesWithText("Connected").fetchSemanticsNodes().isNotEmpty()
}
// Or use typed helpers (available since Compose 1.5 / this BOM):
composeRule.waitUntilAtLeastOneExists(hasText("Connected"), timeoutMillis = 3_000L)
```

Do not mix Espresso `CountDownLatch.await()` inside a Compose test — the virtual clock does not advance during latch waits, causing hangs. Use `waitUntil {}` or register an `IdlingResource` via `composeRule.registerIdlingResource()`.

`assertIsDisplayed()` checks viewport visibility; `assertExists()` only checks the semantic tree — prefer `assertIsDisplayed()` for user-visible state assertions.

### State restoration

```kotlin
val restorationTester = StateRestorationTester(composeRule)
restorationTester.setContent { GalleryScreen() }
// Simulate process death and restore
restorationTester.emulateSavedInstanceStateRestore()
composeRule.onNodeWithTag("gallery_grid").assertIsDisplayed()
```

---

## Robolectric tests

Suitable for Android-framework-touching code that does not involve BLE, Wi-Fi, or JNI.

```kotlin
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31], application = HiltTestApplication::class)
class BluetoothPermissionStateMachineTest {

    @Test
    fun `transitions to Denied when permission is rejected`() {
        // Use ApplicationProvider.getApplicationContext() for Context
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        // ... test permission state machine logic ...
    }
}
```

Set in `robolectric.properties` (placed in `test/resources/`):

```properties
sdk=31
application=dagger.hilt.android.testing.HiltTestApplication
```

BLE and Wi-Fi APIs are partially shadowed in Robolectric 4.x — `BluetoothAdapter` and `WifiManager` exist but are not fully functional. Use `FakeFujiBleClient` / `FakeCameraWifiConnector` rather than Robolectric shadows for any BLE/Wi-Fi path. `MediaStore` insert and query work under Robolectric for pipeline logic, but full MediaStore behavior (including `IS_PENDING` lifecycle) requires an instrumented test.

---

## Instrumented MediaStore tests (:camera:media)

These cannot be replaced by Robolectric. Run on a device or emulator. Test the full `IS_PENDING` lifecycle:

```kotlin
@RunWith(AndroidJUnit4::class)
class MediaStoreImportTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun `IS_PENDING cleared after write completes`() {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, "test_frame.jpg")
            put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Images.Media.getContentUri("external"), values)!!

        // Simulate write
        resolver.openOutputStream(uri)?.use { it.write(ByteArray(16)) }

        values.clear()
        values.put(MediaStore.Images.Media.IS_PENDING, 0)
        resolver.update(uri, values, null, null)

        resolver.query(uri, arrayOf(MediaStore.Images.Media.IS_PENDING), null, null, null)?.use { cursor ->
            cursor.moveToFirst()
            assertThat(cursor.getInt(0)).isEqualTo(0)
        }

        // Clean up
        resolver.delete(uri, null, null)
    }
}
```

---

## Custom test runner (Hilt)

Wire once at the app level in `defaultConfig`:

```kotlin
// :app build.gradle.kts
android {
    defaultConfig {
        testInstrumentationRunner = "dev.po4yka.frameport.CustomTestRunner"
    }
}
```

```kotlin
// src/androidTest/java/dev/po4yka/frameport/CustomTestRunner.kt
class CustomTestRunner : AndroidJUnitRunner() {
    override fun newApplication(
        cl: ClassLoader?, name: String?, context: Context?
    ): Application = super.newApplication(cl, HiltTestApplication::class.java.name, context)
}
```

---

## Key pitfalls

1. **Missing `ui-test-manifest`** — `debugImplementation(libs.compose.ui.test.manifest)` is required; without it `createComposeRule()` crashes at startup.
2. **`createComposeRule()` vs `createAndroidComposeRule`** — use `createAndroidComposeRule<HiltTestActivity>()` whenever you need `getString()`, Hilt, or Activity access.
3. **`stateIn(WhileSubscribed)` with no collector** — the flow never activates; `value` stays at the initial value. Launch `testIn(backgroundScope)` before any action.
4. **Turbine wall-clock timeout with `StandardTestDispatcher`** — Turbine's 3 s timeout uses wall-clock time, not virtual time; advance the dispatcher with `advanceUntilIdle()` or switch to `UnconfinedTestDispatcher`.
5. **Old Turbine API name** — `cancelAndIgnoreRemainingEvents()` is the current name; `cancelAndConsumeRemainingEvents()` no longer exists in 1.x.
6. **`every`/`verify` for suspend functions** — use `coEvery`/`coVerify`; plain variants compile but silently skip the suspend path.
7. **`HiltAndroidRule` order** — must be `order = 0`; the Compose rule must be higher order so Hilt is ready when the Activity inflates.
8. **`HiltTestActivity` in `src/main/`** — it belongs in `src/androidTest/` only; placing it in `src/main/` ships it in the release APK.
9. **`@UninstallModules` vs `@TestInstallIn`** — prefer `@TestInstallIn` for global fakes; `@UninstallModules` forces a full per-class Hilt component rebuild.
10. **JNI in JVM tests** — `System.loadLibrary()` cannot be called from a JVM (unit or Robolectric) test; ViewModel tests must go through the fake interface; native smoke tests belong in `:native:fuji-rust-android` `androidTest/`.
11. **BLE/Wi-Fi Robolectric shadows** — partial only; always use `FakeFujiBleClient`/`FakeCameraWifiConnector` from `:core:testing` instead.
12. **`assertIsDisplayed()` vs `assertExists()`** — `assertExists()` passes even when the node is scrolled off-screen; use `assertIsDisplayed()` for visible-state assertions.
13. **Merged semantics tree** — `onNodeWithText()` may not find text in a child of a merged parent; add `useUnmergedTree = true` or restructure semantics.
14. **`mainClock.advanceTimeBy()` while `autoAdvance = true`** — manual advancement only works after setting `autoAdvance = false`.
15. **Chaining Compose actions** — `onNodeWithText("x").performClick().performTextInput("y")` is invalid; each perform call must start from `onNode*()`.

---

## References

- [Test your Compose layout — developer.android.com](https://developer.android.com/develop/ui/compose/testing)
- [Testing APIs — Jetpack Compose — developer.android.com](https://developer.android.com/develop/ui/compose/testing/apis)
- [Semantics in Compose testing — developer.android.com](https://developer.android.com/develop/ui/compose/testing/semantics)
- [Synchronize your Compose tests — developer.android.com](https://developer.android.com/develop/ui/compose/testing/synchronization)
- [Common testing patterns — Jetpack Compose — developer.android.com](https://developer.android.com/develop/ui/compose/testing/common-patterns)
- [Testing Kotlin flows on Android — developer.android.com](https://developer.android.com/kotlin/flow/test)
- [Hilt testing guide — developer.android.com](https://developer.android.com/training/dependency-injection/hilt-testing)
- [Robolectric on Android Developers](https://developer.android.com/training/testing/local-tests/robolectric)
- [cashapp/turbine 1.2.1 — GitHub](https://github.com/cashapp/turbine)
- [MockK — mocking library for Kotlin](https://mockk.io/)
