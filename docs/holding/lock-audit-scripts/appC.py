import hmac, hashlib, uuid, unicodedata
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
vault, vk, slot = (uuid.UUID('00000000-0000-4000-8000-00000000000%d' % i).bytes for i in (1, 2, 3))
kpin, VK, nonce = bytes([0x42]) * 32, bytes(range(0xa0, 0xc0)), bytes(range(0x10, 0x1c))
msg = b'pvlock-v1:pin-kek' + b'\x00' + vault + slot + b'582913'
kek = hmac.new(kpin, msg, hashlib.sha256).digest()
aad = b'pvlock-v1:pin-wrap' + b'\x00' + vault + vk + slot
print(kek.hex(), AESGCM(kek).encrypt(nonce, VK, aad).hex())

APOS = [0x27, 0x60, 0xB4, 0x2018, 0x2019, 0x201B, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC, 0x02BD, 0x02BE, 0x02BF, 0xA78B, 0xA78C, 0xFF07]
WS = [*range(0x09, 0x0E), 0x20, 0x85, 0xA0, 0x1680, *range(0x2000, 0x200B), 0x2028, 0x2029, 0x202F, 0x205F, 0x3000]
def mp_bytes(s):
    s = unicodedata.normalize('NFC', s)                                                      # 1 NFC
    s = ' '.join(w for w in ''.join(' ' if ord(c) in WS else c for c in s).split(' ') if w)   # 2 white space
    s = ''.join("'" if ord(c) in APOS else c for c in s)                                     # 3 apostrophes
    return s.encode('utf-8')                                                                 # 4 UTF-8
print(mp_bytes('oʻgʻlim-Toʻra').hex(), mp_bytes('oʹg`lim-To´ra').hex(), mp_bytes('Köz-Ўzbek').hex())
