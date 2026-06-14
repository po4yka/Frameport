package dev.po4yka.frameport.camera.media

// This file is intentionally empty. The placeholder MediaImportWriter interface and
// NoOpMediaImportWriter class that existed here in M08 have been superseded by
// MediaStoreWriter (interface) and MediaStoreWriterImpl (implementation) in M09.
//
// MediaStoreWriter owns the full ADR-0004 import lifecycle:
//   create pending row → open write fd → borrow fd to Rust → collect progress →
//   finalize/delete pending → record in ImportCatalog → emit ImportState.
//
// See MediaStoreWriter.kt and MediaStoreWriterImpl.kt in this package.
