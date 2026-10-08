"""Independent Agile Encryption reader written from the [MS-OFFCRYPTO] text fetched 2026-10-06
(2.3.4.10 EncryptionInfo, 2.3.4.11 key generation, 2.3.4.12 IV generation, 2.3.4.13 PasswordKeyEncryptor,
2.3.4.14 DataIntegrity, 2.3.4.15 data encryption), using olefile only to open the compound file and
python-cryptography for AES. It does NOT reuse msoffcrypto-tool. For every file it also runs msoffcrypto-tool,
checks the container with olefile, and compares the decrypted bytes with the expected plaintext.
Usage: agile_check.py PASSWORD (LOCKED PLAIN)..."""
import base64, hashlib, hmac, io, struct, sys, xml.etree.ElementTree as ET
import olefile, msoffcrypto
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

NS = {"e": "http://schemas.microsoft.com/office/2006/encryption",
      "p": "http://schemas.microsoft.com/office/2006/keyEncryptor/password"}
BK_VERIFIER_INPUT = bytes([0xfe, 0xa7, 0xd2, 0x76, 0x3b, 0x4b, 0x9e, 0x79])
BK_VERIFIER_VALUE = bytes([0xd7, 0xaa, 0x0f, 0x6d, 0x30, 0x61, 0x34, 0x4e])
BK_KEY_VALUE = bytes([0x14, 0x6e, 0x0b, 0xe7, 0xab, 0xac, 0xd0, 0xd6])
BK_HMAC_KEY = bytes([0x5f, 0xb2, 0xad, 0x01, 0x0c, 0xb9, 0xe1, 0xf6])
BK_HMAC_VALUE = bytes([0xa0, 0x67, 0x7f, 0x02, 0xb2, 0x2c, 0x84, 0x33])


def H(name):
    return {"SHA512": hashlib.sha512, "SHA1": hashlib.sha1, "SHA256": hashlib.sha256}[name]


def fit(b, n, pad):  # 2.3.4.11 / 2.3.4.12: pad with 0x36 or truncate
    return b[:n] if len(b) >= n else b + bytes([pad]) * (n - len(b))


def aes_dec(key, iv, data):
    d = Cipher(algorithms.AES(key), modes.CBC(iv)).decryptor()
    return d.update(data) + d.finalize()


def decrypt(path, password):
    ole = olefile.OleFileIO(path)
    info = ole.openstream("EncryptionInfo").read()
    vmaj, vmin, flags = struct.unpack("<HHI", info[:8])
    assert (vmaj, vmin, flags) == (4, 4, 0x40), (vmaj, vmin, flags)
    root = ET.fromstring(info[8:])
    kd = root.find("e:keyData", NS)
    di = root.find("e:dataIntegrity", NS)
    pk = root.find("e:keyEncryptors/e:keyEncryptor/p:encryptedKey", NS)
    hname = pk.get("hashAlgorithm"); h = H(hname)
    salt = base64.b64decode(pk.get("saltValue"))
    key_bytes = int(pk.get("keyBits")) // 8
    block = int(pk.get("blockSize"))
    # 2.3.4.11
    hn = h(salt + password.encode("utf-16-le")).digest()
    for i in range(int(pk.get("spinCount"))):
        hn = h(struct.pack("<I", i) + hn).digest()
    def key(bk):
        return fit(h(hn + bk).digest(), key_bytes, 0x36)
    iv_pw = fit(salt, block, 0x36)  # 2.3.4.12 without blockKey: IV = KeySalt
    vin = aes_dec(key(BK_VERIFIER_INPUT), iv_pw, base64.b64decode(pk.get("encryptedVerifierHashInput")))
    vhash = aes_dec(key(BK_VERIFIER_VALUE), iv_pw, base64.b64decode(pk.get("encryptedVerifierHashValue")))
    vin = vin[:int(pk.get("saltSize"))]
    if h(vin).digest() != vhash[:int(pk.get("hashSize"))]:
        return None, "wrong password"
    secret = aes_dec(key(BK_KEY_VALUE), iv_pw, base64.b64decode(pk.get("encryptedKeyValue")))[:int(kd.get("keyBits")) // 8]
    kd_salt = base64.b64decode(kd.get("saltValue"))
    kh = H(kd.get("hashAlgorithm"))
    kblock = int(kd.get("blockSize"))
    # 2.3.4.14 data integrity
    ep = ole.openstream("EncryptedPackage").read()
    hk_enc = base64.b64decode(di.get("encryptedHmacKey"))
    hmac_key_full = aes_dec(secret, fit(kh(kd_salt + BK_HMAC_KEY).digest(), kblock, 0x36), hk_enc)
    hv = aes_dec(secret, fit(kh(kd_salt + BK_HMAC_VALUE).digest(), kblock, 0x36), base64.b64decode(di.get("encryptedHmacValue")))
    hsize = int(kd.get("hashSize")); ssize = int(kd.get("saltSize"))
    mac_full = hmac.new(hmac_key_full[:hsize], ep, kh).digest()        # key as the writer stored it (hashSize bytes)
    mac_spec = hmac.new(hmac_key_full[:ssize], ep, kh).digest()        # key length per the 2.3.4.14 text (saltSize bytes)
    integrity = "ok (hashSize key)" if hv[:hsize] == mac_full else ("ok (saltSize key)" if hv[:hsize] == mac_spec else "FAILED")
    # 2.3.4.15 data
    size = struct.unpack("<Q", ep[:8])[0]
    out = bytearray(); data = ep[8:]
    for seg in range(0, (len(data) + 4095) // 4096):
        chunk = data[seg * 4096:(seg + 1) * 4096]
        iv = fit(kh(kd_salt + struct.pack("<I", seg)).digest(), kblock, 0x36)
        out += aes_dec(secret, iv, chunk)
    return bytes(out[:size]), f"integrity {integrity}; hmac key stored {len(hk_enc)} bytes; package {size} bytes"


def container(path):
    ole = olefile.OleFileIO(path)
    probs = []
    for e in ole.direntries:
        if e is None:
            continue
        if e.color != 1: probs.append(f"{e.name}: red node")
    hdr = open(path, "rb").read(512)
    num_difat = struct.unpack("<I", hdr[0x48:0x4C])[0]
    num_fat = struct.unpack("<I", hdr[0x2C:0x30])[0]
    return f"olefile ok, {num_fat} FAT sectors, {num_difat} DIFAT sectors" + (f"; {probs}" if probs else "")


pw = sys.argv[1]
args = sys.argv[2:]
for locked, plain in zip(args[0::2], args[1::2]):
    want = open(plain, "rb").read()
    try:
        got, note = decrypt(locked, pw)
        mine = "MATCH" if got == want else ("MISMATCH" if got is not None else note)
    except Exception as e:
        mine, note = f"ERROR {type(e).__name__}: {e}", ""
    try:
        f = msoffcrypto.OfficeFile(open(locked, "rb")); f.load_key(password=pw, verify_password=True)
        b = io.BytesIO(); f.decrypt(b, verify_integrity=True)
        mso = "MATCH" if b.getvalue() == want else "MISMATCH"
    except Exception as e:
        mso = f"ERROR {type(e).__name__}: {e}"
    try:
        wrong, _ = decrypt(locked, pw + "x")
        wrong = "refused" if wrong is None else "ACCEPTED"
    except Exception as e:
        wrong = f"error {type(e).__name__}"
    try:
        cont = container(locked)
    except Exception as e:
        cont = f"olefile ERROR {type(e).__name__}: {e}"
    print(f"{locked.rsplit('/', 1)[-1]:28} mine {mine:8} msoffcrypto {mso:8} wrong pw {wrong:8} {cont}; {note}")
