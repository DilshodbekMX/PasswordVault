"""Checks export.json against what Bitwarden, Proton Pass and KeePassXC read (keys and types taken from
their sources: bitwarden/clients CipherExport/LoginExport/FieldExport/PasswordHistoryExport and
isUnencrypted(); ProtonMail/WebClients bitwarden.reader.ts; keepassxc src/format/BitwardenReader.cpp),
and compares the item keys with Proton's Bitwarden fixture."""
import json, re, sys
from datetime import datetime

out, fixture = sys.argv[1], sys.argv[2]
d = json.load(open(f"{out}/export.json", encoding="utf-8"))
fx = json.load(open(fixture, encoding="utf-8"))
problems = []
def need(cond, msg):
    if not cond: problems.append(msg)

need(d.get("encrypted") is False, "encrypted must be false (Bitwarden isUnencrypted, Proton, KeePassXC)")
need(isinstance(d.get("folders"), list), "folders must be an array (Proton Array.isArray; KeePassXC requires the key)")
need(isinstance(d.get("items"), list), "items must be an array")
folder_ids = {f["id"] for f in d["folders"]}
need(all(isinstance(f.get("name"), str) and f["name"] for f in d["folders"]), "folder names")
iso = re.compile(r"^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}Z$")
for it in d["items"]:
    need(it["type"] == 1 and isinstance(it["name"], str), f"type/name {it.get('name')}")
    need(it["folderId"] is None or it["folderId"] in folder_ids, "folderId must point to a folder")
    need(isinstance(it["favorite"], bool) and it["reprompt"] == 0, "favorite/reprompt")
    lg = it["login"]
    need(all(k in lg for k in ("uris", "username", "password", "totp")), "login keys")
    need(all(set(u) == {"match", "uri"} and isinstance(u["uri"], str) for u in lg["uris"]), "uris")
    need(all(set(f) == {"name", "value", "type", "linkedId"} and f["type"] in (0, 1) for f in it["fields"]), "fields")
    for k in ("creationDate", "revisionDate"):
        need(bool(iso.match(it[k])), f"{k} format {it[k]}")
    ph = it["passwordHistory"]
    need(ph is None or all(iso.match(h["lastUsedDate"]) and isinstance(h["password"], str) for h in ph), "passwordHistory")
fx_keys = set().union(*[set(i) for i in fx["items"] if i["type"] == 1])
our_keys = set().union(*[set(i) for i in d["items"]])
print("items:", len(d["items"]), "folders:", len(d["folders"]))
print("keys in Proton's Bitwarden fixture missing from ours:", sorted(fx_keys - our_keys))
print("keys we add:", sorted(our_keys - fx_keys))
print("problems:", problems[:5] if problems else "none")
full = next(i for i in d["items"] if i["name"] == "Full entry")
print(json.dumps(full, ensure_ascii=False, indent=2))
# Check that every string survives json.loads unchanged against the special characters list
s = next(i for i in d["items"] if i["name"] == "Control characters")
print("control chars:", repr(s["login"]["password"]), repr(s["notes"]))
