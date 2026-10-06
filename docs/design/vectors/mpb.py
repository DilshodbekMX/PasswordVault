import unicodedata as U, random, sys
WS = [0x9,0xA,0xB,0xC,0xD,0x20,0x85,0xA0,0x1680]+list(range(0x2000,0x200B))+[0x2028,0x2029,0x202F,0x205F,0x3000]
AP = [0x27,0x60,0xB4,0x2018,0x2019,0x201B,0x2032,0x2035,0x2B9,0x2BB,0x2BC,0x2BD,0x2BE,0x2BF,0xA78B,0xA78C,0xFF07]
assert len(WS)==25 and len(AP)==17
WSs={chr(c) for c in WS}; APs={chr(c) for c in AP}
def ws(s):
    s=''.join(' ' if ch in WSs else ch for ch in s)
    out=[]
    for ch in s:
        if ch==' ' and out and out[-1]==' ': continue
        out.append(ch)
    return ''.join(out).strip(' ')
def ap(s): return ''.join("'" if ch in APs else ch for ch in s)
def lock(s):  return ap(ws(U.normalize('NFC',s)))            # lock.md 8.1 after the audit
def rev(s):   return ap(U.normalize('NFC',ap(ws(s))))        # coverage reviewer's proposal
def old(s):   return U.normalize('NFC',ap(ws(s)))            # architecture.md 4.6 as written
print('unicodedata', U.unidata_version)
# every single code point (no surrogates), alone and between letters
bad_old=[];diff_lr=[];notidem=[];nfd=[]
for cp in range(0x110000):
    if 0xD800<=cp<=0xDFFF: continue
    for s in (chr(cp), 'a'+chr(cp)+'b', chr(cp)+'́', ' '+chr(cp)+' x'):
        l=lock(s)
        if l!=rev(s): diff_lr.append((hex(cp),s))
        if lock(l)!=l: notidem.append(hex(cp))
        if lock(U.normalize('NFD',s))!=l or lock(U.normalize('NFC',s))!=l: nfd.append(hex(cp))
        if old(U.normalize('NFD',s))!=old(U.normalize('NFC',s)) or old(s)!=l: bad_old.append(hex(cp))
print('lock != reviewer order:',len(diff_lr),diff_lr[:8])
print('lock not idempotent:',sorted(set(notidem))[:8])
print('lock NFD/NFC variance:',sorted(set(nfd))[:8])
print('old order differs from lock or is NFD-variant at:',sorted(set(bad_old))[:12],len(set(bad_old)))
# random strings over both lists, combining marks, letters
pool=[chr(c) for c in WS+AP]+['́','̈','̆','͂','ͅ','ʹ','`','´','a','o','g','У','о','ʻ','¨','᾿','῾','΅']
random.seed(1)
c1=c2=c3=0
for i in range(200000):
    s=''.join(random.choice(pool) for _ in range(random.randint(0,12)))
    l=lock(s)
    if lock(l)!=l: c1+=1
    if lock(U.normalize('NFD',s))!=l: c2+=1
    if rev(s)!=l: c3+=1
    assert U.normalize('NFC',l)==l
print('random: not idempotent',c1,'NFD-variant',c2,'differs from reviewer order',c3)
for name,s in [('greek',"oʹg`li´m"),('ws-only','  \t　 '),('ws','  correct  horse battery staple '),('uz',"oʻgʻlim-Toʻra"),('greek-word',"oʹgʹlim-Toʹra"),('greek2',"o`g`dlim"),]:
    print(name, lock(s).encode().hex(), '| old order:', old(s).encode().hex())
