import base64, hashlib, struct
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
b64 = lambda b: base64.b64encode(b).decode()
KEK = bytes.fromhex("853b272a44db1421c02962669a55eb0994f3cab385ed1c4c79253eee19bab49e")
VK, salt, slot_nonce = bytes(range(0xa0, 0xc0)), bytes(range(0x00, 0x10)), bytes(range(0x20, 0x2c))
wrapped = AESGCM(KEK).encrypt(slot_nonce, VK, None)

def seal(body, generation, created, body_nonce):
    plain = body + b' ' * (-len(body) % 4096)
    header = ('{"format":"passwordvault","version":1,"cipher":"AES-256-GCM",'
              '"vault_id":"00000000-0000-4000-8000-000000000001","vk_id":"00000000-0000-4000-8000-000000000002",'
              '"generation":%d,"created":%d,"generator":"PasswordVault 1.0","writer_version":1,"body_len":%d,'
              '"slots":[{"type":"password","kdf":"argon2id","argon2_version":19,"m_kib":65536,"t":3,"p":4,'
              '"salt":"%s","nonce":"%s","wrapped_key":"%s"}]}') % (
              generation, created, len(plain) + 16, b64(salt), b64(slot_nonce), b64(wrapped))
    aad = b"PVAULT01" + struct.pack(">I", len(header)) + header.encode()
    pre = aad + body_nonce + AESGCM(VK).encrypt(body_nonce, plain, aad)
    return pre + hashlib.sha256(pre).digest()

BODY_B = r'''{"schema":1,"entries":[{"id":"00000000-0000-4000-8000-000000000011","title":"Gmail – Oʻzbekiston Ўзбекистон","username":"aziz@example.com","password":"p\"ss\\w/rd-🔑","notes":"Birinchi qator\nИккинчи қатор\ttab\u0001","urls":["https://mail.google.com","https://accounts.google.com/a?b=c"],"fields":[{"name":"PIN code","value":"0042","secret":true},{"name":"Телефон","value":"+998 90 123-45-67","secret":false}],"favourite":true,"changePending":true,"checkIgnored":false,"createdAt":1791244800000,"updatedAt":1791331200000,"passwordChangedAt":1791331200000,"deletedAt":null,"pendingOldPassword":"eski-parol","passwordHistory":[{"password":"old2","replacedAt":1791331200000},{"password":"old1","replacedAt":1791288000000}],"futureEntryKey":"kept"}],"draft":{"entryId":null,"savedAt":1791331260000,"entry":{"title":"Yangi","username":"","password":"gen-🔑","notes":"","urls":[],"fields":[],"favourite":false}},"plainExports":[{"uri":"content://com.android.providers.downloads.documents/document/17","name":"passwords-2026-10-07.csv","savedAt":1791331300000}],"backups":[{"uri":"content://com.android.externalstorage.documents/document/primary%3ADownload%2FPasswordVault-backup-2026-10-07.pvault","name":"PasswordVault-backup-2026-10-07.pvault","savedAt":1791331400000}],"masterPasswordMatches":["00000000-0000-4000-8000-000000000011"],"securitySeenAt":1791331500000,"futureTopKey":{"n":1.50,"list":[true,null,"x"]}}'''.encode("utf-8")
a = seal(b'{"schema":1,"entries":[]}', 1, 1791244800000, bytes(range(0x30, 0x3c)))
b = seal(BODY_B, 7, 1791331200000, bytes(range(0x40, 0x4c)))
open("v1-container.pvault", "wb").write(a)
open("v1-body.pvault", "wb").write(b)
print(len(a), hashlib.sha256(a).hexdigest())     # 4667 3210ed2c…1d2d
print(len(b), hashlib.sha256(b).hexdigest())     # 4667 1bb8d230…12c7
