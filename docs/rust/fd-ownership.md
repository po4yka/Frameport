# File Descriptor Ownership

Frameport's future production path is Android-owned setup followed by Rust-owned protocol work. Android must request and bind the network, USB, or storage resource first, then pass only explicit file descriptors across JNI when ownership rules are clear.

For Wi-Fi PTP-IP, Android will request the camera Wi-Fi network, bind socket creation to the selected Android `Network`, connect the socket to the camera endpoint, duplicate the descriptor when ownership transfers, and pass the owned descriptor to Rust. Rust must not assume a camera IP is reachable without a descriptor or process/socket binding provided by Android.

For MediaStore imports, Android will create the pending MediaStore row, open a `ParcelFileDescriptor`, duplicate the descriptor when ownership transfers, and pass that owned descriptor to Rust for streaming. Rust must close only descriptors it explicitly owns, and Kotlin must not reuse a descriptor after ownership moves.

For USB, Android will own `UsbManager` discovery and permission. If USB is implemented later, Android will open the USB device and pass documented descriptors or handles to Rust; Rust must not require root or hidden platform access.

The current JNI functions do not accept file descriptors. This document defines the rule future FFI methods must follow before any descriptor-taking function is added.
