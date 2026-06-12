# Navigation

Frameport uses Navigation 3 from the app layer. Routes are represented by typed `FrameportDestination` keys rather than string routes, and the active back stack is created in `FrameportApp` with `rememberNavBackStack`.

`FrameportNavHost` maps destination keys to feature route composables. The host owns navigation mutations, including replacing onboarding with home, navigating from home cards, moving from scan to manual connection, and returning cancellation flows to home.

Feature modules must not own app navigation state. A feature route may accept callbacks such as `onCancel` or `onContinue`, but repositories, no-op camera implementations, native wrappers, and stateless content composables must not navigate.

Routes should pass stable IDs only when parameters are needed. Large camera summaries, media objects, native sessions, descriptors, Android framework objects, or protocol payloads must stay out of navigation keys.

Current destination keys are Onboarding, Home, CameraScan, CameraConnect, Gallery, Import, Remote, LiveView, Diagnostics, and Settings. They are placeholders for UI shell validation only and do not imply implemented camera support.
