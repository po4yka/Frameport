# Android Modules

The root app module depends on feature modules and app-level DI bindings. Feature modules depend on `:core:designsystem`, `:core:model`, and `:camera:api` so UI code can render state and emit actions without directly reaching platform, storage, or native SDK code.

Core modules hold shared primitives. `:core:model` contains stable domain models. `:core:common` contains cross-cutting utilities such as dispatcher qualifiers. `:core:designsystem` contains Compose UI primitives and the Frameport theme. `:core:permissions`, `:core:logging`, `:core:storage`, and `:core:testing` are reserved for platform permission helpers, logging setup, persistence, and test support.

Camera modules are split by boundary. `:camera:api` exposes Android-free contracts using suspend functions, `Flow`, and `StateFlow`. `:camera:domain` is for use cases and policy. `:camera:data` coordinates repository implementations. `:camera:bluetooth`, `:camera:wifi`, `:camera:usb`, `:camera:media`, and `:camera:diagnostics` isolate Android platform adapters. `:native:fuji-rust-android` is the future JNI wrapper and currently contains only a no-op native SDK contract.

Dependency direction is one-way: app to features and implementations, features to contracts and UI/model core, data to platform adapters and native bridge, platform adapters to core models, and API to models only. This avoids circular dependencies and prevents protocol or platform APIs from entering Composables or ViewModels.
