import unicodedata, itertools, random, hashlib
APOS = {0x27, 0x60, 0xB4, 0x2018, 0x2019, 0x201B, 0x2032, 0x2035, 0x02B9, 0x02BB, 0x02BC, 0x02BD, 0x02BE, 0x02BF, 0xA78B, 0xA78C, 0xFF07}
WS = set(range(0x09,0x0E)) | {0x20,0x85,0xA0,0x1680} | set(range(0x2000,0x200B)) | {0x2028,0x2029,0x202F,0x205F,0x3000}
assert len(WS)==25 and len(APOS)==17
nfc = lambda s: unicodedata.normalize('NFC', s)
def ws(s):
    t=''.join(' ' if ord(c) in WS else c for c in s)
    while '  ' in t: t=t.replace('  ',' ')
    return t.strip(' ')
def apos(s): return ''.join("'" if ord(c) in APOS else c for c in s)
def old_lock(s): return nfc(apos(s))                 # lock.md as audited: fold, NFC
def arch(s): return nfc(apos(ws(s)))                 # architecture.md 4.6: ws, apos, NFC
def new(s): return apos(ws(nfc(s)))                  # proposed: NFC, ws, apos
# 1. singletons / any code point whose NFC lands in a list
print('into APOS:', [hex(c) for c in range(0x110000) if not 0xD800<=c<=0xDFFF and c not in APOS and any(ord(x) in APOS for x in nfc(chr(c)))])
print('into WS  :', [hex(c) for c in range(0x110000) if not 0xD800<=c<=0xDFFF and c not in WS and any(ord(x) in WS for x in nfc(chr(c)))])
print('WS members changed by NFC:', [(hex(c),[hex(ord(x)) for x in nfc(chr(c))]) for c in sorted(WS) if nfc(chr(c))!=chr(c)])
# 2. idempotent, NFC-stable, and equal on canonically equivalent inputs (random strings over a tricky alphabet)
alpha = [chr(c) for c in list(APOS)+list(WS)+[0x374,0x1FEF,0x1FFD,0x301,0x308,0x306,0x327,0x323,0x41,0x6f,0x67,0x423,0x443,0x4e,0x1e0c,0xe9,0x212b,0xc5,0x1100,0x1161,0x11a8,0xac00,0x0f73,0x0f71,0x0f72]]
rnd = random.Random(7); bad=0
for i in range(200000):
    s=''.join(rnd.choice(alpha) for _ in range(rnd.randint(0,7)))
    r=new(s)
    if new(r)!=r or nfc(r)!=r: bad+=1; print('NOT STABLE', [hex(ord(c)) for c in s]); break
    for form in ('NFD','NFC'):
        if new(unicodedata.normalize(form,s))!=r: bad+=1; print('NOT CANON-INVARIANT', [hex(ord(c)) for c in s]); break
    if bad: break
print('new(): idempotent, NFC-stable, canonical-invariant on 200000 random strings:', bad==0)
# old orders are not canonical-invariant:
for name,f in (('lock.md as audited',old_lock),('architecture.md 4.6',arch)):
    ex=None
    for c in (0x374,0x1FEF,0x1FFD):
        if f(chr(c))!=f(nfc(chr(c))) or f(f(chr(c)))!=f(chr(c)): ex=hex(c); break
    print(name,'breaks on',ex)
# 3. vectors
for label,s in (('oʻgʻlim-Toʻra U+02BB',"oʻgʻlim-Toʻra"),('U+0374/1FEF/1FFD',"oʹg`lim-To´ra"),('decomposed Köz-Ўzbek',"Köz-Ўzbek")):
    print(label, new(s).encode().hex(), old_lock(s).encode().hex(), arch(s).encode().hex())
# does NFC-first ever differ from architecture order apart from the three singletons? exhaustive on single code points and on (cp, combining) pairs for list members
diff=[hex(c) for c in range(0x110000) if not 0xD800<=c<=0xDFFF and new(chr(c))!=arch(chr(c))]
print('single code points where new != architecture order:', diff)
