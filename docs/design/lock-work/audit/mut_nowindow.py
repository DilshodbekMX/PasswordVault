"""Record on disk: (row, total, charged, off). Each write replaces the record.
   kill      : every handed write survives, memory is lost
   power cut : only writes handed at least SETTLE ago survive (a drive cache that fsync does not flush)
   Anyone may type: 'wrong' = a check with a wrong PIN, 'right' = with the right PIN. No master password.
   A right PIN forgives at most FMAX wrong checks of the same visit that are at most WINDOW old.
   P1  wrong checks since the last right PIN                 <= ROW
   P2  wrong checks that no right PIN forgave                <= TOT"""
import sys
from collections import deque
ROW, TOT, SETTLE, MAXDEPTH, FMAX, WINDOW = (int(x) for x in sys.argv[1:7])
VARIANT = sys.argv[7] if len(sys.argv) > 7 else 'aged'          # aged (the spec) | noage | countafter
FLOOR = -max(SETTLE, WINDOW) - 1

def explore():
    # state: t, W=((t_handed,row,total,charged,off),...), mem, consec, unforg, vtrue
    # vw (in mem) and vtrue are the times of this visit's last FMAX wrong checks: what the app remembers, and the truth
    start = (0, ((-99, 0, 0, False, False),), ('idle', ()), 0, 0, ())
    q, seen, w1, w2 = deque([(start, ())]), set(), (0, ()), (0, ())
    while q:
        (t, W, mem, consec, unforg, vtrue), path = q.popleft()
        if consec > w1[0]: w1 = (consec, path)
        if unforg > w2[0]: w2 = (unforg, path)
        if len(path) >= MAXDEPTH: continue
        _, row, total, charged, off = W[-1]
        nxt = []
        wr = lambda rec: W + ((t,) + rec,)
        limit = lambda r, tt: r >= ROW or tt >= TOT
        fresh = lambda times: sum(1 for x in times if t - x <= WINDOW)        # at most FMAX entries are kept
        add = lambda times: (times + (t,))[-FMAX:] if FMAX else ()
        if mem is not None:
            kind = mem[0]
            if kind == 'idle' and not off and not limit(row, total):
                vw = mem[1]
                if VARIANT == 'countafter':
                    nxt.append(('submit wrong', (t, W, ('resW', add(vw)), consec + 1, unforg + 1, add(vtrue))))
                    nxt.append(('submit right', (t, W, ('resR', len(vw)), 0, unforg - fresh(vtrue), ())))
                else:
                    nxt.append(('digit1: charge', (t, wr((row, total, True, False)), ('charged', t, vw), consec, unforg, vtrue)))
            if kind == 'charged':
                tc, vw = mem[1], mem[2]
                if VARIANT == 'noage' or t - tc >= SETTLE:
                    nxt.append(('digit6 wrong', (t, W, ('resW', add(vw)), consec + 1, unforg + 1, add(vtrue))))
                    nxt.append(('digit6 right', (t, W, ('resR', len(vw)), 0, unforg - fresh(vtrue), ())))
                nxt.append(('given up: refund', (t, wr((row, total, False, False)), ('idle', vw), consec, unforg, vtrue)))
                nxt.append(('visit ends: refund', (t, wr((row, total, False, False)), ('idle', ()), consec, unforg, ())))
            if kind == 'resW':
                r2, t2 = row + 1, total + 1
                nxt.append(('write wrong', (t, wr((r2, t2, False, limit(r2, t2))), ('idle', mem[1]), consec, unforg, vtrue)))
            if kind == 'resR':      # the list is already open; this write runs in the background
                nxt.append(('write right', (t, wr((0, max(0, total - mem[1]), False, False)), ('idle', ()), consec, unforg, vtrue)))
            if kind == 'idle' and mem[1]:
                nxt.append(('visit ends', (t, W, ('idle', ()), consec, unforg, ())))
            nxt.append(('kill', (t, W, None, consec, unforg, ())))
            durable = [w for w in W if t - w[0] >= SETTLE] or [W[0]]
            nxt.append(('power cut', (t, (durable[-1],), None, consec, unforg, ())))
        elif charged:
            r2, t2 = row + 1, total + 1
            nxt.append(('start: charge counted', (t, wr((r2, t2, False, off or limit(r2, t2))), ('idle', ()), consec, unforg, ())))
        else:
            nxt.append(('start', (t, W, ('idle', ()), consec, unforg, ())))
        nxt.append(('wait', (t + 1, W, mem, consec, unforg, vtrue)))
        for name, (tt, WW, m, c, u, v) in nxt:
            keep = [i for i, w in enumerate(WW) if tt - w[0] >= SETTLE]
            if keep: WW = WW[keep[-1]:]
            norm = lambda x: max(x - tt, FLOOR)
            nt = lambda times: tuple(norm(x) for x in times)
            if m is None: mk = None
            elif m[0] == 'charged': mk = ('charged', norm(m[1]), nt(m[2]))
            elif m[0] == 'resR': mk = m
            else: mk = (m[0], nt(m[1]))
            key = (tuple((norm(w[0]),) + w[1:] for w in WW), mk, c, u, nt(v))
            if key not in seen:
                seen.add(key); q.append(((tt, WW, m, c, u, v), path + (name,)))
    return w1, w2

w1, w2 = explore()
print('wrong in a row:', w1[0], 'OK' if w1[0] <= ROW else 'VIOLATED: ' + ' > '.join(w1[1]))
print('not forgiven  :', w2[0], 'OK' if w2[0] <= TOT else 'VIOLATED: ' + ' > '.join(w2[1]))
