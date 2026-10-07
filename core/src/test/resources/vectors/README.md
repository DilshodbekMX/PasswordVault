# Vault file test vectors (architecture.md §4.13)

Frozen (architecture.md §8.7): after the first release a change to either file is a new format
version, never an edit.

| File | What it is | Size, SHA-256 |
|---|---|---|
| `v1-container.pvault` | Vector A, the container (§4.13.1): the framing, the header and the AAD, around the plaintext `{"schema":1,"entries":[]}` and 4,071 spaces | 4,667 bytes, `3210ed2c15bddde2e2d3793aeff65deda2d745d1ac10ed6c6443be1711ee1d2d` |
| `v1-body.pvault` | Vector B, the body as a version 1 writer produces it (§4.13.2) | 4,667 bytes, `1bb8d230c949b197859bbd9fec613bd65f149595b3f9db98b40dd6dc2e5312c7` |

Written on 2026-10-07 by the script of architecture.md's appendix (`docs/design/vectors/generate-vectors.py`,
the same text but for its final newline) with Python 3.12.3 and `cryptography` 41.0.7, and equal byte for
byte to the reference copies `docs/design/vectors/vector-a.pvault` and `vector-b.pvault`.

The tests of `core.vaultfile` (`VaultVectorsTest`) produce both files from their inputs and read both back;
the device test of architecture.md §8.3 and the decrypt script use them too.
