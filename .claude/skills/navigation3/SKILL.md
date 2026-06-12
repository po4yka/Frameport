---
name: navigation3
description: Use when authoring or reviewing navigation code in Frameport — FrameportNavHost, FrameportNavKey destinations, NavBackStack mutation patterns, entryProvider DSL, scene strategies, ViewModel/Hilt scoping per NavEntry, modular entryProvider contribution from :feature:* modules, or any question touching androidx.navigation3 1.1.2 APIs. Also triggers when migrating old NavController/NavHost (Navigation 2) patterns or diagnosing back-stack/lifecycle bugs in the Nav3 layer.
---

# Navigation 3 — Frameport

Jetpack Navigation 3 is the navigation library pinned in Frameport (version **1.1.2**, released 2026-05-19; the library first reached stable at 1.0.0 on 2025-11-19). It replaces the old `NavController` / `NavHost` (Navigation 2 / `androidx.navigation:navigation-compose`) with a model where the back stack is plain Kotlin state that the host owns and mutates directly.

This skill covers the Nav3 API surface, Frameport-specific design decisions, and integration with Hilt and ViewModels.

---

## When to use this skill

- Adding, renaming, or removing a destination in `FrameportNavHost`.
- Defining or modifying a `FrameportNavKey` destination (data object / data class).
- Wiring a new `:feature:*` module's nav entry via the Hilt multibinding pattern.
- Scoping a `@HiltViewModel` to a `NavEntry` (not the Activity).
- Choosing or implementing a `SceneStrategy` (phone single-pane vs future tablet list-detail).
- Diagnosing unexpected ViewModel recreation, state loss, or back-press behaviour.
- Reviewing any diff that imports `NavController`, `NavHost`, `NavBackStackEntry`, or `rememberNavController` — these are **Nav2** types and do not belong in Frameport.

---

## Artifact coordinates

Add both to every module that hosts a `NavDisplay` or contributes entries via the `entryProvider` DSL:

```toml
# gradle/libs.versions.toml (already pinned — do not change without updating CLAUDE.md)
navigation3 = "1.1.2"

[libraries]
navigation3-runtime = { module = "androidx.navigation3:navigation3-runtime", version.ref = "navigation3" }
navigation3-ui      = { module = "androidx.navigation3:navigation3-ui",      version.ref = "navigation3" }
```

ViewModel scoping (separate artifact — align version with the Lifecycle BOM):

```toml
lifecycle-viewmodel-navigation3 = { module = "androidx.lifecycle:lifecycle-viewmodel-navigation3", version.ref = "lifecycle" }
```

Material 3 adaptive list-detail scenes (add only when implementing tablet/foldable layouts):

```toml
adaptive-navigation3 = { module = "androidx.compose.material3.adaptive:adaptive-navigation3", version = "<adaptive-version>" }
```

**compileSdk/minSdk check**: Nav3 requires `compileSdk >= 36` and `minSdk >= 23`. Frameport uses `compileSdk 37` / `minSdk 31` — both requirements are satisfied.

---

## Core concepts

### NavKey

Every destination is a `NavKey`. In Frameport, all destinations implement `sealed interface FrameportNavKey : NavKey` defined in `:core:model` (or a dedicated `:core:navigation` module).

Requirements for a valid NavKey:
1. Implements `NavKey` (marker interface from `androidx.navigation3:navigation3-runtime`).
2. Annotated `@Serializable` (kotlinx.serialization) — required for `rememberNavBackStack` to persist across config changes and process death.
3. Carries only **stable IDs** as properties — never `Bitmap`, `Parcelable`, domain objects, or any non-serializable type.

```kotlin
// :core:model — dev.po4yka.frameport.core.model.navigation

@Serializable
sealed interface FrameportNavKey : NavKey

// Simple destinations — data objects (no payload)
@Serializable data object Onboarding  : FrameportNavKey
@Serializable data object Connection  : FrameportNavKey
@Serializable data object Gallery     : FrameportNavKey
@Serializable data object Settings    : FrameportNavKey
@Serializable data object Diagnostics : FrameportNavKey

// Destinations with a stable ID payload
@Serializable data class Import(val sessionId: String) : FrameportNavKey
@Serializable data class LiveView(val sessionId: String) : FrameportNavKey
```

The `sealed interface` gives exhaustive `when` expressions across the full destination set — no `else` branch needed, and the compiler catches missing destinations at build time.

### NavBackStack

```kotlin
val backStack = rememberNavBackStack(Onboarding) // initial destination
```

`rememberNavBackStack` creates a `NavBackStack<FrameportNavKey>` — a typed `SnapshotStateList` persisted via `rememberSerializable`. It survives configuration changes and process death as long as all `FrameportNavKey` implementations are `@Serializable`.

`NavBackStack<T>` is a `SnapshotStateList<T>`. It is mutated directly:

```kotlin
backStack.add(Gallery)                        // push
backStack.removeLastOrNull()                  // pop
backStack.removeIf { it is Import }           // conditional pop
```

**Mutation ownership**: only `:app` / `FrameportNavHost` (the navigation host layer) mutates the back stack. Feature ViewModels never touch it directly.

### NavDisplay

`NavDisplay` is the central Composable. It observes `backStack`, resolves each key through `entryProvider`, selects a `Scene` via `sceneStrategies`, and renders:

```kotlin
NavDisplay(
    backStack          = backStack,
    onBack             = { backStack.removeLastOrNull() },
    entryProvider      = entryProvider { /* entry DSL blocks */ },
    sceneStrategies    = listOf(/* custom strategies before fallback */),
    entryDecorators    = listOf(
        rememberSaveableStateHolderNavEntryDecorator(), // MUST come first
        rememberViewModelStoreNavEntryDecorator(),
    ),
)
```

### entryProvider DSL

```kotlin
entryProvider<FrameportNavKey> {
    entry<Onboarding> {
        OnboardingScreen(/* viewModel injected inside */)
    }
    entry<Gallery> {
        GalleryScreen()
    }
    entry<Import> { key ->          // typed key instance available
        ImportScreen(sessionId = key.sessionId)
    }
}
```

`entry<T>` is the DSL function inside `EntryProviderScope<FrameportNavKey>`. The content lambda receives the typed key. No `NavBackStackEntry`, no `route` strings.

### NavMetadataKey (1.1.0+)

For scene selection, use `NavMetadataKey<V>` instead of raw `String` map keys:

```kotlin
// Define a type-safe metadata key
object MyPaneSizeKey : NavMetadataKey<PaneSize>

// Use in entry DSL
entry<Gallery>(metadata { put(MyPaneSizeKey, PaneSize.Full) }) {
    GalleryScreen()
}
```

For the Material 3 adaptive pattern (future tablet support), use the built-in helpers:
- `ListDetailSceneStrategy.listPane(detailPlaceholder)` — marks a list destination
- `ListDetailSceneStrategy.detailPane()` — marks a detail destination
- `ListDetailSceneStrategy.extraPane()` — marks a supplemental pane

### SceneStrategy

The `sceneStrategies` parameter is a `List<SceneStrategy<T>>` (changed from a single strategy in 1.1.0 — passing a bare instance is a compile error on 1.1.x). `SinglePaneSceneStrategy` is always the implicit final fallback; you do not need to add it to the list.

**Frameport v1** targets phones (`minSdk 31`). Leave `sceneStrategies` as an empty list (or omit it) — the default `SinglePaneSceneStrategy` is sufficient.

When tablet/foldable support is added (v2/v3), prepend:

```kotlin
sceneStrategies = listOf(rememberListDetailSceneStrategy<FrameportNavKey>()),
```

and tag Gallery list entries with `ListDetailSceneStrategy.listPane(...)` metadata, photo-detail entries with `.detailPane()`.

---

## ViewModel and Hilt scoping

The `rememberViewModelStoreNavEntryDecorator()` scopes a `ViewModelStoreOwner` to each `NavEntry`. A ViewModel created inside a `NavEntry` content lambda is created when the entry is added to the back stack and cleared when it is removed (popped).

**Decorator order is mandatory**: `rememberSaveableStateHolderNavEntryDecorator()` MUST appear before `rememberViewModelStoreNavEntryDecorator()` in the list. Reversing or omitting the saveable decorator breaks state restoration.

Inside a `NavEntry` content lambda, call `hiltViewModel()` with no extra arguments:

```kotlin
entry<Gallery> {
    val viewModel: GalleryViewModel = hiltViewModel()
    GalleryScreen(
        uiState = viewModel.uiState.collectAsStateWithLifecycle().value,
        onNavigateToImport = { sessionId ->
            // emit a NavigationEvent — do NOT mutate backStack here
            viewModel.onOpenImport(sessionId)
        },
    )
}
```

`hiltViewModel()` uses `LocalViewModelStoreOwner` which is automatically set to the `NavEntry`'s owner by `rememberViewModelStoreNavEntryDecorator`. Without that decorator active, `hiltViewModel()` falls back to the Activity's `ViewModelStoreOwner` — a silent correctness bug.

---

## Navigation event pattern (layer invariant)

Feature ViewModels must not mutate the back stack. They emit navigation events; `FrameportNavHost` observes and acts:

```kotlin
// In a :feature:* ViewModel
sealed interface NavigationEvent {
    data class OpenImport(val sessionId: String) : NavigationEvent
    data object NavigateUp : NavigationEvent
}

@HiltViewModel
class GalleryViewModel @Inject constructor(/* ... */) : ViewModel() {
    private val _navEvents = MutableSharedFlow<NavigationEvent>(extraBufferCapacity = 1)
    val navEvents: SharedFlow<NavigationEvent> = _navEvents.asSharedFlow()

    fun onOpenImport(sessionId: String) {
        _navEvents.tryEmit(NavigationEvent.OpenImport(sessionId))
    }
}

// In FrameportNavHost (:app)
entry<Gallery> {
    val viewModel: GalleryViewModel = hiltViewModel()
    LaunchedEffect(viewModel) {
        viewModel.navEvents.collect { event ->
            when (event) {
                is NavigationEvent.OpenImport -> backStack.add(Import(event.sessionId))
                NavigationEvent.NavigateUp    -> backStack.removeLastOrNull()
            }
        }
    }
    GalleryScreen(
        uiState = viewModel.uiState.collectAsStateWithLifecycle().value,
        onOpenImport = viewModel::onOpenImport,
    )
}
```

`SharedFlow` with `extraBufferCapacity = 1` avoids dropping events emitted before the `LaunchedEffect` collector is active. `Channel` is also acceptable for strictly one-shot delivery — see CLAUDE.md state rules.

---

## Modular entryProvider across :feature:* modules

Follow the Hilt multibinding pattern. Each `:feature:*` module contributes its entry builders without depending on other feature modules:

```kotlin
// In :feature:gallery — dev.po4yka.frameport.feature.gallery

fun EntryProviderScope<FrameportNavKey>.galleryEntryBuilder() {
    entry<Gallery> {
        val viewModel: GalleryViewModel = hiltViewModel()
        // ... wire events and UI
    }
}

@Module
@InstallIn(ActivityRetainedComponent::class)   // NOT SingletonComponent
object GalleryNavModule {
    @Provides
    @IntoSet
    fun provideGalleryEntryBuilder(): @JvmSuppressWildcards (EntryProviderScope<FrameportNavKey>.() -> Unit) =
        { galleryEntryBuilder() }
}
```

`ActivityRetainedComponent` is the correct scope — the entry providers survive configuration changes but are not singletons that outlive the Activity. Using `SingletonComponent` here is a bug.

In `MainActivity` (`:app`):

```kotlin
@Inject
lateinit var entryBuilders: @JvmSuppressWildcards Set<EntryProviderScope<FrameportNavKey>.() -> Unit>

// Inside the NavDisplay call:
entryProvider<FrameportNavKey> {
    entryBuilders.forEach { builder -> this.builder() }
}
```

---

## FrameportNavHost structure (`:app`)

Canonical shape of the navigation host. Keep this in `:app`; no `:feature:*` module references it.

```kotlin
// dev.po4yka.frameport.navigation.FrameportNavHost

@Composable
fun FrameportNavHost(
    entryBuilders: Set<@JvmSuppressWildcards EntryProviderScope<FrameportNavKey>.() -> Unit>,
    modifier: Modifier = Modifier,
) {
    val backStack = rememberNavBackStack(Onboarding)

    NavDisplay(
        backStack       = backStack,
        onBack          = { backStack.removeLastOrNull() },
        entryProvider   = entryProvider<FrameportNavKey> {
            entryBuilders.forEach { builder -> this.builder() }
        },
        sceneStrategies = listOf(),  // SinglePaneSceneStrategy is the implicit fallback
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(), // saveable FIRST
            rememberViewModelStoreNavEntryDecorator(),
        ),
        modifier = modifier,
    )
}
```

---

## Deep links

Navigation 3 1.1.x has **no deep-link infrastructure**. For Frameport v1 (privacy-first, local-only — no external deep links required) this is not a gap.

If Android OS share-sheet intents or widget shortcuts are added later, handle them in `MainActivity.onNewIntent` by pushing the appropriate typed `FrameportNavKey` onto the back stack — do not attempt to route them through Nav3 route strings.

---

## Contrast with Navigation 2

| Concept | Navigation 2 | Navigation 3 |
|---|---|---|
| Back stack | Managed by `NavController` | Owned by host as `NavBackStack<T>` (SnapshotStateList) |
| Destination identity | String route / type-safe route (2.8+) | `@Serializable NavKey` subtype |
| Entry resolution | `NavGraphBuilder` / composable { } | `EntryProviderScope` / `entry<T> { }` |
| Host Composable | `NavHost` | `NavDisplay` |
| Back-stack entry type | `NavBackStackEntry` | `NavEntry<T>` |
| ViewModel scoping | automatic via `NavBackStackEntry` | `rememberViewModelStoreNavEntryDecorator()` required |
| hiltViewModel call | `hiltViewModel()` inside composable | `hiltViewModel()` inside NavEntry content lambda (same call, different owner) |
| Deep links | Built-in | Not supported in 1.1.x — manual intent handling |

Do not mix Nav2 and Nav3 in the same app. Remove any `NavController`, `rememberNavController()`, `NavHost`, or `NavBackStackEntry` that appears in `:app` or `:feature:*` modules.

---

## Key pitfalls

**Large or non-serializable objects in NavKey properties.** `NavBackStack` is serialized for process death. Every property of every `FrameportNavKey` must be serializable (`String`, `Int`, `Long`, other `@Serializable` types). Never embed `Bitmap`, `Parcelable`, domain objects, or complex data classes. Pass only stable IDs and resolve the full object inside the destination.

**Mutating the back stack inside a Composable or ViewModel.** Back-stack mutations (`backStack.add(...)`, `backStack.removeLastOrNull()`) belong exclusively in `FrameportNavHost` (or a Navigator helper owned by `:app`). ViewModels emit `NavigationEvent` types; the host collects and applies them.

**Wrong decorator order.** `rememberSaveableStateHolderNavEntryDecorator()` must be the first element in `entryDecorators`. If it comes after `rememberViewModelStoreNavEntryDecorator()`, or is absent, `rememberSaveable` state inside `NavEntry` content is not restored after the back stack is recreated.

**Using `hiltViewModel()` without the ViewModel store decorator.** Without `rememberViewModelStoreNavEntryDecorator()` in `entryDecorators`, there is no `NavEntry`-scoped `ViewModelStoreOwner`. `hiltViewModel()` silently scopes to the Activity — the ViewModel is never cleared on pop.

**Confusing Nav3 types with Nav2 types.** `NavBackStack<T>` (Nav3) vs `NavBackStackEntry` (Nav2) look similar in autocomplete. Importing `androidx.navigation.NavBackStackEntry` anywhere in the Nav3 layer is a sign of a mistaken import. Check with `rg 'NavBackStackEntry|NavController|NavHost|rememberNavController' app/src/`.

**Single `SceneStrategy` instance passed to `NavDisplay` instead of `List<SceneStrategy<T>>`.** The 1.1.0 API change made the parameter a list. Any code written against pre-1.1.0 alphas that passes a bare strategy instance will not compile against 1.1.2.

**Using `SingletonComponent` for Hilt multibinding of entry builders.** Entry builders must be `@InstallIn(ActivityRetainedComponent::class)`. Singleton scope causes the entry builders (and their captured state) to outlive the Activity.

**Implementing `NavKey` on a class with mutable or non-serializable fields.** kotlinx.serialization's KSP plugin will fail at compile time if a `NavKey` property cannot be serialized. All fields must be primitive or `@Serializable` types.

**Expecting Nav3 to handle deep links.** It does not. Do not add `deepLinks = listOf(...)` to `entry<T>` blocks — that API does not exist in Nav3. Route intents manually in `MainActivity.onNewIntent`.

**Mixing `NavHost` (Nav2) alongside `NavDisplay` (Nav3).** The two libraries have independent back-stack implementations. Running both creates double lifecycle management and unpredictable back-press behaviour.

---

## Verification commands

```bash
# Ensure no Nav2 types have been imported into Nav3 modules
rg 'NavController|rememberNavController|NavHost|NavBackStackEntry' \
    app/src/ feature/ core/ --include='*.kt' -l

# Check all FrameportNavKey implementations are @Serializable
rg 'FrameportNavKey' core/model/src/ --include='*.kt'

# Build to catch serialization KSP errors early
./gradlew :app:compileDebugKotlin
```

---

## References

- [Navigation 3 overview](https://developer.android.com/guide/navigation/navigation-3)
- [Basics — NavKey, NavBackStack, NavDisplay, entryProvider](https://developer.android.com/guide/navigation/navigation-3/basics)
- [Save and manage navigation state](https://developer.android.com/guide/navigation/navigation-3/save-state)
- [Custom layouts using Scenes](https://developer.android.com/guide/navigation/navigation-3/scenes)
- [Migrate from Navigation 2 to Navigation 3](https://developer.android.com/guide/navigation/navigation-3/migration-guide)
- [Modularize navigation code — Hilt multibinding](https://developer.android.com/guide/navigation/navigation-3/modularize)
- [navigation3 release notes](https://developer.android.com/jetpack/androidx/releases/navigation3)
- [Jetpack Navigation 3 stable announcement](https://developer.android.com/blog/posts/jetpack-navigation-3-is-stable)
- [nav3-recipes sample (GitHub android/nav3-recipes)](https://github.com/android/nav3-recipes)
- Frameport `CLAUDE.md` — pins Navigation3 1.1.2, describes `FrameportNavHost`
- Frameport `docs/adr/0007-module-boundaries.md` — layer invariants (no direct nav/platform calls in feature modules)
