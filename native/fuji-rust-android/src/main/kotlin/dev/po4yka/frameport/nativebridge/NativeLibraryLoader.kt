package dev.po4yka.frameport.nativebridge

class NativeLibraryLoader(
    private val libraryName: String = "fuji_ffi",
    private val loadLibrary: (String) -> Unit = System::loadLibrary,
) {
    @Volatile
    private var cachedState: NativeLibraryState? = null

    fun load(): NativeLibraryState {
        cachedState?.let { return it }

        val state = try {
            loadLibrary(libraryName)
            NativeLibraryState(
                isLoaded = true,
                message = "Native Fuji SDK library loaded.",
                failure = null,
            )
        } catch (error: UnsatisfiedLinkError) {
            NativeLibraryState(
                isLoaded = false,
                message = "Native Fuji SDK library is unavailable; using no-op bridge.",
                failure = error.message,
            )
        } catch (error: SecurityException) {
            NativeLibraryState(
                isLoaded = false,
                message = "Native Fuji SDK library could not be loaded; using no-op bridge.",
                failure = error.message,
            )
        }

        cachedState = state
        return state
    }
}

data class NativeLibraryState(
    val isLoaded: Boolean,
    val message: String,
    val failure: String?,
)
