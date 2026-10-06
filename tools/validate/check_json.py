"""Checks a JSON export against formats.md §5 and against what Bitwarden, Proton Pass and KeePassXC read
(keys and types from their sources: bitwarden/clients CipherExport, LoginExport, FieldExport,
PasswordHistoryExport and isUnencrypted(); ProtonMail/WebClients bitwarden.reader.ts; KeePassXC
src/format/BitwardenReader.cpp), and compares the item keys with Proton's Bitwarden fixture.

1. The text: UTF-8 without a BOM, no CR, LF line ends with a final newline, two-space indent; it parses as
   JSON with no duplicate key, no NaN or Infinity, and no unpaired surrogate (formats.md §2 rule 8).
2. The structure: "encrypted": false; "folders" and "items" arrays; every item a login (type 1) with the keys
   and types the three readers need, ISO 8601 UTC dates with milliseconds, a UUID id that no other item
   has (formats-review P1), at most 5 previous passwords, "deletedDate" null (architecture.md D-10e), empty
   notes, usernames and passwords written as null; our "passwordVault" objects well formed.
3. The keys of Proton's fixture that our items lack, and the keys we add (information only).

Ported from prototypes/formats/validate/check_json.py, with the fix of formats-review P18: the prototype's
script looked up two entries of its own test data and crashed with StopIteration on any other file; this
one looks up nothing by name and prints no value.
Usage: check_json.py [--fixture bitwarden.json] [--show-values] FILE.json
"""
import argparse
import json
import os
import re
import sys
import uuid

from common import PROTON_FIXTURE, Report, has_lone_surrogate

ISO = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")
GENERATOR = re.compile(r"^PasswordVault [A-Za-z0-9 ._()-]{1,50}$")  # "PasswordVault " + versionName (R-14)


class Duplicate(Exception):
    pass


def no_duplicates(pairs):
    seen = set()
    for k, _ in pairs:
        if k in seen:
            raise Duplicate(f"duplicate key {k!r}")
        seen.add(k)
    return dict(pairs)


def refuse_constant(name):
    raise ValueError(f"{name} is not JSON")


def strings(x):
    if isinstance(x, str):
        yield x
    elif isinstance(x, dict):
        for k, v in x.items():
            yield k
            yield from strings(v)
    elif isinstance(x, list):
        for v in x:
            yield from strings(v)


def check(path, report, fixture=PROTON_FIXTURE):
    """Checks one JSON export; returns its items, or None."""
    name = os.path.basename(path)
    raw = open(path, "rb").read()
    report.check(not raw.startswith(b"\xef\xbb\xbf"), f"{name}: no byte order mark")
    try:
        text = raw.decode("utf-8")
    except UnicodeDecodeError as e:
        report.fail(f"{name}: valid UTF-8", [f"invalid byte at offset {e.start}"])
        return None
    text = text.lstrip("﻿")
    lines = text.split("\n")
    fmt = []
    if "\r" in text:
        fmt.append("contains CR")
    if not text.endswith("\n"):
        fmt.append("no final newline")
    if len(lines) > 1 and not (lines[1].startswith("  ") and not lines[1].startswith("   ")):
        fmt.append("the second line is not indented by two spaces")
    odd = [i + 1 for i, l in enumerate(lines) if (len(l) - len(l.lstrip(" "))) % 2 or l.startswith("\t")]
    if odd:
        fmt.append(f"indent not a multiple of two spaces on line {odd[0]}")
    report.check(not fmt, f"{name}: LF line ends, final newline, two-space indent (formats.md §5.2)", fmt)
    try:
        d = json.loads(text, object_pairs_hook=no_duplicates, parse_constant=refuse_constant)
    except (ValueError, Duplicate) as e:
        report.fail(f"{name}: parses as JSON with no duplicate key", [str(e)[:200]])
        return None
    report.ok(f"{name}: parses as JSON with no duplicate key")
    report.check(not any(has_lone_surrogate(s) for s in strings(d)),
                 f"{name}: no unpaired surrogate (formats.md §2 rule 8 writes U+FFFD)")

    problems = []

    def need(cond, msg):
        if not cond:
            problems.append(msg)
        return cond

    if not need(isinstance(d, dict), "the top level is not an object"):
        report.fail(f"{name}: structure", problems)
        return None
    need(d.get("encrypted") is False, '"encrypted" must be false (Bitwarden isUnencrypted, Proton, KeePassXC)')
    need(isinstance(d.get("folders"), list), '"folders" must be an array (Proton Array.isArray; KeePassXC needs the key)')
    if not need(isinstance(d.get("items"), list), '"items" must be an array'):
        report.fail(f"{name}: structure", problems)
        return None
    folders = d["folders"] if isinstance(d.get("folders"), list) else []
    folder_ids = {f.get("id") for f in folders if isinstance(f, dict)}
    need(all(isinstance(f, dict) and isinstance(f.get("name"), str) and f["name"] for f in folders), "every folder has a name")
    pv = d.get("passwordVault")
    if need(isinstance(pv, dict), 'no top-level "passwordVault" object'):
        need(pv.get("format") == "passwordvault-export", 'passwordVault.format must be "passwordvault-export"')
        need(pv.get("version") == 1, "passwordVault.version must be 1")
        need(isinstance(pv.get("exportedAt"), str) and bool(ISO.match(pv["exportedAt"])), "passwordVault.exportedAt format")
        need(isinstance(pv.get("generator"), str) and bool(GENERATOR.match(pv["generator"])),
             'passwordVault.generator must be "PasswordVault <version>" (R-14)')
    ids = {}
    for n, it in enumerate(d["items"], start=1):
        where = f"item {n}"
        if not need(isinstance(it, dict), f"{where} is not an object"):
            continue
        need(it.get("type") == 1, f"{where}: type must be 1 (login)")
        need(isinstance(it.get("name"), str) and it["name"] != "", f"{where}: name must be a non-empty string")
        try:
            ok_id = isinstance(it.get("id"), str) and str(uuid.UUID(it["id"])) == it["id"].lower()
        except ValueError:
            ok_id = False
        if need(ok_id, f"{where}: id must be a UUID"):
            if it["id"] in ids:
                problems.append(f"{where}: the same id as item {ids[it['id']]} (formats-review P1)")
            ids.setdefault(it["id"], n)
        need(it.get("folderId") is None or it.get("folderId") in folder_ids, f"{where}: folderId must name a folder")
        need(isinstance(it.get("favorite"), bool) and it.get("reprompt") == 0, f"{where}: favorite and reprompt")
        need(it.get("notes") is None or (isinstance(it["notes"], str) and it["notes"] != ""),
             f"{where}: notes must be null or a non-empty string")
        need(it.get("deletedDate", None) is None, f"{where}: deletedDate must be null; deleted entries are in no plain export (D-10e)")
        lg = it.get("login")
        if need(isinstance(lg, dict), f"{where}: login must be an object"):
            need(all(k in lg for k in ("uris", "username", "password", "totp")), f"{where}: login keys")
            for k in ("username", "password"):
                need(lg.get(k) is None or (isinstance(lg[k], str) and lg[k] != ""), f"{where}: login.{k} must be null or a non-empty string")
            uris = lg.get("uris")
            need(isinstance(uris, list) and all(isinstance(u, dict) and set(u) == {"match", "uri"} and isinstance(u["uri"], str)
                                                for u in uris), f"{where}: login.uris")
        fields = it.get("fields")
        need(fields is None or (isinstance(fields, list) and all(
            isinstance(f, dict) and set(f) == {"name", "value", "type", "linkedId"} and f["type"] in (0, 1) for f in fields)),
            f"{where}: fields")
        for k in ("creationDate", "revisionDate"):
            need(isinstance(it.get(k), str) and bool(ISO.match(it[k])), f"{where}: {k} format")
        ph = it.get("passwordHistory")
        need(ph is None or (isinstance(ph, list) and len(ph) <= 5 and all(
            isinstance(h, dict) and isinstance(h.get("password"), str) and isinstance(h.get("lastUsedDate"), str)
            and ISO.match(h["lastUsedDate"]) for h in ph)), f"{where}: passwordHistory (at most 5, ISO dates)")
        ext = it.get("passwordVault")
        if ext is not None and need(isinstance(ext, dict), f"{where}: passwordVault must be an object"):
            for k, t in (("changePending", bool), ("checkIgnored", bool)):
                need(k not in ext or isinstance(ext[k], t), f"{where}: passwordVault.{k}")
            need(ext.get("passwordChangedAt") is None or (isinstance(ext["passwordChangedAt"], str) and ISO.match(ext["passwordChangedAt"])),
                 f"{where}: passwordVault.passwordChangedAt")
            need(ext.get("pendingOldPassword") is None or isinstance(ext["pendingOldPassword"], str),
                 f"{where}: passwordVault.pendingOldPassword (D-10c)")
            need("urls" not in ext or (isinstance(ext["urls"], list) and all(isinstance(u, str) for u in ext["urls"])),
                 f"{where}: passwordVault.urls")
            need("deletedAt" not in ext, f"{where}: passwordVault.deletedAt is never written (D-10c)")
    report.check(not problems, f"{name}: structure that Bitwarden, Proton Pass and KeePassXC read, and our extension"
                 f"  [{len(d['items'])} items, {len(folders)} folders]", problems)
    if fixture and os.path.exists(fixture):
        fx = json.load(open(fixture, encoding="utf-8"))
        fx_keys = set().union(*[set(i) for i in fx["items"] if i.get("type") == 1])
        our_keys = set().union(*[set(i) for i in d["items"] if isinstance(i, dict)]) if d["items"] else set()
        report.info(f"{name}: keys of Proton's Bitwarden fixture that our items lack: {sorted(fx_keys - our_keys) or 'none'}"
                    f"; keys we add: {sorted(our_keys - fx_keys) or 'none'}")
    else:
        report.info(f"Proton's fixture not found at {fixture}; key comparison skipped")
    return d["items"]


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("file")
    ap.add_argument("--fixture", default=PROTON_FIXTURE)
    ap.add_argument("--show-values", action="store_true")
    a = ap.parse_args()
    r = Report(a.show_values)
    check(a.file, r, a.fixture)
    sys.exit(1 if r.failed else 0)


if __name__ == "__main__":
    main()
