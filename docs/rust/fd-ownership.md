# File Descriptor Ownership

Frameport's future production path is Android-owned setup followed by Rust-owned protocol work. Android must request and bind the network, USB, or storage resource first, then pass only explicit file descriptors across JNI when ownership rules are clear.

For Wi-Fi PTP-IP, Android requests the camera Wi-Fi network, binds socket creation to the selected Android `Network`, connects the socket to the camera endpoint, and creates a detached duplicate with `ParcelFileDescriptor.fromSocket(socket).detachFd()`. That detached fd is caller-owned only until the JNI bridge call returns. Rust borrows and duplicates the fd synchronously with `BorrowedFd::try_clone_to_owned`; the Kotlin camera data adapter closes the detached fd after the bridge returns, and Rust closes only its own duplicate when the session or live-view worker stops. Rust must not assume a camera IP is reachable without a descriptor or process/socket binding provided by Android.

For MediaStore imports, Android creates the pending MediaStore row, opens a `ParcelFileDescriptor`, and passes the Android-owned output fd to Rust. Rust borrows and duplicates the fd for the transfer and closes only its duplicate. Kotlin keeps ownership of the original `ParcelFileDescriptor` and must close it after the transfer terminates.

For USB, Android will own `UsbManager` discovery and permission. If USB is implemented later, Android will open the USB device and pass documented descriptors or handles to Rust; Rust must not require root or hidden platform access.

Descriptor-taking JNI functions must state whether Rust borrows-and-dups the fd or takes ownership of the exact fd. The Kotlin caller must close every Android-owned or detached fd that Rust only borrows, and must not close any fd whose exact ownership has been explicitly transferred to Rust.
