package dev.po4yka.frameport.camera.data

import dev.po4yka.frameport.camera.domain.AllowlistRemoteCapabilityChecker
import dev.po4yka.frameport.camera.domain.RemoteCapabilityChecker
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Injectable adapter that delegates to [AllowlistRemoteCapabilityChecker].
 *
 * [AllowlistRemoteCapabilityChecker] is a public `object` in :camera:domain (a plain JVM module
 * with no Hilt) — public so this :camera:data adapter can reference it across the module boundary.
 * This class lives in :camera:data (which has Hilt and depends on :camera:domain) so it can be
 * bound via [CameraBindingsModule] without pulling Hilt into :camera:domain.
 *
 * The delegation is zero-overhead: [AllowlistRemoteCapabilityChecker.isRemoteCapable] is a
 * constant-time Set lookup against a compile-time allowlist.
 */
@Singleton
class RemoteCapabilityCheckerBinding
    @Inject
    constructor() : RemoteCapabilityChecker {
        override fun isRemoteCapable(cameraModel: String?): Boolean =
            AllowlistRemoteCapabilityChecker.isRemoteCapable(cameraModel)
    }
