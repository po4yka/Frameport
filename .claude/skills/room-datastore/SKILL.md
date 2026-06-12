---
name: room-datastore
description: Use when authoring or modifying persistence code in :core:storage — Room entities (@Entity, @Dao, @Database, @TypeConverter, AutoMigration, MigrationTestHelper), Proto DataStore (Serializer, updateData, dataStore delegate), or encrypted credential storage (direct Android Keystore AES-256-GCM for Wi-Fi credentials and pairing secrets). Triggers on FrameportDatabase, MediaItemEntity, TransferHistoryEntity, DiagnosticEventEntity, AppSettingsDataStore, PrivacySettingsDataStore, ConnectionSettingsDataStore, KeystoreEncryptedStorage, schema migration, or any question about Room 2.8.x or DataStore 1.2.0 in Frameport.
---

# Room + DataStore Persistence Skill

## When to use

Invoke this skill when working on:
- `FrameportDatabase` or any `@Entity` / `@Dao` / `@TypeConverter` in `:core:storage`
- `MediaItemEntity`, `TransferHistoryEntity`, `DiagnosticEventEntity` table definitions
- Schema version bumps, `AutoMigration`, or manual `Migration` objects
- `AppSettingsDataStore`, `PrivacySettingsDataStore`, `ConnectionSettingsDataStore` Proto DataStore instances
- `KeystoreEncryptedStorage` — encrypted Wi-Fi credentials or BLE pairing secrets
- DAO tests (`inMemoryDatabaseBuilder`, `MigrationTestHelper`)
- Questions about Room 2.8.x or DataStore 1.2.x behaviour changes

---

## Layer invariants (non-negotiable)

`:core:storage` is the **sole owner** of all persistence. No other module instantiates `RoomDatabase` or `DataStore` directly:

- `:camera:data`, `:feature:*` — consume repository interfaces injected by Hilt
- ViewModels — receive `Flow`-producing abstractions; MUST NOT call Room DAOs directly
- Composables — MUST NOT touch storage; collect `StateFlow` from ViewModel only

---

## Gradle setup

```kotlin
// :core:storage/build.gradle.kts — current project configuration

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    // NOTE: The Room Gradle Plugin (id "androidx.room") is NOT yet applied in this project.
    // Adding it would replace the RoomSchemaArgProvider approach and enable the
    // `room { schemaDirectory(...) }` DSL block. Apply when ready to commit schema JSON files.
}

dependencies {
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)        // re-exports room-runtime since 2.7.0; keep until Room 3.0
    ksp(libs.androidx.room.compiler)              // always ksp(), never kapt()
    androidTestImplementation(libs.androidx.room.testing)

    implementation(libs.androidx.datastore)       // Proto DataStore, Android-only (pinned 1.2.0)
    implementation("com.google.protobuf:protobuf-kotlin-lite:4.32.1")
}
```

The project version catalog (`gradle/libs.versions.toml`) pins:
- `room = "2.8.4"` — used for `room-runtime`, `room-ktx`, `room-compiler`, `room-testing`
- `datastore = "1.2.0"` — used for the `androidx.datastore:datastore` artifact

Key version facts:
- `room-ktx` re-exports `room-runtime` since 2.7.0 and carries no additional code; keep it until Room 3.0 removes it to avoid import churn — **do not add it as a new dependency on modules that don't already have it**
- Use `ksp()` not `kapt()` — KSP generates Kotlin code by default since 2.7.0
- DataStore project-pinned version is **1.2.0**; the storage backend changed from `OkioStorage` to `FileStorage` in 1.2.0 — verify against the version catalog before bumping
- The Room Gradle Plugin (`id("androidx.room")`) with its `room { schemaDirectory(...) }` DSL is not applied in the current project; schema export is controlled via `ksp` arguments instead if needed

---

## Room — FrameportDatabase

### @Database declaration

```kotlin
@Database(
    entities = [
        MediaItemEntity::class,
        TransferHistoryEntity::class,
        DiagnosticEventEntity::class,
    ],
    version = 1,
    exportSchema = true,
    autoMigrations = [
        // add AutoMigration entries as version increases
    ],
)
@TypeConverters(FrameportTypeConverters::class)
abstract class FrameportDatabase : RoomDatabase() {
    abstract fun mediaItemDao(): MediaItemDao
    abstract fun transferHistoryDao(): TransferHistoryDao
    abstract fun diagnosticEventDao(): DiagnosticEventDao
}
```

Provide via Hilt:

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {
    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): FrameportDatabase =
        Room.databaseBuilder(context, FrameportDatabase::class.java, "frameport.db")
            .build()
    // Expose individual DAOs as @Provides bindings referencing the database
}
```

### @Entity examples

```kotlin
@Entity(tableName = "media_items")
data class MediaItemEntity(
    @PrimaryKey val id: String,                        // device-assigned ID
    @ColumnInfo(name = "camera_file_name") val cameraFileName: String,
    @ColumnInfo(name = "capture_instant") val captureInstant: Long, // epoch millis via TypeConverter
    @ColumnInfo(name = "media_type") val mediaType: String,
    @ColumnInfo(name = "local_uri") val localUri: String?,
    @ColumnInfo(name = "tag_list") val tagList: String,             // JSON via TypeConverter
)

@Entity(
    tableName = "transfer_history",
    foreignKeys = [
        ForeignKey(
            entity = MediaItemEntity::class,
            parentColumns = ["id"],
            childColumns = ["media_item_id"],
            onDelete = ForeignKey.CASCADE,
        )
    ],
    indices = [Index("media_item_id")],
)
data class TransferHistoryEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "media_item_id") val mediaItemId: String,
    @ColumnInfo(name = "transfer_instant") val transferInstant: Long,
    @ColumnInfo(name = "status") val status: String, // map TransferStatus enum via TypeConverter
)

@Entity(tableName = "diagnostic_events")
data class DiagnosticEventEntity(
    @PrimaryKey(autoGenerate = true) val rowId: Long = 0,
    @ColumnInfo(name = "event_instant") val eventInstant: Long,
    @ColumnInfo(name = "event_type") val eventType: String,
    @ColumnInfo(name = "payload_json") val payloadJson: String,
)
```

### @TypeConverters

```kotlin
class FrameportTypeConverters {

    // Instant <-> Long (epoch millis)
    @TypeConverter fun instantToLong(value: Instant): Long = value.toEpochMilli()
    @TypeConverter fun longToInstant(value: Long): Instant = Instant.ofEpochMilli(value)

    // List<String> <-> JSON string (simple tag lists; use kotlinx.serialization or Gson)
    @TypeConverter fun listToJson(value: List<String>): String =
        Json.encodeToString(ListSerializer(String.serializer()), value)
    @TypeConverter fun jsonToList(value: String): List<String> =
        Json.decodeFromString(ListSerializer(String.serializer()), value)
}
```

Register `@TypeConverters(FrameportTypeConverters::class)` on the `@Database` class, not on individual DAOs.

### @Dao — reactive and one-shot patterns

```kotlin
@Dao
interface MediaItemDao {

    // Reactive query for UI — Room re-emits on table invalidation
    @Query("SELECT * FROM media_items ORDER BY capture_instant DESC")
    fun observeAll(): Flow<List<MediaItemEntity>>   // Flow<List<T>?> is REJECTED by Kotlin CodeGen

    // One-shot write — must be suspend
    @Upsert
    suspend fun upsert(item: MediaItemEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertAll(items: List<MediaItemEntity>)

    @Delete
    suspend fun delete(item: MediaItemEntity)

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun findById(id: String): MediaItemEntity?

    // Paging 3 integration (requires room-paging artifact)
    @Query("SELECT * FROM media_items ORDER BY capture_instant DESC")
    fun pagingSource(): PagingSource<Int, MediaItemEntity>
}
```

Collect in ViewModel, NOT in Composable:

```kotlin
// ViewModel
val mediaItems: StateFlow<List<MediaItemEntity>> =
    mediaItemDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
```

### Schema migrations

**Additive (new nullable column)** — use `AutoMigration`:

```kotlin
// In @Database:
autoMigrations = [AutoMigration(from = 1, to = 2)]

// No AutoMigrationSpec needed for simple nullable column addition.
// For rename/delete, add a spec:
@RenameColumn(tableName = "media_items", fromColumnName = "old_name", toColumnName = "new_name")
class V1ToV2Spec : AutoMigrationSpec
// Then: AutoMigration(from = 1, to = 2, spec = FrameportDatabase.V1ToV2Spec::class)
```

**Destructive changes** — use manual `Migration`:

```kotlin
val MIGRATION_2_3 = object : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("CREATE TABLE media_items_new (id TEXT NOT NULL PRIMARY KEY, ...)")
        db.execSQL("INSERT INTO media_items_new SELECT ... FROM media_items")
        db.execSQL("DROP TABLE media_items")
        db.execSQL("ALTER TABLE media_items_new RENAME TO media_items")
    }
}
// Register in databaseBuilder: .addMigrations(MIGRATION_2_3)
```

Note: the `SupportSQLiteDatabase`-based override is correct for Room 2.8.x Android-only. The `SQLiteConnection`-based override is for Room 3.0 / KMP targets — do not use until migrating to Room 3.0.

---

## Proto DataStore — settings

### Proto definition (example: app_settings.proto)

```protobuf
syntax = "proto3";
option java_package = "dev.po4yka.frameport.core.storage.proto";
option java_multiple_files = true;

message AppSettings {
  string theme = 1;           // "SYSTEM" | "LIGHT" | "DARK"
  bool show_grid_view = 2;
  int32 schema_version = 3;
}
```

### Serializer

```kotlin
object AppSettingsSerializer : Serializer<AppSettings> {
    override val defaultValue: AppSettings = AppSettings.getDefaultInstance()

    override suspend fun readFrom(input: InputStream): AppSettings =
        try {
            AppSettings.parseFrom(input)
        } catch (e: InvalidProtocolBufferException) {
            throw CorruptionException("Cannot read proto.", e)
        }

    override suspend fun writeTo(t: AppSettings, output: OutputStream) =
        t.writeTo(output)
}
```

### Single-instance property delegate

```kotlin
// Top-level in :core:storage — one instance per file enforced by the delegate
val Context.appSettingsDataStore: DataStore<AppSettings> by dataStore(
    fileName = "app_settings.pb",
    serializer = AppSettingsSerializer,
    corruptionHandler = ReplaceFileCorruptionHandler { cause ->
        Log.e("DataStore", "AppSettings corrupted, resetting to default", cause)
        AppSettings.getDefaultInstance()
    },
)
```

Repeat the same pattern for `PrivacySettingsDataStore` (`privacy_settings.pb`) and `ConnectionSettingsDataStore` (`connection_settings.pb`).

### Hilt binding

```kotlin
@Module
@InstallIn(SingletonComponent::class)
object DataStoreModule {

    @Provides
    @Singleton
    fun provideAppSettingsDataStore(@ApplicationContext context: Context): DataStore<AppSettings> =
        context.appSettingsDataStore

    // Bind to a repository interface consumed by ViewModels
    @Provides
    @Singleton
    fun provideAppSettingsRepository(
        dataStore: DataStore<AppSettings>,
    ): AppSettingsRepository = AppSettingsRepositoryImpl(dataStore)
}
```

Do not call `DataStoreFactory.create()` without a `@Singleton` wrapper — it will create a new instance on each injection and cause `IllegalStateException` at runtime.

### Reading and writing

```kotlin
// Repository implementation
class AppSettingsRepositoryImpl(
    private val dataStore: DataStore<AppSettings>,
) : AppSettingsRepository {

    // Reactive read — never blocks
    override val settings: Flow<AppSettings> = dataStore.data
        .catch { e ->
            if (e is IOException) emit(AppSettings.getDefaultInstance())
            else throw e
        }

    // Atomic write
    override suspend fun setTheme(theme: String) {
        dataStore.updateData { current ->
            current.toBuilder().setTheme(theme).build()
        }
    }
}
```

In Compose:

```kotlin
// In ViewModel:
val settings = settingsRepository.settings
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), AppSettings.getDefaultInstance())

// In Composable:
val settings by viewModel.settings.collectAsStateWithLifecycle()
```

---

## Encrypted storage — Wi-Fi credentials and pairing secrets

`security-crypto` (`EncryptedSharedPreferences`, `EncryptedFile`, `MasterKeys`) is **deprecated as of June 2025**. Do not use it for new code. Use direct Android Keystore.

### KeystoreEncryptedStorage

```kotlin
class KeystoreEncryptedStorage(private val filesDir: File) {

    private val keyAlias = "frameport_wifi_creds_key"

    private fun getOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        if (keyStore.containsAlias(keyAlias)) {
            return (keyStore.getEntry(keyAlias, null) as KeyStore.SecretKeyEntry).secretKey
        }
        val keyGenerator = KeyGenerator.getInstance(
            KeyProperties.KEY_ALGORITHM_AES,
            "AndroidKeyStore",
        )
        val spec = KeyGenParameterSpec.Builder(
            keyAlias,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUnlockedDeviceRequired(true) // key inaccessible while device is locked
            .applyStrongBoxIfAvailable()
            .build()
        keyGenerator.init(spec)
        return keyGenerator.generateKey()
    }

    fun encrypt(plaintext: ByteArray, fileName: String) {
        val key = getOrCreateKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv                   // 12 bytes, fresh per encryption call
        val ciphertext = cipher.doFinal(plaintext)
        // Prepend IV to ciphertext: [12-byte IV][ciphertext]
        File(filesDir, fileName).writeBytes(iv + ciphertext)
    }

    fun decrypt(fileName: String): ByteArray {
        val key = getOrCreateKey()
        val blob = File(filesDir, fileName).readBytes()
        val iv = blob.copyOfRange(0, 12)
        val ciphertext = blob.copyOfRange(12, blob.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        val spec = GCMParameterSpec(128, iv)
        cipher.init(Cipher.DECRYPT_MODE, key, spec)
        return cipher.doFinal(ciphertext)
    }
}

// Extension used during key generation
private fun KeyGenParameterSpec.Builder.applyStrongBoxIfAvailable(): KeyGenParameterSpec.Builder {
    return try {
        setIsStrongBoxBacked(true)
    } catch (_: StrongBoxUnavailableException) {
        // Device does not have StrongBox; fall back to TEE-backed key
        this
    }
}
```

For BLE pairing secrets, use a separate key alias `frameport_pairing_secret_key` with the same spec. Both aliases share `setUnlockedDeviceRequired(true)`. Do NOT store credentials or secrets as plain fields in a proto DataStore file — DataStore files are plaintext on disk.

`datastore-tink` (encrypted DataStore artifact) is alpha (~1.3.0-alpha09); do not adopt it for Frameport v1.

---

## Testing

### DAO tests (in-memory, fast)

```kotlin
// :core:storage/src/androidTest
@RunWith(AndroidJUnit4::class)
class MediaItemDaoTest {

    private lateinit var db: FrameportDatabase
    private lateinit var dao: MediaItemDao

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            FrameportDatabase::class.java,
        ).build()
        dao = db.mediaItemDao()
    }

    @After
    fun teardown() = db.close()

    @Test
    fun insertAndObserve() = runTest {
        val item = MediaItemEntity(id = "DSC001", ...)
        dao.upsert(item)
        val items = dao.observeAll().first()
        assertThat(items).contains(item)
    }
}
```

In-memory databases are recreated per test and require no migration setup. Use `MigrationTestHelper` only for migration-specific tests that exercise schema files.

### Migration tests

```kotlin
// Requires schema JSON files committed to VCS (from Room Gradle Plugin)
@RunWith(AndroidJUnit4::class)
class MigrationTest {

    @get:Rule
    val helper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        FrameportDatabase::class.java,
    )

    @Test
    fun migrate1To2() {
        val db = helper.createDatabase("test.db", 1)
        db.close()
        helper.runMigrationsAndValidate("test.db", 2, true, MIGRATION_1_2)
    }
}
```

---

## Room 3.0 readiness

Room 3.0-alpha (group `androidx.room3`) is in active development as of early 2026. Stay on **2.8.4** for v1. To reduce future migration cost, write all DAO methods as `suspend fun` or `Flow<>` now — blocking DAO functions (non-suspend, non-Flow) will be rejected by Room 3.0 at compile time. The `SupportSQLiteDatabase`-based `Migration.migrate()` override will need updating to `SQLiteConnection`-based when targeting Room 3.0, but that is a mechanical change.

---

## Key pitfalls

| Pitfall | Consequence | Fix |
|---|---|---|
| `kapt()` instead of `ksp()` for room-compiler | Slower, Java codegen, legacy path | Always `ksp("androidx.room:room-compiler:2.8.4")` |
| Adding `room-ktx` to a module that doesn't already have it | Unnecessary; it re-exports `room-runtime` with no extra code since 2.7.0 | Do not add as a new dep; modules that already declare it can keep it until Room 3.0 |
| `Flow<List<T>?>` return type | Rejected by Kotlin CodeGen (2.7+) | Use `Flow<List<T>>` — empty list = zero results |
| Blocking `fun` DAO methods | Blocks calling thread; banned in Room 3.0 | Always `suspend fun` or `Flow<>` |
| Missing `room { schemaDirectory(...) }` | AutoMigration fails silently or produces wrong schema JSON | Configure Room Gradle Plugin in `:core:storage/build.gradle.kts` |
| `AutoMigrationSpec` missing for rename/delete | Compile-time error about ambiguous schema change | Add `@RenameTable`/`@RenameColumn`/`@DeleteTable`/`@DeleteColumn` on spec class |
| `EncryptedSharedPreferences` in new code | Depends on deprecated library (June 2025) | Use direct Keystore + AES-256-GCM |
| Multiple DataStore instances for same file | `IllegalStateException` at runtime | Use property delegate; provide as `@Singleton` via Hilt |
| Mixing `SingleProcessDataStore` and `MultiProcessDataStore` on same file | Data corruption | Frameport is single-process; always use `dataStore` delegate |
| Not catching `IOException` from `DataStore.data` | App crash on disk read error | `.catch { e -> if (e is IOException) emit(defaultValue) else throw e }` |
| Forgetting to persist GCM IV alongside ciphertext | Decryption permanently impossible after process restart | Prepend 12-byte IV to ciphertext blob before writing |
| `datastore-tink` alpha in production | No stability guarantee; API may break | Implement Keystore encryption in `:core:storage` instead |
| DAO access outside `:core:storage` | Violates module boundary invariants | DAOs must only be accessed through repository interfaces in `:core:storage` |
| `fallbackToDestructiveMigration()` in production builds | Silently deletes all user data | Only during early development; must be removed before release |

---

## References

- Room Jetpack releases (stable 2.8.4): https://developer.android.com/jetpack/androidx/releases/room
- Room 3.0 — Modernizing the Room: https://developer.android.com/blog/posts/modernizing-the-room
- Migrate Room database versions: https://developer.android.com/training/data-storage/room/migrating-db-versions
- Accessing data using Room DAOs: https://developer.android.com/training/data-storage/room/accessing-data
- Save data in a local database using Room: https://developer.android.com/training/data-storage/room
- DataStore Jetpack releases (project-pinned 1.2.0; check for stable updates): https://developer.android.com/jetpack/androidx/releases/datastore
- App Architecture: DataStore: https://developer.android.com/topic/libraries/architecture/datastore
- Jetpack Security releases (security-crypto 1.1.0 deprecated June 2025): https://developer.android.com/jetpack/androidx/releases/security
- Android Keystore system: https://developer.android.com/privacy-and-security/keystore
