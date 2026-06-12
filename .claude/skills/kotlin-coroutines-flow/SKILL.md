---
name: kotlin-coroutines-flow
description: >
  Use when authoring or reviewing structured concurrency in Frameport — choosing between StateFlow,
  SharedFlow, Channel, and suspend functions; wiring viewModelScope and Dispatchers in :feature and
  :camera ViewModels; wrapping BLE GATT and Wi-Fi callbacks with callbackFlow; collecting flows
  lifecycle-safely in Compose with collectAsStateWithLifecycle; or testing flows with Turbine and
  runTest. Also triggers on questions about repeatOnLifecycle, flowOn placement, dispatcher injection
  for Hilt, one-shot UI events, or coroutines-test migration from deprecated APIs.
---

# Kotlin Coroutines & Flow — Frameport Skill

**Versions:** `kotlinx-coroutines 1.10.2`, `lifecycle 2.10.0`, `turbine 1.2.1` (confirmed in `gradle/libs.versions.toml`).

---

## When to Use

- Choosing `StateFlow` vs `SharedFlow` vs `Channel` vs `suspend fun` for a new ViewModel or repository.
- Wrapping a `BluetoothGattCallback` or `WifiManager` scan callback in `callbackFlow` inside `:camera:bluetooth` or `:camera:wifi`.
- Wiring `flowOn`, dispatcher injection, or `viewModelScope` in `:camera:*` or `:feature:*` modules.
- Making flow collection lifecycle-safe in Jetpack Compose (`collectAsStateWithLifecycle`) or View-based code (`repeatOnLifecycle`).
- Testing a ViewModel's `StateFlow` or a repository's cold flow with `runTest` + Turbine.
- Handling one-shot events (toasts, navigation triggers) without violating the Android events guidance.

---

## Primitive Selection Guide

| Scenario | Primitive |
|---|---|
| Long-lived UI state exposed from ViewModel to Compose | `StateFlow<UiState>` |
| Converting a cold repository flow to a ViewModel-scoped hot flow | `Flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)` |
| One-shot transient events (error toast, navigation trigger) | Nullable field inside `UiState` + `fun eventConsumed()` on ViewModel |
| Wrapping BLE GATT / Wi-Fi callbacks | `callbackFlow { }` in `:camera:bluetooth` / `:camera:wifi` |
| Internal actor / command queue inside a single component | `Channel<Command>(capacity = Channel.BUFFERED)` — never exposed across module boundaries |
| Fan-out broadcast across multiple collectors with replay | `SharedFlow` with explicit `replay` and `extraBufferCapacity` |
| Single async request/response (no UI subscription) | `suspend fun` returning a result |

---

## StateFlow — Long-lived UI State

Every `:feature` and `:camera` ViewModel exposes state as `StateFlow<UiState>`:

```kotlin
// :feature:connection ViewModel example
@HiltViewModel
class ConnectionViewModel @Inject constructor(
    private val cameraRepository: CameraRepository,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher,
) : ViewModel() {

    private val _uiState = MutableStateFlow(ConnectionUiState())
    val uiState: StateFlow<ConnectionUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            cameraRepository.connectionState()   // cold flow from :camera:domain
                .flowOn(ioDispatcher)            // I/O work stays off Main
                .collect { state ->
                    _uiState.update { it.copy(connectionState = state) }
                }
        }
    }
}
```

Use `MutableStateFlow.update { }` for all read-modify-write operations — it is atomic, unlike a manual `value = value.copy(...)` compound.

### Transient Events Inside UiState

Do **not** use `Channel<UiEvent>` or `MutableSharedFlow<UiEvent>` for one-shot events. The ViewModel outlives the Compose collector during recomposition; delivery is not guaranteed.

```kotlin
data class ConnectionUiState(
    val connectionState: ConnectionState = ConnectionState.Idle,
    // Transient error — nullable; null means "no error to show"
    val connectionError: String? = null,
)

// ViewModel clears it after UI consumes
fun errorShown() {
    _uiState.update { it.copy(connectionError = null) }
}
```

The Composable calls `viewModel.errorShown()` inside the `LaunchedEffect` or `snackbarHostState.showSnackbar()` callback.

---

## callbackFlow — Wrapping BLE and Wi-Fi Callbacks

`callbackFlow` is the correct wrapper for `BluetoothGattCallback` and Wi-Fi scan callbacks. Lives in `:camera:bluetooth` and `:camera:wifi` behind the `FujiBleClient` and `CameraWifiConnector` interfaces.

```kotlin
// Inside FujiBleClientImpl (implements FujiBleClient) — :camera:bluetooth
// characteristic is passed in so it can be enabled/disabled around the flow lifetime
fun observeGattNotifications(
    gatt: BluetoothGatt,
    characteristic: BluetoothGattCharacteristic,
): Flow<ByteArray> = callbackFlow {
    val callback = object : BluetoothGattCallback() {
        // API 33+ overload — receives value directly (preferred path)
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray,
        ) {
            // trySend is non-blocking; log failures rather than silently dropping
            trySend(value).onFailure { cause ->
                // Buffer full or channel closed — log and decide whether to drop or close
                Log.w(TAG, "GATT notification dropped", cause)
            }
        }

        // API 31–32 overload (deprecated at 33, still required for minSdk 31)
        @Suppress("OVERRIDE_DEPRECATION")
        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
        ) {
            val value = characteristic.value ?: return
            trySend(value).onFailure { cause ->
                Log.w(TAG, "GATT notification dropped (API 31-32)", cause)
            }
        }
    }
    gatt.setCharacteristicNotification(characteristic, true)

    // awaitClose is MANDATORY — omitting it throws IllegalStateException
    awaitClose {
        gatt.setCharacteristicNotification(characteristic, false)
    }
}
```

**Rules:**
- `awaitClose { }` is non-optional. The block executes when the collector cancels or the flow completes; use it to unregister callbacks and release resources.
- Because minSdk is 31, implement **both** `onCharacteristicChanged` overloads: the 3-argument form (API 33+, value passed directly) and the deprecated 2-argument form (API 31–32, read from `characteristic.value`). See the `android-ble-gatt` skill for the full dual-override pattern.
- Use `trySend()` (non-blocking) inside Android callbacks. Check `.onFailure` to log dropped items. Use `trySendBlocking()` only on threads that are safe to park (not the Android main thread).
- Default buffer capacity is 64. If the GATT characteristic fires faster than the consumer, increase via a downstream `.buffer(Channel.BUFFERED)` operator or apply `conflate()`. Note: `callbackFlow` has no `capacity` constructor parameter — buffer size is set with the `.buffer()` operator on the returned flow.

---

## flowOn — Dispatcher Placement

`flowOn` changes the dispatcher for operators **above** it (upstream). The collect site always runs on the calling coroutine's dispatcher.

```kotlin
// Repository / DataSource — apply flowOn in the data layer
fun cameraFiles(): Flow<List<CameraFile>> = flow {
    emit(localDb.queryCameraFiles())  // I/O
}
    .map { it.filter { f -> f.isVisible } }  // still on ioDispatcher
    .flowOn(ioDispatcher)  // everything above runs on IO

// ViewModel — collect on Main (viewModelScope), do NOT add another flowOn here
viewModelScope.launch {
    cameraRepository.cameraFiles().collect { files ->
        _uiState.update { it.copy(files = files) }
    }
}
```

Never apply `flowOn` inside a ViewModel — `viewModelScope` is already on `Dispatchers.Main.immediate`.

---

## Dispatcher Injection with Hilt

Define qualifiers in `:core:common` and provide them from a Hilt `@Module`:

```kotlin
// :core:common — qualifier annotations
@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class IoDispatcher

@Qualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class DefaultDispatcher
```

```kotlin
// :app or :core:common — Hilt module
@Module
@InstallIn(SingletonComponent::class)
object DispatchersModule {

    @Provides
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    @Provides
    @DefaultDispatcher
    fun provideDefaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
}
```

Inject into repositories and data sources — never hardcode `Dispatchers.IO` inside production classes.

---

## Lifecycle-Safe Collection

### In Compose (preferred for :feature modules)

`collectAsStateWithLifecycle` from `androidx.lifecycle:lifecycle-runtime-compose` (already in `libs.versions.toml` at lifecycle `2.10.0`) handles lifecycle gating automatically:

```kotlin
// :feature:gallery screen
@Composable
fun GalleryScreen(viewModel: GalleryViewModel = hiltViewModel()) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // uiState is only updated when the lifecycle is at least STARTED
    GalleryContent(uiState)
}
```

Do **not** use `collectAsState()` alone — it uses Composition lifecycle (tied to the Composable tree), not the Activity/Fragment lifecycle, and keeps collecting in background pager tabs.

### In View-based code (app shell or legacy Fragment)

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
        viewModel.uiState.collect { state ->
            render(state)
        }
    }
}
```

`repeatOnLifecycle` suspends until the lifecycle reaches `STARTED`, cancels when it drops below, and restarts on the next rise. It is the View-layer equivalent of `collectAsStateWithLifecycle`.

**Do not use** `lifecycleScope.launchWhenStarted` / `launchWhenResumed` — deprecated.

### Single-flow Compose collection inside LaunchedEffect

If you must collect a cold or transformed flow inside `LaunchedEffect`, gate it with `flowWithLifecycle`:

```kotlin
LaunchedEffect(viewModel) {
    viewModel.singleShotFlow
        .flowWithLifecycle(lifecycle, Lifecycle.State.STARTED)
        .collect { event -> /* handle */ }
}
```

For UI state (`StateFlow`), prefer `collectAsStateWithLifecycle` over this pattern.

---

## stateIn — Converting Repository Flows

Use in ViewModels when the upstream is a cold flow from the repository:

```kotlin
val importProgress: StateFlow<ImportProgress> = importRepository
    .observeProgress()
    .stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(stopTimeoutMillis = 5_000),
        initialValue = ImportProgress.Idle,
    )
```

`WhileSubscribed(5_000)` stops the upstream 5 seconds after the last collector disappears (e.g., during configuration change), restarting when a new collector subscribes. This avoids redundant work while surviving brief gaps.

**R8 note (coroutines 1.10 fix):** Ensure R8 minification does not garbage-collect `stateIn`/`shareIn` upstream producers in release builds. If you observe upstream silently stopping after minification, check your R8 rules — kotlinx-coroutines 1.10 includes a fix but verify with a release build test.

---

## Channel — Internal Command Queues

Use `Channel` for internal serialized command dispatch inside a single component (e.g., PTP command queue in a ViewModel or data-layer actor). Never expose a raw `Channel` across module boundaries — wrap it:

```kotlin
// Internal to a DataSource — not exposed
private val commandChannel = Channel<PtpCommand>(capacity = Channel.BUFFERED)

init {
    viewModelScope.launch {
        for (command in commandChannel) {
            processPtpCommand(command)  // serialized, one at a time
        }
    }
}

// Expose only a suspend function or a Flow to callers
suspend fun dispatchCommand(command: PtpCommand) {
    commandChannel.send(command)
}
```

Expose to upstream callers via `commandChannel.receiveAsFlow()` if fan-out is needed, or convert to `SharedFlow` via `shareIn`.

---

## Testing

### Gradle dependencies

```toml
# gradle/libs.versions.toml (already present)
kotlinx-coroutines-test = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-test", version.ref = "coroutinesTest" }
turbine = { module = "app.cash.turbine:turbine", version.ref = "turbine" }
```

### MainDispatcherRule

Define a `MainDispatcherRule` `TestWatcher` in `:core:testing` and apply to all ViewModel tests:

```kotlin
// :core:testing
class MainDispatcherRule(
    val testDispatcher: TestDispatcher = UnconfinedTestDispatcher(),
) : TestWatcher() {
    override fun starting(description: Description) {
        Dispatchers.setMain(testDispatcher)
    }
    override fun finished(description: Description) {
        Dispatchers.resetMain()
    }
}
```

### Testing StateFlow + stateIn

```kotlin
@get:Rule
val mainDispatcherRule = MainDispatcherRule()

@Test
fun `gallery loads files on init`() = runTest {
    val fakeRepository = FakeGalleryRepository()
    val viewModel = GalleryViewModel(fakeRepository, UnconfinedTestDispatcher(testScheduler))

    // IMPORTANT: for stateIn with WhileSubscribed, activate upstream before asserting
    backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
        viewModel.uiState.collect {}
    }

    fakeRepository.emitFiles(listOf(CameraFile("DSC0001.RAF")))
    advanceUntilIdle()

    assertThat(viewModel.uiState.value.files).hasSize(1)
}
```

Without the `backgroundScope` collector, `WhileSubscribed` never activates and `value` stays at `initialValue`.

### Testing with Turbine

```kotlin
@Test
fun `connection error is shown then cleared`() = runTest {
    val viewModel = ConnectionViewModel(fakeRepo, UnconfinedTestDispatcher(testScheduler))

    viewModel.uiState.test {
        assertThat(awaitItem().connectionError).isNull()

        fakeRepo.emitError("Wi-Fi not connected")
        assertThat(awaitItem().connectionError).isEqualTo("Wi-Fi not connected")

        viewModel.errorShown()
        assertThat(awaitItem().connectionError).isNull()

        cancel()
    }
}
```

For testing multiple flows simultaneously, use `turbineScope`:

```kotlin
@Test
fun `ui state and secondary flow update together`() = runTest {
    turbineScope {
        val stateTurbine = viewModel.uiState.testIn(backgroundScope)
        val eventTurbine = viewModel.secondaryFlow.testIn(backgroundScope)

        // trigger, then await on each turbine
        stateTurbine.awaitItem()
        eventTurbine.awaitItem()

        stateTurbine.cancel()
        eventTurbine.cancel()
    }
}
```

**Do not use** `runBlockingTest` or `TestCoroutineScope` — fully removed from the stable API as of coroutines 1.9+. Use `runTest` with `TestScope`.

---

## Pitfalls Checklist

Before submitting a diff touching coroutines or flows, verify:

- [ ] All `StateFlow`/`SharedFlow` collection in Compose uses `collectAsStateWithLifecycle`, not bare `collectAsState()`.
- [ ] All collection in Views/Fragments is inside `repeatOnLifecycle(Lifecycle.State.STARTED)` — not `launchWhenStarted`.
- [ ] Every `callbackFlow` block has a matching `awaitClose { }` that unregisters callbacks.
- [ ] `trySend()` results inside callbacks are checked with `.onFailure { }` — no silent drops.
- [ ] No `Dispatchers.IO` or `Dispatchers.Default` hardcoded in production repository/data-source classes — inject via `@IoDispatcher` / `@DefaultDispatcher`.
- [ ] No `GlobalScope` usage anywhere.
- [ ] `flowOn` is applied in the repository/data-source layer, not in the ViewModel.
- [ ] Read-modify-write on `MutableStateFlow` uses `.update { }`, not compound `value = value.copy(...)`.
- [ ] One-shot events are modelled as nullable `UiState` fields + `fun eventConsumed()` — not `Channel<UiEvent>` or `MutableSharedFlow<UiEvent>`.
- [ ] Tests use `runTest` + `MainDispatcherRule`, not `runBlockingTest`.
- [ ] Tests activate `WhileSubscribed` flows with a `backgroundScope` collector before asserting.
- [ ] JNI (fuji-ffi) is never called directly from a ViewModel — the layer invariant requires platform interfaces (`FujiBleClient`, `CameraWifiConnector`) between them.

---

## Frameport Layer Invariants (Reminders)

- **Composables** must not open sockets, touch `BluetoothGatt`, access USB, write files, or call JNI.
- **ViewModels** must not call JNI directly. All native I/O goes through `:camera:*` interfaces.
- **callbackFlow producers** live in `:camera:bluetooth` and `:camera:wifi` behind interfaces, not in `:feature` modules.
- **`JNIEnv` must never be stored across a suspend boundary.** After an `.await()`, the coroutine may resume on a different thread. Obtain a fresh `AttachCurrentThread` on each JNI re-entry inside `fuji-ffi`. (This is a JNI discipline rule, not a coroutines API rule — enforced by the `rust-android-jni` skill.)

---

## References

- [StateFlow and SharedFlow — Android Developers](https://developer.android.com/kotlin/flow/stateflow-and-sharedflow)
- [Kotlin flows on Android — Android Developers](https://developer.android.com/kotlin/flow)
- [Coroutines with lifecycle-aware components — Android Developers](https://developer.android.com/topic/libraries/architecture/coroutines)
- [Best practices for coroutines in Android — Android Developers](https://developer.android.com/kotlin/coroutines/coroutines-best-practices)
- [UI events (one-shot events guidance) — Android Developers](https://developer.android.com/topic/architecture/ui-layer/events)
- [Testing Kotlin flows on Android — Android Developers](https://developer.android.com/kotlin/flow/test)
- [Testing Kotlin coroutines on Android — Android Developers](https://developer.android.com/kotlin/coroutines/test)
- [callbackFlow — kotlinx.coroutines API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.flow/callback-flow.html)
- [Channel — kotlinx.coroutines API](https://kotlinlang.org/api/kotlinx.coroutines/kotlinx-coroutines-core/kotlinx.coroutines.channels/-channel/)
- [kotlinx.coroutines CHANGES.md (1.9 / 1.10 changelog)](https://github.com/Kotlin/kotlinx.coroutines/blob/master/CHANGES.md)
- [Turbine 1.2.1 README — cashapp/turbine](https://github.com/cashapp/turbine/blob/trunk/README.md)
- Frameport `gradle/libs.versions.toml` (local, confirmed versions)
