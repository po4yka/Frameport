pub use fuji_core::{DiagnosticCategory, DiagnosticEvent};

pub fn native_placeholder_event() -> DiagnosticEvent {
    DiagnosticEvent::placeholder(
        DiagnosticCategory::Native,
        "Rust diagnostics are placeholders; no camera communication is implemented.",
    )
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn placeholder_event_is_native() {
        assert_eq!(
            native_placeholder_event().category,
            DiagnosticCategory::Native
        );
    }
}
