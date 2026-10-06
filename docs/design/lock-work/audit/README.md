# Scripts behind lock.md's audit

The models and simulations the audit of [lock.md](../../lock.md) ran on 2026-10-06. lock.md reproduces the ones it relies on in its appendices; these are the files as they were run. They were rescued from the session's scratchpad into `docs/holding/lock-audit-scripts/` and moved here by build-plan package 0.3. Python 3; `appC.py` also needs `cryptography`.

| File | In lock.md | What it does |
|---|---|---|
| `guard_model.py` | Appendix B, identical | The model check of the attempt counter (§6): every interleaving of wrong and right PINs, kills and power cuts up to a depth. `python3 guard_model.py 2 3 1 34 2 3` prints 2 and 3. A seventh argument selects a broken variant (`noage`, `countafter`, `nowindow` or `stale`), which prints a violation with its witness. Package 2.11 ports it as lock.md's test T5 |
| `mut_nowindow.py`, `mut_stale.py` | — | Earlier copies of `guard_model.py` with the `nowindow` and the `stale` mistake written in. `guard_model.py` now takes both as its seventh argument |
| `appC.py` | Appendix C, identical | The vectors of §4.3: the PIN message, `KEK_pin` and the PIN wrap |
| `appD.py` | Appendix D, the same code | How often an honest owner loses the PIN, by unlocks a day, typo rate and days between master-password entries |
| `simD.py` | — | An earlier version of `appD.py` |
| `appF.py` | Appendix F, identical | How many days 24 credited hours take for a phone that is on from 07:00 to 23:00 (§6.9) |
| `credit.py` | — | An earlier version of `appF.py` |
| `fold.py`, `Fold.java` | §8.1 | The fold order of `MasterPasswordBytes` (NFC, white space, apostrophes) over every code point, in Python and in Java: no code point folds into or out of the two lists under NFC, and the result is stable and the same for NFC and NFD input |

Not kept: `patch.py`, a helper that applied text patches to lock.md during the audit. It remains in the history of the tag `design-ok`.
