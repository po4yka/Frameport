# fuji-ble-protocol test fixtures

All byte sequences in this directory are **synthetic** — they are constructed
from the documented payload layouts in `docs/reference/ble-wifi-discovery.md`
and `docs/reference/master-constants.md`. They do NOT originate from real
Fujifilm camera captures. The actual numeric values (e.g. company ID 0x04D8,
type byte 0x02, payload lengths) are interoperability facts documented in the
references above.

No real device MACs, SSIDs, passphrases, serial numbers, or pairing tokens
appear here. Token bytes are synthetic zero-pattern or counter-pattern values
chosen to exercise boundary conditions only.

Governed by `golden-bless-discipline.md` — do not regenerate with
`FRAMEPORT_BLESS_GOLDENS=1` without human approval and a commit-message
rationale.
