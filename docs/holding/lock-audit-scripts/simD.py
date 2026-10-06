import random
def run(U, p, quit, days, design, trials=20000, seed=1):
    rnd, off = random.Random(seed), 0
    for _ in range(trials):
        row = total = 0; dead = False
        for _ in range(days * U):
            visit = 0
            while True:
                if rnd.random() < p:
                    row += 1; total += 1; visit += 1
                    if row >= 5 or total >= 10: dead = True; break
                    if rnd.random() < quit: break
                else:
                    if design == 'final': total -= visit
                    row = 0; break
            if dead: break
        off += dead
    return off / trials
for q in (0.2, 0.1):
    for d in (180, 365):
        print('no MP entry, U=8 p=3.1%% quit=%d%% days=%d ->' % (q*100, d), round(100*run(8, 0.031, q, d, 'final'), 1), '%')
# table of appendix D re-check
for (days,U,p,q) in [(14,8,0.031,0),(14,10,0.05,0),(14,20,0.03,0),(14,20,0.05,0.2),(21,20,0.03,0.2),(21,20,0.05,0.2)]:
    print(days,U,p,q, round(100*run(U,p,q,days,'d1'),1), round(100*run(U,p,q,days,'final'),1))
