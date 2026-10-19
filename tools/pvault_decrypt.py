#!/usr/bin/env python3
# -*- coding: utf-8 -*-
#
# pvault_decrypt.py: opens a PasswordVault backup on a computer, without the phone and without
# the app, and writes the accounts as the app's own CSV or JSON. For how to install and run it,
# in plain words: python3 pvault_decrypt.py --help. The file format it reads is docs/format.md.
#
# For programmers. This file follows the app's own code, which wins where they differ:
#   - the container, the header and reader rules R1 to R7: core/.../vaultfile/DefaultVaultCodec.kt
#     and HeaderCodec.kt (architecture.md §4.1, §4.2, §4.4);
#   - the master password's bytes: core/.../crypto/MasterPasswordBytes.kt (architecture.md §4.6,
#     lock.md §8.1); the KDF: Argon2id v1.3, 65,536 KiB, 3 passes, 4 lanes, 32 bytes;
#   - the body and rules R8 to R10: BodyCodec.kt and StrictJson.kt (architecture.md §4.3, §4.4);
#   - the CSV: GoogleCsv.kt, FieldsBlock.kt, Csv.kt, EntryRules.normalizeUrl, WebAddress.hostOf
#     (formats.md §4); the JSON: BitwardenJson.kt and IsoTime.kt (formats.md §5).
# Given the same vault, it writes the same bytes as the app's export: the CSV byte for byte, the
# JSON except "exportedAt" and "generator". `--check` tests that on this computer.
#
# It needs Python 3.7 or newer and the package cryptography, version 44.0.0 or newer (AES-GCM,
# and Argon2id, which came in 44.0.0 and needs OpenSSL 3.2 or newer, which its wheels include).
# Exit status: 0 done, 1 the backup did not open or the file was not written, 2 wrong use or
# nothing to work with (no such file, a file that exists, the package missing), 3 stopped.

import argparse
import base64
import binascii
import getpass
import hashlib
import hmac
import json
import os
import re
import struct
import sys
import time
import unicodedata

SCRIPT_VERSION = "1.0"
GENERATOR = "pvault_decrypt.py " + SCRIPT_VERSION
CRYPTOGRAPHY_MINIMUM = "44.0.0"

# ---------------------------------------------------------------------------------- plain words

HELP_TEXT = """\
Open a PasswordVault backup on a computer, without the phone and without the app.

This script opens a backup file that PasswordVault saved (its name starts with
PasswordVault-backup and ends in .pvault) with the master password you had when
you made the backup, and writes your accounts into a new file:
  passwords.csv    Google Password Manager, Chrome, Bitwarden, Proton Pass,
                   1Password and KeePassXC can import it.
  passwords.json   Bitwarden, Proton Pass and KeePassXC can import it, and the
                   PasswordVault app reads it back with everything, previous
                   passwords included.
Accounts in Recently deleted are not written, as in the app's own export.

WHAT TO INSTALL, ONCE
  1. Python 3, version 3.9 or newer: https://www.python.org/downloads/
     On Windows, tick "Add python.exe to PATH" on the installer's first screen.
  2. One package, "cryptography", version 44 or newer (older versions cannot
     do Argon2id, which every backup needs). Open a terminal and type:
       on Windows:          py -m pip install --upgrade cryptography
       on macOS or Linux:   python3 -m pip install --upgrade cryptography
     Then check that this computer has everything:
       py pvault_decrypt.py --check        (macOS, Linux: python3 instead of py)

HOW TO RUN IT
  Put this script and your backup file in one folder. Open a terminal in that
  folder (on Windows: click the address bar of File Explorer, type cmd and
  press Enter) and type, with the name of your own backup file:
       py pvault_decrypt.py PasswordVault-backup-2026-10-07.pvault passwords.csv
  (on macOS and Linux: python3 instead of py; use passwords.json for JSON).
  You can also double-click the script: it then asks for the backup file (drag
  it into the window) and for the name of the new file.
  Type your master password when the script asks for it. Nothing is shown
  while you type; that is normal. The script says what the backup holds and
  waits until you type yes before it writes anything.

THE NEW FILE IS NOT PROTECTED
  Anyone who opens it can read all your passwords. It has no password.
  Save it only where you control it: your own computer or USB stick.
  Don't send it by Telegram, email or other messengers.
  Delete it as soon as you're done.
  Don't open the CSV file in Excel: Excel changes phone numbers and codes and
  can run formulas.
  The script never writes over a file that already exists.
"""

HELP_EPILOG = """\
The backup's format is described in docs/format.md of the PasswordVault project.
Keep a copy of this script and of that page next to your backups.
"""

INSTALL_TEXT = """\
This script needs the package "cryptography", version 44 or newer.
{found}
Install it once. Open a terminal and type:
  on Windows:          py -m pip install --upgrade cryptography
  on macOS or Linux:   python3 -m pip install --upgrade cryptography
Then run the script again. If pip says the environment is "externally managed"
(some Linux systems), make a small environment of its own first:
  python3 -m venv pvenv
  pvenv/bin/python -m pip install --upgrade cryptography
  pvenv/bin/python pvault_decrypt.py --check
"""

WARNING_LINES = [
    "Anyone who opens this file can read all your passwords. It has no password.",
    "- Save it only where you control it: your own computer or USB stick.",
    "- Don't send it by Telegram, email or other messengers.",
    "- Delete it as soon as you're done.",
]
WARNING_CSV = "- Don't open this file in Excel: Excel changes phone numbers and codes and can run formulas."

MSG_NOT_OURS = "This is not a PasswordVault backup. Choose a backup file that the app saved: its name starts with PasswordVault-backup."
MSG_DAMAGED = "This backup file is damaged and cannot be opened. Try another backup file."
MSG_NEWER = ("This backup was made by a newer version of PasswordVault. This script reads files of version 1 only. "
             "Get the newest pvault_decrypt.py and try again.")
MSG_WRONG = "This password does not open this backup. Try the master password you used when you made it."
MSG_WRONG_OR_DAMAGED = "This file may be damaged, or the password is wrong."
MSG_NO_MEMORY = ("This computer did not give the script enough memory to open the backup (it needs about 70 MB). "
                 "Close other programs and try again.")

EXIT_DONE, EXIT_FAILED, EXIT_USAGE, EXIT_STOPPED = 0, 1, 2, 3


class Stop(Exception):
    """Ends the run with a message for the user and an exit status."""

    def __init__(self, message, status):
        Exception.__init__(self, message)
        self.message = message
        self.status = status


# ---------------------------------------------------------------------------- the cryptography package

class Crypto(object):
    """The three things this script takes from the package cryptography."""

    def __init__(self, version, argon2id, aesgcm, invalid_tag):
        self.version = version
        self.argon2id = argon2id
        self.aesgcm = aesgcm
        self.invalid_tag = invalid_tag

    def kek(self, password_bytes, salt):
        """Argon2id v1.3, m = 65,536 KiB, t = 3, p = 4, 32 bytes (architecture.md §4.6)."""
        kdf = self.argon2id(salt=bytes(salt), length=32, iterations=3, lanes=4, memory_cost=65536)
        return kdf.derive(bytes(password_bytes))

    def open(self, key, nonce, sealed, aad):
        """AES-256-GCM: the plaintext, or None when the tag does not verify."""
        try:
            return self.aesgcm(key).decrypt(nonce, sealed, aad)
        except self.invalid_tag:
            return None

    def seal(self, key, nonce, plain, aad):
        return self.aesgcm(key).encrypt(nonce, plain, aad)


def load_crypto():
    """The package, or Stop with plain words on how to install it."""
    try:
        import cryptography
        from cryptography.exceptions import InvalidTag
        from cryptography.hazmat.primitives.ciphers.aead import AESGCM
    except ImportError:
        raise Stop(INSTALL_TEXT.format(found="It is not installed for the Python that runs this script ("
                                       + sys.executable + ")."), EXIT_USAGE)
    version = getattr(cryptography, "__version__", "?")
    try:
        from cryptography.hazmat.primitives.kdf.argon2 import Argon2id
    except ImportError:
        raise Stop(INSTALL_TEXT.format(found="This computer has version " + version + ", which cannot do Argon2id."),
                   EXIT_USAGE)
    crypto = Crypto(version, Argon2id, AESGCM, InvalidTag)
    try:
        crypto.kek(b"probe", bytes(16))
    except MemoryError:
        raise Stop(MSG_NO_MEMORY, EXIT_FAILED)
    except Exception as e:  # UnsupportedAlgorithm: built against an OpenSSL older than 3.2
        raise Stop(INSTALL_TEXT.format(found="This computer has version " + version
                                       + ", but it cannot do Argon2id here (" + type(e).__name__ + ")."), EXIT_USAGE)
    return crypto


# ------------------------------------------------------- the master password's bytes (A §4.6, lock.md §8.1)

_WHITE_SPACE = frozenset([0x09, 0x0A, 0x0B, 0x0C, 0x0D, 0x20, 0x85, 0xA0, 0x1680] + list(range(0x2000, 0x200B))
                         + [0x2028, 0x2029, 0x202F, 0x205F, 0x3000])
_APOSTROPHES = frozenset([0x27, 0x60, 0xB4, 0x2018, 0x2019, 0x201B, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC,
                          0x02BD, 0x02BE, 0x02BF, 0xA78B, 0xA78C, 0xFF07])


def _combine_surrogates(text):
    """Joins the halves of a surrogate pair into one code point, as UTF-16 sees them; lone ones stay."""
    return text.encode("utf-16-le", "surrogatepass").decode("utf-16-le", "surrogatepass")


def master_password_bytes(password):
    """MasterPasswordBytes.of: NFC; 25 white-space code points become one space between words and none at
    the ends; 17 apostrophe-like code points become U+0027; UTF-8, with U+FFFD for an unpaired surrogate."""
    nfc = unicodedata.normalize("NFC", _combine_surrogates(password))
    out = []
    space_before = False
    for ch in nfc:
        c = ord(ch)
        if c in _WHITE_SPACE:
            space_before = len(out) > 0
            continue
        if space_before:
            out.append(" ")
        space_before = False
        if c in _APOSTROPHES:
            out.append("'")
        elif 0xD800 <= c <= 0xDFFF:
            out.append("\ufffd")
        else:
            out.append(ch)
    return "".join(out).encode("utf-8")


# ------------------------------------------------------------- the container (A §4.1, §4.2, §4.4 R1 to R7)

MAGIC = b"PVAULT01"
MAX_FILE = 16777216
MIN_FILE = 74
MAX_HEADER = 4096
MIN_BODY_LEN = 4112
BLOCK = 4096

_T0 = '{"format":"passwordvault","version":1,"cipher":"AES-256-GCM","vault_id":"'
_T1 = '","vk_id":"'
_T2 = '","generation":'
_T3 = ',"created":'
_T4 = ',"generator":"'
_T5 = '","writer_version":'
_T6 = ',"body_len":'
_T7 = (',"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,'
       '"salt":"')
_T8 = '","nonce":"'
_T9 = '","wrapped_key":"'
_T10 = '"}]}'
_UUID = "([0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12})"
_HEADER = re.compile(
    re.escape(_T0) + _UUID + re.escape(_T1) + _UUID + re.escape(_T2) + "([1-9][0-9]{0,9})"
    + re.escape(_T3) + "(0|[1-9][0-9]{0,15})" + re.escape(_T4) + "([A-Za-z0-9 ._()-]{1,64})"
    + re.escape(_T5) + "([1-9][0-9]{0,9})" + re.escape(_T6) + "(0|[1-9][0-9]{0,9})"
    + re.escape(_T7) + "([A-Za-z0-9+/=]{24})" + re.escape(_T8) + "([A-Za-z0-9+/=]{16})"
    + re.escape(_T9) + "([A-Za-z0-9+/=]{64})" + re.escape(_T10))
_PREFIX = b'{"format":"passwordvault","version":'


class Checked(object):
    """What check_file found. kind is "ok", "not-ours", "newer" or "damaged"; rule is the rule that failed."""

    def __init__(self, kind, rule=0, version=0):
        self.kind = kind
        self.rule = rule
        self.version = version
        self.trailer_ok = False
        self.vault_id = self.vk_id = self.generator = ""
        self.generation = self.created = self.writer_version = 0
        self.salt = self.slot_nonce = self.wrapped_key = b""
        self.aad = self.body_nonce = self.body_ct = b""


def _trailer_ok(data):
    """R4: the last 32 bytes are the SHA-256 of all the bytes before them."""
    if len(data) < 32:
        return False
    return hmac.compare_digest(hashlib.sha256(data[:-32]).digest(), data[-32:])


def _header_version(header):
    """R5: N of a header that starts with {"format":"passwordvault","version":N, (N is 1 to 9 digits)."""
    if len(header) < len(_PREFIX) + 2 or not header.startswith(_PREFIX):
        return None
    i = len(_PREFIX)
    if not 0x31 <= header[i] <= 0x39:
        return None
    n = 0
    while i < len(header) and 0x30 <= header[i] <= 0x39 and i - len(_PREFIX) < 9:
        n = n * 10 + header[i] - 0x30
        i += 1
    return n if i < len(header) and header[i] == 0x2C else None


def _base64(text, size):
    """Standard Base64 with padding that decodes to size bytes and encodes back to the same text."""
    try:
        raw = base64.b64decode(text.encode("ascii"), validate=True)
    except (binascii.Error, ValueError):
        return None
    if len(raw) != size or base64.b64encode(raw).decode("ascii") != text:
        return None
    return raw


def check_file(data):
    """Rules R1 to R7 of architecture.md §4.4, without a key: the first rule that fails decides."""
    if len(data) < 8 or data[:6] != b"PVAULT":
        return Checked("not-ours", 1)
    tens, units = data[6], data[7]
    if not (0x30 <= tens <= 0x39 and 0x30 <= units <= 0x39):
        return Checked("not-ours", 1)
    if data[6:8] != MAGIC[6:8]:
        if len(data) >= 40 and _trailer_ok(data):
            return Checked("newer", 1, (tens - 0x30) * 10 + units - 0x30)
        return Checked("damaged", 1)
    if len(data) < MIN_FILE or len(data) > MAX_FILE:
        return Checked("damaged", 2)
    header_len = struct.unpack(">I", data[8:12])[0]
    if header_len < 2 or header_len > MAX_HEADER or 56 + header_len + 16 > len(data):
        return Checked("damaged", 3)
    trailer_ok = _trailer_ok(data)
    header = data[12:12 + header_len]
    version = _header_version(header)
    if version is None:
        return Checked("damaged", 5)
    if version != 1:
        return Checked("newer", 6, version) if trailer_ok else Checked("damaged", 6)
    match = _HEADER.fullmatch(header.decode("latin-1"))  # one char per byte: no other byte matches
    if match is None:
        return Checked("damaged", 7)
    g = match.groups()
    writer_version = int(g[5])
    body_len = int(g[6])
    salt, slot_nonce, wrapped_key = _base64(g[7], 16), _base64(g[8], 12), _base64(g[9], 48)
    if (writer_version > 2147483647 or body_len != len(data) - 56 - header_len or body_len < MIN_BODY_LEN
            or (body_len - 16) % BLOCK != 0 or salt is None or slot_nonce is None or wrapped_key is None):
        return Checked("damaged", 7)
    ok = Checked("ok")
    ok.trailer_ok = trailer_ok
    ok.vault_id, ok.vk_id = g[0], g[1]
    ok.generation, ok.created, ok.generator, ok.writer_version = int(g[2]), int(g[3]), g[4], writer_version
    ok.salt, ok.slot_nonce, ok.wrapped_key = salt, slot_nonce, wrapped_key
    aad_end = 12 + header_len
    ok.aad = data[:aad_end]
    ok.body_nonce = data[aad_end:aad_end + 12]
    ok.body_ct = data[aad_end + 12:aad_end + 12 + body_len]
    return ok


# ---------------------------------------------------------------------- the body (A §4.3, §4.4 R9, R10)

class RawNumber(str):
    """A JSON number as it was spelled (1.50 stays 1.50), as the app keeps it."""


class Damaged(Exception):
    """R9 or R10 refused the body; the reason is a code, never a piece of the body."""


class Newer(Exception):
    """The body's schema is above 1."""


class Field(object):
    __slots__ = ("name", "value", "secret")

    def __init__(self, name, value, secret):
        self.name, self.value, self.secret = name, value, secret


class Entry(object):
    __slots__ = ("id", "title", "username", "password", "notes", "urls", "fields", "favourite", "change_pending",
                 "check_ignored", "created_at", "updated_at", "password_changed_at", "deleted_at",
                 "pending_old_password", "password_history", "extras")


class Vault(object):
    __slots__ = ("entries", "extras")


_MISSING = object()
_TOP_KEYS = ("schema", "entries", "draft", "plainExports", "backups", "masterPasswordMatches", "securitySeenAt")
_ENTRY_KEYS = ("id", "title", "username", "password", "notes", "urls", "fields", "favourite", "changePending",
               "checkIgnored", "createdAt", "updatedAt", "passwordChangedAt", "deletedAt", "pendingOldPassword",
               "passwordHistory")
_INTEGER = re.compile("-?(0|[1-9][0-9]{0,18})")


def _pairs(pairs):
    out = {}
    for key, value in pairs:
        if key in out:
            raise Damaged("duplicate-key")
        out[key] = value
    return out


def _constant(name):
    raise Damaged("unexpected-character")


_DECODER = json.JSONDecoder(object_pairs_hook=_pairs, parse_int=RawNumber, parse_float=RawNumber,
                            parse_constant=_constant, strict=True)


def _depth(root):
    """The nesting of root: the top container is 1, each container inside one more."""
    deepest = 0
    stack = [(root, 1)]
    while stack:
        value, depth = stack.pop()
        deepest = max(deepest, depth)
        children = value.values() if isinstance(value, dict) else value
        for child in children:
            if isinstance(child, (dict, list)):
                stack.append((child, depth + 1))
    return deepest


def _required(o, key):
    value = o.get(key, _MISSING)
    if value is _MISSING:
        raise Damaged("missing-key")
    return value


def _optional(o, key, read, default):
    value = o.get(key, _MISSING)
    return default if value is _MISSING else read(value)


def _obj(v):
    if not isinstance(v, dict):
        raise Damaged("wrong-type")
    return v


def _list(v, item):
    if not isinstance(v, list):
        raise Damaged("wrong-type")
    return [item(x) for x in v]


def _string(v):
    if isinstance(v, RawNumber) or not isinstance(v, str):
        raise Damaged("wrong-type")
    return v


def _string_or_null(v):
    return None if v is None else _string(v)


def _boolean(v):
    if v is True or v is False:
        return v
    raise Damaged("wrong-type")


def _integer_or_none(v):
    """An integer of §4.3: -?(0|[1-9][0-9]{0,18}) that fits a signed 64-bit value; else None."""
    if not isinstance(v, RawNumber) or not _INTEGER.fullmatch(v):
        return None
    n = int(v)
    return n if -2 ** 63 <= n < 2 ** 63 else None


def _integer(v):
    n = _integer_or_none(v)
    if n is None:
        raise Damaged("wrong-type")
    return n


def _integer_or_null(v):
    return None if v is None else _integer(v)


def _field(v):
    o = _obj(v)
    return Field(_string(_required(o, "name")), _string(_required(o, "value")), _boolean(_required(o, "secret")))


def _history(v):
    o = _obj(v)
    return (_string(_required(o, "password")), _integer(_required(o, "replacedAt")))


def _file_ref(v):
    o = _obj(v)
    _string(_required(o, "uri"))
    _string(_required(o, "name"))
    _integer(_required(o, "savedAt"))


def _entry(v):
    o = _obj(v)
    e = Entry()
    e.id = _string(_required(o, "id"))
    e.title = _string(_required(o, "title"))
    e.username = _optional(o, "username", _string, "")
    e.password = _optional(o, "password", _string, "")
    e.notes = _optional(o, "notes", _string, "")
    e.urls = _optional(o, "urls", lambda x: _list(x, _string), [])
    e.fields = _optional(o, "fields", lambda x: _list(x, _field), [])
    e.favourite = _optional(o, "favourite", _boolean, False)
    e.change_pending = _optional(o, "changePending", _boolean, False)
    e.check_ignored = _optional(o, "checkIgnored", _boolean, False)
    e.created_at = _integer(_required(o, "createdAt"))
    e.updated_at = _integer(_required(o, "updatedAt"))
    e.password_changed_at = _optional(o, "passwordChangedAt", _integer_or_null, None)
    e.deleted_at = _optional(o, "deletedAt", _integer_or_null, None)
    e.pending_old_password = _optional(o, "pendingOldPassword", _string_or_null, None)
    e.password_history = _optional(o, "passwordHistory", lambda x: _list(x, _history), [])
    e.extras = [(k, x) for k, x in o.items() if k not in _ENTRY_KEYS]
    return e


def _draft_entry(v):
    o = _obj(v)
    _string(_required(o, "title"))
    for key in ("username", "password", "notes"):
        _optional(o, key, _string, "")
    _optional(o, "urls", lambda x: _list(x, _string), [])
    _optional(o, "fields", lambda x: _list(x, _field), [])
    _optional(o, "favourite", _boolean, False)


def _draft(v):
    if v is None:
        return
    o = _obj(v)
    _string_or_null(_required(o, "entryId"))
    _integer(_required(o, "savedAt"))
    _draft_entry(_required(o, "entry"))


def read_body(plaintext):
    """R9 and R10 over the decrypted body, padding included: the vault, or Damaged or Newer."""
    text = bytes(plaintext).rstrip(b" ")
    try:
        decoded = text.decode("utf-8")  # strict: no malformed sequence, no encoded surrogate
    except UnicodeDecodeError:
        raise Damaged("malformed-utf8")
    if decoded.startswith("\ufeff"):
        raise Damaged("byte-order-mark")
    try:
        root = _DECODER.decode(decoded)
    except Damaged:
        raise
    except (ValueError, RecursionError):
        raise Damaged("not-json")
    if not isinstance(root, dict):
        raise Damaged("not-an-object")
    if _depth(root) > 16:
        raise Damaged("too-deep")
    schema = _required(root, "schema")
    n = _integer_or_none(schema)
    if n is None:
        raise Damaged("wrong-type")
    if n > 1:
        raise Newer(n)
    if n != 1:
        raise Damaged("bad-schema")
    vault = Vault()
    vault.entries = _list(_required(root, "entries"), _entry)
    _optional(root, "draft", _draft, None)
    _optional(root, "plainExports", lambda x: _list(x, _file_ref), [])
    _optional(root, "backups", lambda x: _list(x, _file_ref), [])
    _optional(root, "masterPasswordMatches", lambda x: _list(x, _string), [])
    _optional(root, "securitySeenAt", _integer_or_null, None)
    vault.extras = [(k, x) for k, x in root.items() if k not in _TOP_KEYS]
    return vault


def unwrap(crypto, checked, password_bytes):
    """KEK from the password and the slot's salt; VK from the slot. None: this password does not open it."""
    kek = crypto.kek(password_bytes, checked.salt)
    vk = crypto.open(kek, checked.slot_nonce, checked.wrapped_key, None)
    return vk if vk is not None and len(vk) == 32 else None


def open_body(crypto, checked, vk):
    """R8 to R10: the vault, or Damaged or Newer."""
    plaintext = crypto.open(vk, checked.body_nonce, checked.body_ct, checked.aad)
    if plaintext is None:
        raise Damaged("tag")
    return read_body(plaintext)


# --------------------------------------------------- text rules of the writers (formats.md §2 rule 8)

def replace_unpaired_surrogates(text):
    """TextHygiene.replaceUnpairedSurrogates: each unpaired surrogate becomes U+FFFD."""
    text = _combine_surrogates(text)
    if not any(0xD800 <= ord(c) <= 0xDFFF for c in text):
        return text
    return "".join("\ufffd" if 0xD800 <= ord(c) <= 0xDFFF else c for c in text)


def normalize_line_breaks(text):
    return text.replace("\r\n", "\n").replace("\r", "\n") if "\r" in text else text


def _kotlin_whitespace(ch):
    """Kotlin's Char.isWhitespace on the JVM: Character.isWhitespace or Character.isSpaceChar."""
    o = ord(ch)
    return 0x09 <= o <= 0x0D or 0x1C <= o <= 0x1F or unicodedata.category(ch) in ("Zs", "Zl", "Zp")


def _kotlin_trim(text):
    start, end = 0, len(text)
    while start < end and _kotlin_whitespace(text[start]):
        start += 1
    while end > start and _kotlin_whitespace(text[end - 1]):
        end -= 1
    return text[start:end]


def _kotlin_blank(text):
    return all(_kotlin_whitespace(c) for c in text)


def _unicode_white_space(ch):
    """Unicode's White_Space, as EntryRules.isWhiteSpace has it."""
    o = ord(ch)
    return 0x09 <= o <= 0x0D or o == 0x85 or unicodedata.category(ch) in ("Zs", "Zl", "Zp")


def _simple_case(ch, upper):
    if ch == "\u0130" and not upper:
        return "i"  # its one-character mapping; Python's lower() gives two
    mapped = ch.upper() if upper else ch.lower()
    return mapped if len(mapped) == 1 else ch


def _char_equals_ignore_case(a, b):
    """Kotlin's Char.equals(other, ignoreCase = true)."""
    if a == b:
        return True
    ua, ub = _simple_case(a, True), _simple_case(b, True)
    return ua == ub or _simple_case(ua, False) == _simple_case(ub, False)


def _starts_with_ignore_case(text, prefix):
    if len(text) < len(prefix):
        return False
    return all(_char_equals_ignore_case(text[i], prefix[i]) for i in range(len(prefix)))


# ------------------------------------------------------- websites (EntryRules, WebAddress; search.md §2)

_SCHEME = re.compile("[A-Za-z][A-Za-z0-9+.-]*://")


def normalize_url(raw):
    """EntryRules.normalizeUrl: trimmed; https:// in front of a text without a scheme that looks like a host."""
    url = _kotlin_trim(raw)
    if not url or _SCHEME.match(url):
        return url
    return "https://" + url if _looks_like_host(url) else url


def _looks_like_host(url):
    if any(_unicode_white_space(c) for c in url):
        return False
    dot = url.find(".", 1)
    if not 1 <= dot <= len(url) - 2:
        return False
    return "/" not in url[:dot] and ":" not in url[:dot]


def _is_scheme(text, end):
    if not ("a" <= text[0] <= "z" or "A" <= text[0] <= "Z"):
        return False
    return all("a" <= c <= "z" or "A" <= c <= "Z" or "0" <= c <= "9" or c in "+-." for c in text[1:end])


def _idn_to_unicode(host):
    """java.net.IDN.toUnicode, label by label; a label that does not convert stays as it is."""
    import encodings.idna
    labels = re.split("[.\u3002\uff0e\uff61]", host)
    out = []
    for label in labels:
        if label[:4].lower() == "xn--" and all(ord(c) < 0x80 for c in label):
            try:
                decoded = label[4:].encode("ascii").decode("punycode")
                if encodings.idna.ToASCII(decoded).decode("ascii").lower() == label.lower():
                    out.append(decoded)
                    continue
            except Exception:
                pass
        out.append(label)
    return ".".join(out)


def host_of(address):
    """WebAddress.hostOf: the host of a website, lower case, without www., port or path."""
    s = _kotlin_trim(address)
    if not s:
        return s
    scheme_end = s.find("://")
    if scheme_end > 0 and _is_scheme(s, scheme_end):
        scheme = s[:scheme_end]
        s = s[scheme_end + 3:]
        if scheme.lower() == "android":  # the scheme is ASCII here
            at = s.rfind("@")
            if at >= 0:
                s = s[at + 1:]
    elif s.startswith("//"):
        s = s[2:]
    for i, c in enumerate(s):
        if c in "/?#\\":
            s = s[:i]
            break
    at = s.rfind("@")
    if at >= 0:
        s = s[at + 1:]
    if s.startswith("["):
        close = s.find("]")
        if close > 0:
            s = s[1:close]
    else:
        colon = s.find(":")
        if colon >= 0 and s.rfind(":") == colon:
            s = s[:colon]
    s = s.rstrip(".").lower()
    if s.startswith("www.") and len(s) > 4:
        s = s[4:]
    if "xn--" in s:
        s = _idn_to_unicode(s)
    return s


# ------------------------------------------------------------- the CSV (formats.md §4; GoogleCsv.kt)

CSV_HEADER = ["name", "url", "username", "password", "note"]
CSV_MAX_ROWS = 3000
CSV_MAX_BYTES = 1000 * 1024
_MARKER_START = "--- PasswordVault ("
_MARKER_END = " lines) ---"
_HIDDEN = " (hidden)"
_TITLE_LABEL = "Title"
_OLD_PASSWORD_LABEL = "Old password"


def _append_line(out, label, hidden, text):
    """`LF label[ (hidden)]: text`, later lines of text indented by two spaces; returns the lines written."""
    out.append("\n" + label + (_HIDDEN if hidden else "") + ": ")
    lines = 1
    for c in normalize_line_breaks(text):
        out.append(c)
        if c == "\n":
            out.append("  ")
            lines += 1
    return lines


def _label(name):
    plain = _kotlin_trim(normalize_line_breaks(name).replace("\n", " "))
    out = "\\" if plain in (_TITLE_LABEL, _OLD_PASSWORD_LABEL) else ""
    return out + "".join("\\" + c if c in "\\:(" else c for c in plain)


def _marker_count(text, start, end):
    digits_start = start + len(_MARKER_START)
    digits_end = end - len(_MARKER_END)
    digits = digits_end - digits_start
    if digits < 1 or digits > 9:
        return 0
    if not text.startswith(_MARKER_START, start) or not text.startswith(_MARKER_END, digits_end):
        return 0
    if text[digits_start] == "0":
        return 0
    count = 0
    for c in text[digits_start:digits_end]:
        if not "0" <= c <= "9":
            return 0
        count = count * 10 + ord(c) - 0x30
    return count


def _parse_line(text, start, end):
    """`label[ (hidden)]: text` in text[start:end], as (name, reserved, hidden, value); None when it is not one."""
    name = []
    escaped = False
    plain_tail = 0
    i = start
    while True:
        if i >= end:
            return None
        c = text[i]
        if c == "\\":
            if i + 1 >= end:
                return None
            name.append(text[i + 1])
            escaped = True
            plain_tail = 0
            i += 2
        elif c == ":" and i + 1 < end and text[i + 1] == " ":
            break
        else:
            name.append(c)
            plain_tail += 1
            i += 1
    label = "".join(name)
    hidden = plain_tail >= len(_HIDDEN) and label.endswith(_HIDDEN)
    if hidden:
        label = label[:-len(_HIDDEN)]
    reserved = label if not escaped and label in (_TITLE_LABEL, _OLD_PASSWORD_LABEL) else None
    return [label, reserved, hidden, text[i + 2:end]]


def fields_block_found(note):
    """FieldsBlock.read(note).found: whether the note ends with a block of ours."""
    text = normalize_line_breaks(note)
    total_lines = 1 + text.count("\n")
    marker_start, block_lines = -1, 0
    line_number, line_start, previous_empty = 0, 0, False
    while True:
        line_end = text.find("\n", line_start)
        if line_end < 0:
            line_end = len(text)
        if line_number >= 2 and previous_empty:
            count = _marker_count(text, line_start, line_end)
            if count > 0 and count == total_lines - line_number - 1:
                marker_start, block_lines = line_start, count
        previous_empty = line_end == line_start
        if line_end == len(text):
            break
        line_start = line_end + 1
        line_number += 1
    if marker_start < 0:
        return False
    current = None
    at = text.find("\n", marker_start) + 1
    for _ in range(block_lines):
        end = text.find("\n", at)
        if end < 0:
            end = len(text)
        if text.startswith("  ", at):
            if current is None:
                return False
        else:
            current = _parse_line(text, at, end)
            if current is None:
                return False
        at = end + 1
    return True


def fields_block_note(notes, title, host, fields, old_password):
    """FieldsBlock.note: the notes and, when there is something to carry, the block of formats.md §4.4."""
    with_title = host is not None and title != host
    if not with_title and not fields and old_password is None:
        if not fields_block_found(notes):
            return notes  # nothing to carry
        with_title = True  # notes that would read as a block get one of ours behind them
    lines = []
    count = 0
    if with_title:
        count += _append_line(lines, _TITLE_LABEL, False, title)
    for field in fields:
        count += _append_line(lines, _label(field.name), field.secret, field.value)
    if old_password is not None:
        count += _append_line(lines, _OLD_PASSWORD_LABEL, True, old_password)
    return notes + "\n\n" + _MARKER_START + str(count) + _MARKER_END + "".join(lines)


def entry_note(entry):
    first = None
    for url in entry.urls:
        normalized = normalize_url(url)
        if normalized:
            first = normalized
            break
    return fields_block_note(entry.notes, entry.title, host_of(first) if first is not None else None,
                             entry.fields, entry.pending_old_password if entry.change_pending else None)


def _csv_value(value):
    value = replace_unpaired_surrogates(value)
    if not value:
        return value
    quote = value[0] in " \t" or value[-1] in " \t" or any(c in ',"\r\n' for c in value)
    return '"' + value.replace('"', '""') + '"' if quote else value


def _csv_row(values):
    return ",".join(_csv_value(v) for v in values) + "\r\n"


def csv_rows(entry):
    """GoogleCsv.rows: one row per website (one with an empty url without any): name, url, username, password, note."""
    note = replace_unpaired_surrogates(entry_note(entry))
    name = replace_unpaired_surrogates(entry.title)
    username = replace_unpaired_surrogates(entry.username)
    password = replace_unpaired_surrogates(entry.password)
    urls = [u for u in (normalize_url(u) for u in entry.urls) if u] or [""]
    return [[name, replace_unpaired_surrogates(url), username, password, note] for url in urls]


def csv_export(entries):
    """GoogleCsv.export: the files' bytes, cut into parts of at most 3,000 rows and 1,000 KB that Google
    accepts; one account's rows are never in two parts."""
    header = _csv_row(CSV_HEADER)
    files = []
    text, rows_in_part, bytes_in_part = [header], 0, len(header)
    for entry in entries:
        if entry.deleted_at is not None:
            continue
        rows = csv_rows(entry)
        chunk = "".join(_csv_row(r) for r in rows)
        chunk_bytes = len(chunk.encode("utf-8"))
        if rows_in_part > 0 and (rows_in_part + len(rows) > CSV_MAX_ROWS or bytes_in_part + chunk_bytes > CSV_MAX_BYTES):
            files.append("".join(text).encode("utf-8"))
            text, rows_in_part, bytes_in_part = [header], 0, len(header)
        text.append(chunk)
        rows_in_part += len(rows)
        bytes_in_part += chunk_bytes
    if rows_in_part > 0 or not files:
        files.append("".join(text).encode("utf-8"))
    return files


# ---------------------------------------------------------- the JSON (formats.md §5; BitwardenJson.kt)

_ROOT_EXTENSION_KEYS = ("format", "version", "exportedAt", "generator")


def civil_from_days(days):
    """IsoTime.civilFromDays: year, month, day of a count of days since 1970-01-01 (proleptic Gregorian)."""
    z = days + 719468
    era = z // 146097
    doe = z - era * 146097
    yoe = (doe - doe // 1460 + doe // 36524 - doe // 146096) // 365
    doy = doe - (365 * yoe + yoe // 4 - yoe // 100)
    mp = (5 * doy + 2) // 153
    day = doy - (153 * mp + 2) // 5 + 1
    month = mp + 3 if mp < 10 else mp - 9
    return yoe + era * 400 + (1 if month <= 2 else 0), month, day


def iso_time(ms):
    """IsoTime.format: 2026-10-06T07:05:09.123Z, UTC with milliseconds."""
    days, in_day = divmod(ms, 86400000)
    year, month, day = civil_from_days(days)
    y = "%04d" % year if 0 <= year <= 9999 else ("-" if year < 0 else "+") + "%06d" % abs(year)
    return "%s-%02d-%02dT%02d:%02d:%02d.%03dZ" % (y, month, day, in_day // 3600000, in_day // 60000 % 60,
                                                  in_day // 1000 % 60, in_day % 1000)


def _quoted(text):
    out = ['"']
    for c in text:
        if c == '"':
            out.append('\\"')
        elif c == "\\":
            out.append("\\\\")
        elif c >= " ":
            out.append(c)
        elif c == "\b":
            out.append("\\b")
        elif c == "\t":
            out.append("\\t")
        elif c == "\n":
            out.append("\\n")
        elif c == "\f":
            out.append("\\f")
        elif c == "\r":
            out.append("\\r")
        else:
            out.append("\\u%04x" % ord(c))
    out.append('"')
    return "".join(out)


class _Pretty(object):
    """JSON with a two-space indent, as BitwardenJson's writer writes it."""

    def __init__(self):
        self.out = []
        self.members = [0]

    def _newline(self):
        self.out.append("\n" + "  " * (len(self.members) - 1))

    def begin(self, c):
        self.out.append(c)
        self.members.append(0)
        return self

    def end(self, c):
        had = self.members.pop() > 0
        if had:
            self._newline()
        self.out.append(c)
        return self

    def item(self):
        if self.members[-1] > 0:
            self.out.append(",")
        self.members[-1] += 1
        self._newline()
        return self

    def key(self, name):
        self.item()
        self.string(name)
        self.out.append(": ")
        return self

    def literal(self, text):
        self.out.append(text)
        return self

    def string(self, text):
        self.out.append(_quoted(replace_unpaired_surrogates(text)))
        return self

    def string_or_null(self, text):
        return self.literal("null") if text is None else self.string(text)

    def boolean(self, value):
        return self.literal("true" if value else "false")

    def element(self, value):
        if isinstance(value, dict):
            self.begin("{")
            for k, v in value.items():
                self.key(k).element(v)
            self.end("}")
        elif isinstance(value, list):
            self.begin("[")
            for v in value:
                self.item().element(v)
            self.end("]")
        elif value is None:
            self.literal("null")
        elif value is True or value is False:
            self.boolean(value)
        elif isinstance(value, RawNumber):
            self.literal(str(value))
        else:
            self.string(value)
        return self


def _write_extras(w, extras, taken):
    """The kept unknown keys after our own; a key that would stand twice in the object is left out."""
    written = set()
    for key, value in extras:
        name = replace_unpaired_surrogates(key)
        if name in taken or name in written:
            continue
        written.add(name)
        w.key(key).element(value)


def to_bitwarden_uri(url):
    """Chrome's android://<hash>@<package>/ as Bitwarden's androidapp://<package>; anything else as it is."""
    if not _starts_with_ignore_case(url, "android://"):
        return url
    at = url.rfind("@")
    return url if at < 0 else "androidapp://" + url[at + 1:].rstrip("/")


def _write_item(w, e):
    uris = [to_bitwarden_uri(u) for u in e.urls if not _kotlin_blank(u)]
    w.begin("{")
    w.key("id").string(e.id)
    w.key("organizationId").literal("null")
    w.key("folderId").literal("null")
    w.key("type").literal("1")
    w.key("reprompt").literal("0")
    w.key("name").string(e.title)
    w.key("notes").string_or_null(e.notes or None)
    w.key("favorite").boolean(e.favourite)
    w.key("fields").begin("[")
    for field in e.fields:
        w.item().begin("{")
        w.key("name").string(field.name)
        w.key("value").string(field.value)
        w.key("type").literal("1" if field.secret else "0")
        w.key("linkedId").literal("null")
        w.end("}")
    w.end("]")
    w.key("login").begin("{")
    w.key("fido2Credentials").begin("[").end("]")
    w.key("uris").begin("[")
    for uri in uris:
        w.item().begin("{")
        w.key("match").literal("null")
        w.key("uri").string(uri)
        w.end("}")
    w.end("]")
    w.key("username").string_or_null(e.username or None)
    w.key("password").string_or_null(e.password or None)
    w.key("totp").literal("null")
    w.end("}")
    w.key("passwordHistory")
    if not e.password_history:
        w.literal("null")
    else:
        w.begin("[")
        for password, replaced_at in e.password_history:
            w.item().begin("{")
            w.key("lastUsedDate").string(iso_time(replaced_at))
            w.key("password").string(password)
            w.end("}")
        w.end("]")
    w.key("creationDate").string(iso_time(e.created_at))
    w.key("revisionDate").string(iso_time(e.updated_at))
    w.key("deletedDate").literal("null")
    w.key("collectionIds").literal("null")
    w.key("passwordVault").begin("{")
    w.key("passwordChangedAt").string_or_null(None if e.password_changed_at is None else iso_time(e.password_changed_at))
    w.key("changePending").boolean(e.change_pending)
    w.key("checkIgnored").boolean(e.check_ignored)
    w.key("pendingOldPassword").string_or_null(e.pending_old_password)
    if uris != e.urls:
        w.key("urls").begin("[")
        for url in e.urls:
            w.item().string(url)
        w.end("]")
    _write_extras(w, e.extras, _ENTRY_KEYS)
    w.end("}")
    w.end("}")


def json_export(vault, exported_at_ms, generator):
    """BitwardenJson.export: Bitwarden's unencrypted layout with our passwordVault objects."""
    w = _Pretty()
    w.begin("{")
    w.key("encrypted").literal("false")
    w.key("passwordVault").begin("{")
    w.key("format").string("passwordvault-export")
    w.key("version").literal("1")
    w.key("exportedAt").string(iso_time(exported_at_ms))
    w.key("generator").string(generator)
    _write_extras(w, vault.extras, _ROOT_EXTENSION_KEYS)
    w.end("}")
    w.key("folders").begin("[").end("]")
    w.key("items").begin("[")
    for entry in vault.entries:
        if entry.deleted_at is None:
            w.item()
            _write_item(w, entry)
    w.end("]")
    w.end("}")
    w.out.append("\n")
    return "".join(w.out).encode("utf-8")


# ----------------------------------------------------------------------------------- the self-check

VECTOR_PASSWORD = "correct horse battery staple"
VECTOR_KEK = "853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e"
VECTOR_A_SHA256 = "3210ed2c15bddde2e2d3793aeff65deda2d745d1ac10ed6c6443be1711ee1d2d"
VECTOR_B_SHA256 = "1bb8d230c949b197859bbd9fec613bd65f149595b3f9db98b40dd6dc2e5312c7"
VECTOR_B_BODY_SHA256 = "610a407ad04359ae1fc8787f29f4ed943d46358eae7bdddc6dd7ad2490186cb7"
# Vector B's body (architecture.md §4.13.2), one line of UTF-8.
VECTOR_B_BODY = r'''{"schema":1,"entries":[{"id":"00000000-0000-4000-8000-000000000011","title":"Gmail – Oʻzbekiston Ўзбекистон","username":"aziz@example.com","password":"p\"ss\\w/rd-🔑","notes":"Birinchi qator\nИккинчи қатор\ttab\u0001","urls":["https://mail.google.com","https://accounts.google.com/a?b=c"],"fields":[{"name":"PIN code","value":"0042","secret":true},{"name":"Телефон","value":"+998 90 123-45-67","secret":false}],"favourite":true,"changePending":true,"checkIgnored":false,"createdAt":1791244800000,"updatedAt":1791331200000,"passwordChangedAt":1791331200000,"deletedAt":null,"pendingOldPassword":"eski-parol","passwordHistory":[{"password":"old2","replacedAt":1791331200000},{"password":"old1","replacedAt":1791288000000}],"futureEntryKey":"kept"}],"draft":{"entryId":null,"savedAt":1791331260000,"entry":{"title":"Yangi","username":"","password":"gen-🔑","notes":"","urls":[],"fields":[],"favourite":false}},"plainExports":[{"uri":"content://com.android.providers.downloads.documents/document/17","name":"passwords-2026-10-07.csv","savedAt":1791331300000}],"backups":[{"uri":"content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-07.pvault","name":"PasswordVault-backup-2026-10-07.pvault","savedAt":1791331400000}],"masterPasswordMatches":["00000000-0000-4000-8000-000000000011"],"securitySeenAt":1791331500000,"futureTopKey":{"n":1.50,"list":[true,null,"x"]}}'''


def build_vector(crypto, body, generation, created, body_nonce):
    """architecture.md's appendix: a vault file with VK a0..bf, salt 00..0f and slot nonce 20..2b."""
    kek = bytes.fromhex(VECTOR_KEK)
    vk, salt, slot_nonce = bytes(range(0xA0, 0xC0)), bytes(range(0x00, 0x10)), bytes(range(0x20, 0x2C))
    wrapped = crypto.seal(kek, slot_nonce, vk, None)
    plain = body + b" " * (-len(body) % 4096)
    b64 = lambda raw: base64.b64encode(raw).decode("ascii")
    header = (_T0 + "00000000-0000-4000-8000-000000000001" + _T1 + "00000000-0000-4000-8000-000000000002"
              + _T2 + str(generation) + _T3 + str(created) + _T4 + "PasswordVault 1.0" + _T5 + "1"
              + _T6 + str(len(plain) + 16) + _T7 + b64(salt) + _T8 + b64(slot_nonce) + _T9 + b64(wrapped) + _T10)
    aad = MAGIC + struct.pack(">I", len(header)) + header.encode("ascii")
    pre = aad + body_nonce + crypto.seal(vk, body_nonce, plain, aad)
    return pre + hashlib.sha256(pre).digest()


class _E(object):
    """A small entry for the vectors of formats.md §4.4."""

    def __init__(self, title, urls, notes="", fields=(), pending=None):
        self.title, self.urls, self.notes = title, urls, notes
        self.fields = [Field(n, v, s) for n, v, s in fields]
        self.change_pending = pending is not None
        self.pending_old_password = pending


# formats.md §4.4, "Vectors": the note column the CSV writer produces for a stored entry.
FIELDS_BLOCK_VECTORS = [
    (_E("Bank", ["https://bank.uz"], "Branch: Chilonzor", [("Q: mother's name", "Aziza", False)]),
     "Branch: Chilonzor\n\n--- PasswordVault (2 lines) ---\nTitle: Bank\nQ\\: mother's name: Aziza"),
    (_E("Bank", ["https://bank.uz"], "", [("Code (hidden)", "77", False), ("PIN", "1234", True)]),
     "\n\n--- PasswordVault (3 lines) ---\nTitle: Bank\nCode \\(hidden): 77\nPIN (hidden): 1234"),
    (_E("bank.uz", ["https://bank.uz"], "", [("  B", "x", False)]),
     "\n\n--- PasswordVault (1 lines) ---\nB: x"),
    (_E("mail.ru", ["https://mail.ru"], "Old notes:\n\n--- PasswordVault ---\nPIN: 9999"),
     "Old notes:\n\n--- PasswordVault ---\nPIN: 9999"),
    (_E("mail.ru", ["https://mail.ru"], "--- PasswordVault (1 lines) ---\nPIN: 9999\nmore text"),
     "--- PasswordVault (1 lines) ---\nPIN: 9999\nmore text"),
    (_E("Telegram", ["https://web.telegram.org"], "", [("Title", "Mr", False), ("Old password", "x", False)]),
     "\n\n--- PasswordVault (3 lines) ---\nTitle: Telegram\n\\Title: Mr\n\\Old password: x"),
    (_E("Gmail", ["https://mail.google.com"], "", [("PIN", "1234", True)], pending="Old#2"),
     "\n\n--- PasswordVault (3 lines) ---\nTitle: Gmail\nPIN (hidden): 1234\nOld password (hidden): Old#2"),
    (_E("Server", ["https://srv.example"], "", [("C:\\keys", "line 1\nline 2", False)]),
     "\n\n--- PasswordVault (3 lines) ---\nTitle: Server\nC\\:\\\\keys: line 1\n  line 2"),
    (_E("mail.ru", ["https://mail.ru"], "Old notes:\n\n--- PasswordVault (1 lines) ---\nPIN: 9999"),
     "Old notes:\n\n--- PasswordVault (1 lines) ---\nPIN: 9999\n\n--- PasswordVault (1 lines) ---\nTitle: mail.ru"),
]

# MasterPasswordBytes: architecture.md §4.13 and lock.md §4.3.
PASSWORD_BYTES_VECTORS = [
    (" correct  horse\u00a0battery staple ", "636f727265637420686f727365206261747465727920737461706c65"),
    ("o\u0374g\u1fefli\u1ffdm", "6f2767276c69276d"),
    ("\u0020\u00a0\u0009\u3000\u0020", ""),
    ("o\u02bbg\u02bblim-To\u02bbra", "6f2767276c696d2d546f277261"),
    ("o\u2018g\u2018lim-To\u2018ra", "6f2767276c696d2d546f277261"),
    ("o'g'lim-To'ra", "6f2767276c696d2d546f277261"),
    ("o\u2019g\u02bclim-To\u0060ra", "6f2767276c696d2d546f277261"),
    ("o\u00b4g\uff07lim-To\u2032ra", "6f2767276c696d2d546f277261"),
    ("o\u0374g\u1feflim-To\u1ffdra", "6f2767276c696d2d546f277261"),
    ("Ko\u0308z-\u0423\u0306zbek", "4bc3b67a2dd08e7a62656b"),
]

# The app's own writers (GoogleCsv.export; BitwardenJson.export with "exportedAt" 1791331200000 and the
# generator below) over the vaults of vectors A and B give these files, as their SHA-256: computed with the
# classes of :core at wave-5-merged on the JVM.
VECTOR_EXPORTED_AT = 1791331200000
VECTOR_GENERATOR = "pvault_decrypt.py 1.0"
VECTOR_EXPORTS = {
    "A": ("029c9442ca214587f5da8fa3c788d5f69fd6582e5b65a00ad0e019a7addaa795",
          "2c55903829b07d9b0cd0ae2b3e1425ace3b881af71c6a9638db9fef8a94a3f57"),
    "B": ("2fdd971031f829451c74f4ec5727eb1b2ed77c2a7eb54566c61322c61c96a13c",
          "da2bf44e4e1db467b2174eb8d44d05b55d6d872be572e927996ddd4079b020b1"),
}


def self_check():
    """--check: what this computer needs, then the vectors through the same code that opens a backup."""
    results = []

    def check(name, test):
        try:
            ok = bool(test())
        except Exception as e:  # a check that throws has failed
            ok = False
            name += " (" + type(e).__name__ + ")"
        results.append(ok)
        say(("  OK      " if ok else "  FAILED  ") + name)
        return ok

    say("Checking what this computer needs to open PasswordVault backups.")
    say("  Python " + sys.version.split()[0] + " at " + sys.executable)
    crypto = load_crypto()
    say("  cryptography " + crypto.version)
    check("the master password's bytes (architecture.md §4.13, lock.md §4.3)",
          lambda: all(master_password_bytes(t).hex() == h for t, h in PASSWORD_BYTES_VECTORS))
    check("Argon2id with the backup's parameters gives the known key",
          lambda: crypto.kek(master_password_bytes(VECTOR_PASSWORD), bytes(range(16))).hex() == VECTOR_KEK)
    body_b = VECTOR_B_BODY.encode("utf-8")
    check("vector B's body is intact in this script", lambda: hashlib.sha256(body_b).hexdigest() == VECTOR_B_BODY_SHA256)
    file_a = build_vector(crypto, b'{"schema":1,"entries":[]}', 1, 1791244800000, bytes(range(0x30, 0x3C)))
    file_b = build_vector(crypto, body_b, 7, 1791331200000, bytes(range(0x40, 0x4C)))
    check("AES-256-GCM rebuilds vector A byte for byte", lambda: hashlib.sha256(file_a).hexdigest() == VECTOR_A_SHA256)
    check("AES-256-GCM rebuilds vector B byte for byte", lambda: hashlib.sha256(file_b).hexdigest() == VECTOR_B_SHA256)
    vaults = {}

    def opens(name, data):
        checked = check_file(data)
        vk = unwrap(crypto, checked, master_password_bytes(VECTOR_PASSWORD)) if checked.kind == "ok" else None
        vaults[name] = open_body(crypto, checked, vk) if vk is not None else None
        return vaults[name] is not None

    check("vector A opens with its password", lambda: opens("A", file_a) and vaults["A"].entries == [])
    check("vector B opens with its password", lambda: opens("B", file_b) and len(vaults["B"].entries) == 1
          and vaults["B"].entries[0].title == "Gmail \u2013 O\u02bbzbekiston \u040e\u0437\u0431\u0435\u043a\u0438\u0441\u0442\u043e\u043d")
    check("a wrong password does not open vector A",
          lambda: unwrap(crypto, check_file(file_a), master_password_bytes("correct horse battery stapler")) is None)
    check("a changed byte makes vector A damaged",
          lambda: check_file(file_a[:100] + b"x" + file_a[101:]).kind == "damaged")
    check("the CSV's block of extra fields (formats.md §4.4, vectors 1 to 8)",
          lambda: all(entry_note(e) == note for e, note in FIELDS_BLOCK_VECTORS))
    for name in ("A", "B"):
        vault = vaults.get(name)
        csv_sha, json_sha = VECTOR_EXPORTS[name]
        check("vector " + name + " as CSV and as JSON: the same bytes as the app's own export",
              lambda: vault is not None
              and [hashlib.sha256(p).hexdigest() for p in csv_export(vault.entries)] == [csv_sha]
              and hashlib.sha256(json_export(vault, VECTOR_EXPORTED_AT, VECTOR_GENERATOR)).hexdigest() == json_sha)
    if all(results):
        say("Everything is ready: this computer can open PasswordVault backups.")
        return EXIT_DONE
    say("Some checks failed: this computer cannot be trusted to open backups with this script.")
    return EXIT_FAILED


# ------------------------------------------------------------------------------------- the command

def say(text=""):
    sys.stdout.write(text + "\n")
    sys.stdout.flush()


def _interactive():
    try:
        return sys.stdin.isatty() and sys.stdout.isatty()
    except (AttributeError, ValueError):
        return False


def _clean_path(text):
    """A path typed or dragged into the window: without the quotes or the escapes a terminal puts around it."""
    text = text.strip()
    if len(text) >= 2 and text[0] == text[-1] and text[0] in "\"'":
        text = text[1:-1]
    elif os.name != "nt" and "\\" in text:
        text = re.sub(r"\\(.)", r"\1", text)
    return text


def _read_backup(path):
    if not os.path.exists(path):
        raise Stop("There is no file " + path + ". Check the name, or drag the file into this window.", EXIT_USAGE)
    if os.path.isdir(path):
        raise Stop(path + " is a folder. Give the backup file inside it.", EXIT_USAGE)
    try:
        with open(path, "rb") as f:
            return f.read(MAX_FILE + 1)  # never more than the cap and one byte (R2)
    except OSError as e:
        raise Stop("This file can't be read: " + path + " (" + (e.strerror or type(e).__name__) + ").", EXIT_FAILED)


def _output_kind(path):
    lower = path.lower()
    if lower.endswith(".csv"):
        return "csv"
    if lower.endswith(".json"):
        return "json"
    raise Stop("The new file's name must end in .csv or .json, for example passwords.csv.", EXIT_USAGE)


def _part_paths(path, parts):
    if parts == 1:
        return [path]
    stem, ext = path[:-4], path[-4:]  # ".csv"
    return [stem + "-part" + str(n) + ext for n in range(1, parts + 1)]


def _check_new_files(paths):
    for path in paths:
        folder = os.path.dirname(os.path.abspath(path))
        if not os.path.isdir(folder):
            raise Stop("There is no folder " + folder + ".", EXIT_USAGE)
        if os.path.lexists(path):
            raise Stop(path + " already exists. The script never writes over a file: give a new name, "
                       "or move that file away first.", EXIT_USAGE)


def _ask_password(from_stdin):
    if from_stdin:
        line = sys.stdin.readline()
        if not line:
            raise Stop("No master password came on standard input.", EXIT_USAGE)
        return line[:-1] if line.endswith("\n") else line
    try:
        return getpass.getpass("Master password of the backup: ")
    except EOFError:
        raise Stop("Stopped. Nothing was written.", EXIT_STOPPED)


def _open_with_password(crypto, checked, from_stdin):
    tries = 0
    while True:
        password = _ask_password(from_stdin)
        if not master_password_bytes(password):
            if from_stdin:
                raise Stop(MSG_WRONG, EXIT_FAILED)
            say("Nothing was typed. Type the master password, or press Ctrl+C to stop.")
            continue
        say("Opening... (this takes a second)")
        try:
            vk = unwrap(crypto, checked, master_password_bytes(password))
        except MemoryError:
            raise Stop(MSG_NO_MEMORY, EXIT_FAILED)
        if vk is not None:
            return vk
        tries += 1
        message = MSG_WRONG if checked.trailer_ok else MSG_WRONG_OR_DAMAGED
        if from_stdin or tries >= 5:
            raise Stop(message, EXIT_FAILED)
        say(message)


def _confirm(from_stdin, assume_yes):
    if assume_yes:
        return
    prompt = "Type yes to write it, or press Enter to stop: "
    try:
        if from_stdin:
            sys.stdout.write(prompt)
            sys.stdout.flush()
            answer = sys.stdin.readline()
        else:
            answer = input(prompt)
    except EOFError:
        answer = ""
    if answer.strip().lower() != "yes":
        raise Stop("Stopped. Nothing was written.", EXIT_STOPPED)


def _write_new(path, data):
    """Creates path, which must not exist, writes data, reads it back and compares (formats.md §2 rule 3)."""
    flags = os.O_WRONLY | os.O_CREAT | os.O_EXCL | getattr(os, "O_BINARY", 0)
    try:
        fd = os.open(path, flags, 0o600)
    except FileExistsError:
        raise Stop(path + " already exists. The script never writes over a file: give a new name.", EXIT_USAGE)
    except OSError as e:
        raise Stop("Couldn't create " + path + " (" + (e.strerror or type(e).__name__) + "). Try another folder.",
                   EXIT_FAILED)
    try:
        try:
            with os.fdopen(fd, "wb") as f:
                f.write(data)
                f.flush()
                os.fsync(f.fileno())
            with open(path, "rb") as f:
                back = f.read()
        except OSError as e:
            raise Stop("Couldn't save to " + path + " (" + (e.strerror or type(e).__name__) + "). Try another folder.",
                       EXIT_FAILED)
        if back != data:
            raise Stop("Couldn't save to this place: the file read back was not what was written. Try another folder.",
                       EXIT_FAILED)
    except BaseException:  # Ctrl+C included: a file cut short would still hold passwords in clear
        _remove_quietly(path)
        raise


def _remove_quietly(path):
    try:
        os.remove(path)
    except OSError:
        pass


def _when(created_ms):
    try:
        return time.strftime("%Y-%m-%d at %H:%M", time.localtime(created_ms / 1000.0))
    except (OverflowError, OSError, ValueError):
        return None


def _accounts(n):
    return "1 account" if n == 1 else str(n) + " accounts"


def decrypt(backup, output, from_stdin, assume_yes):
    kind = _output_kind(output)
    data = _read_backup(backup)
    _check_new_files([output])
    checked = check_file(data)
    if checked.kind == "not-ours":
        raise Stop(MSG_NOT_OURS, EXIT_FAILED)
    if checked.kind == "newer":
        raise Stop(MSG_NEWER, EXIT_FAILED)
    if checked.kind == "damaged":
        raise Stop(MSG_DAMAGED + " (Rule R" + str(checked.rule) + " of docs/format.md failed.)", EXIT_FAILED)
    crypto = load_crypto()
    vk = _open_with_password(crypto, checked, from_stdin)
    try:
        vault = open_body(crypto, checked, vk)
    except Newer:
        raise Stop(MSG_NEWER, EXIT_FAILED)
    except Damaged as e:
        raise Stop(MSG_DAMAGED + " (Its accounts did not open: " + str(e) + ".)", EXIT_FAILED)
    kept = [e for e in vault.entries if e.deleted_at is None]
    deleted = len(vault.entries) - len(kept)
    when = _when(checked.created)
    say("The backup opened. It holds " + _accounts(len(kept)) + "."
        + (" It was made on " + when + " by " + checked.generator + "." if when else ""))
    if deleted:
        say(_accounts(deleted) + " in Recently deleted " + ("is" if deleted == 1 else "are") + " not written.")
    if not checked.trailer_ok:
        say("The end of this file is damaged, but your accounts opened and are complete.")
    if kind == "csv":
        files = csv_export(vault.entries)
    else:
        files = [json_export(vault, int(time.time() * 1000), GENERATOR)]
    paths = _part_paths(output, len(files))
    _check_new_files(paths)
    say()
    say("This file will not be protected" if len(paths) == 1 else "These files will not be protected")
    for path in paths:
        say("  " + os.path.abspath(path))
    for line in WARNING_LINES + ([WARNING_CSV] if kind == "csv" else []):
        say(line)
    if len(paths) > 1:
        say("- Google takes at most 3,000 rows and 1,000 KB in one file, so the accounts are cut into "
            + str(len(paths)) + " files.")
    _confirm(from_stdin, assume_yes)
    written = []
    try:
        for path, content in zip(paths, files):
            _write_new(path, content)
            written.append(path)
    except BaseException:
        for path in written:  # all the files or none
            _remove_quietly(path)
        raise
    say()
    say("Saved " + _accounts(len(kept)) + " to " + (paths[0] if len(paths) == 1 else str(len(paths)) + " files") + ".")
    say("Delete " + ("it" if len(paths) == 1 else "them") + " as soon as you're done, and empty the Recycle Bin or Trash.")
    return EXIT_DONE


def _parser():
    parser = argparse.ArgumentParser(
        prog="pvault_decrypt.py", description=HELP_TEXT, epilog=HELP_EPILOG,
        formatter_class=argparse.RawDescriptionHelpFormatter, add_help=False)
    parser.add_argument("backup", nargs="?", metavar="BACKUP", help="the backup file (.pvault)")
    parser.add_argument("output", nargs="?", metavar="OUTPUT",
                        help="the new file to write; its name ends in .csv or .json")
    parser.add_argument("-h", "--help", action="help", help="show this text and stop")
    parser.add_argument("--check", action="store_true",
                        help="only check that this computer has what the script needs")
    parser.add_argument("--yes", action="store_true",
                        help="do not wait for yes before writing (the warning is still shown)")
    parser.add_argument("--password-stdin", action="store_true",
                        help="read the master password from the first line of standard input instead of "
                             "asking for it, and the answer to the question from the next line "
                             "(for use from other programs)")
    parser.add_argument("--version", action="version", version=GENERATOR)
    return parser


def main(argv=None):
    for stream in (sys.stdout, sys.stderr):
        try:
            stream.reconfigure(errors="replace")
        except (AttributeError, ValueError):
            pass
    args = _parser().parse_args(argv)
    asked = False
    status = EXIT_DONE
    try:
        if args.check:
            status = self_check()
        else:
            backup, output = args.backup, args.output
            if (backup is None or output is None) and not args.password_stdin and _interactive():
                asked = True
                say("PasswordVault backup opener " + SCRIPT_VERSION + " (more: run it with --help)")
                if backup is None:
                    backup = _clean_path(input("Backup file (drag it into this window, then press Enter): "))
                if output is None:
                    output = _clean_path(input("Name of the new file, ending in .csv or .json [passwords.csv]: ")
                                         ) or "passwords.csv"
            if not backup or not output:
                _parser().print_usage()
                raise Stop("Give the backup file and the name of the new file, for example:\n"
                           "  python3 pvault_decrypt.py PasswordVault-backup-2026-10-07.pvault passwords.csv\n"
                           "Run it with --help for more.", EXIT_USAGE)
            status = decrypt(backup, output, args.password_stdin, args.yes)
    except Stop as stop:
        say(stop.message.rstrip("\n"))
        status = stop.status
    except KeyboardInterrupt:
        say()
        say("Stopped. Nothing was written.")
        status = EXIT_STOPPED
    if asked:
        try:
            input("Press Enter to close this window.")
        except (EOFError, KeyboardInterrupt):
            pass
    return status


if __name__ == "__main__":
    sys.exit(main())
