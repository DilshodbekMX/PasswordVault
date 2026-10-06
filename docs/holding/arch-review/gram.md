**D-10a in full: the fields block of our CSV.** Every later version must read what 1.0 writes, so the block has a grammar. The formats package copies it into formats.md §4.4 with its vectors: the four cases of formats-review P8 and the two reserved labels.

```
note    = user-notes [ LF LF block ]                 ; written only when the block has at least one line
block   = marker 1*( LF line )
marker  = "--- PasswordVault (" count " lines) ---"  ; count: the lines after the marker, continuation lines included, ASCII digits
line    = label [ " (hidden)" ] ": " text *( LF "  " text )    ; later lines of a value are indented by two spaces
```

- **Order of lines:** `Title: <title>` when the title differs from the host of the first website (ux.md §14.10); one line per extra field, in order; `Old password (hidden): <pendingOldPassword>` while a change is pending (D-10d).
- **Labels.** Field names are trimmed when they are entered or imported, so a label never starts or ends with a space. A backslash makes the next character literal: a label is written with `\` as `\\`, `:` as `\:` and `(` as `\(`. So the first unescaped `: ` ends the label, and an unescaped ` (hidden)` at its end can only be the flag.
- **Reserved labels:** `Title` and `Old password`, only when written with no backslash. A user's own field with one of these names is written with its first letter escaped (`\Title`), and reads back as an ordinary field.
- **Recognised only when it is ours:** the marker must follow a blank line and `count` must equal exactly the number of lines after it, to the end of the note. Otherwise the whole note is text. A note that contains a pasted block therefore stays a note.
- The marker, `(hidden)` and the two reserved labels stay in English in every language, because the importer reads them.

