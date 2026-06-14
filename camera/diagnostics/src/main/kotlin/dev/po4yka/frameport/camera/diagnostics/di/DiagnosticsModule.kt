package dev.po4yka.frameport.camera.diagnostics.di

import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import dev.po4yka.frameport.camera.api.DiagnosticsRepository
import dev.po4yka.frameport.camera.diagnostics.DiagnosticsRepositoryImpl
import javax.inject.Singleton

/**
 * Hilt module that wires the diagnostics subsystem into the [SingletonComponent].
 *
 * [DiagnosticTimeline], [DiagnosticCollector], [DiagnosticBundleExporter], and
 * [RedactionPipeline] are all annotated with [@Singleton] and [@Inject constructor],
 * so Hilt binds them automatically without explicit [@Provides] methods.
 *
 * This module's sole responsibility is the [@Binds] that maps the
 * [DiagnosticsRepository] interface to [DiagnosticsRepositoryImpl].
 *
 * IMPORTANT: any previous [@Binds] for [DiagnosticsRepository] in :camera:data
 * (DiagnosticsRepositoryImpl in that module) and any provider for
 * NoOpDiagnosticEventSink in :app must be removed — they conflict with this binding.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class DiagnosticsModule {
    @Binds
    @Singleton
    abstract fun bindDiagnosticsRepository(impl: DiagnosticsRepositoryImpl): DiagnosticsRepository
}
