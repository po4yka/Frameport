package dev.po4yka.frameport.nativebridge

class NativeLibraryLoader(
    private val libraryName: String = "fuji_ffi",
    private val loadLibrary: (String) -> Unit = System::loadLibrary,
) {
    @Volatile
    private var cachedState: NativeLibraryState? = null

    // @Synchronized ensures the check-then-act on cachedState is atomic.
    // The fast path (cachedState != null) still benefits from @Volatile above,
    // but the synchronized gate on the first call prevents a race between two
    // threads both observing null and both invoking loadLibrary.
    @Synchronized
    fun load(): NativeLibraryState {
        cachedState?.let { return it }

        val state =
            try {
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
