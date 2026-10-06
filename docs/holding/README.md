# Holding area

Files rescued on 2026-10-06 from the session's temporary scratch space (under /tmp, which a reboot
empties) before the build started. They are inputs for build-plan.md package 0.3, which moves what is
needed to its final place (docs/lab/clipboard-artifacts/, tools/validate/, core/src/test/resources/...)
and then deletes this directory.

- clipboard-lab/: the clipboard lab's kit, driver (clipexp.py), analysis scripts, raw runs (runs*.jsonl),
  report-tables.md, and the final snippet. The report itself is docs/lab/clipboard.md.
- formats-samples/: files written by the export prototype (prototypes/formats) and the expected values
  its validation scripts compare against.
- arch-review/: the script that generates the vault file test vectors A and B of architecture.md's
  appendix, and the helpers the architecture's reviewers used.
- lock-audit-scripts/: the models and simulations behind lock.md's audit (also reproduced in its appendices).
