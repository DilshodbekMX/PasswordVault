import random
FORGIVE_MAX = 2
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
                    if rnd.random() < quit: break            # given up: stays counted
                else:
                    if design == 'final': total -= min(FORGIVE_MAX, visit)   # retyped at once: within the 60 s window
                    row = 0; break
            if dead: break
        off += dead
    return off / trials
if __name__ == '__main__':
    print('days U typos quit | every wrong PIN counts | this design')
    for (days,U,p,q) in [(14,8,0.031,0),(14,10,0.05,0),(14,20,0.03,0),(14,20,0.05,0.2),(21,20,0.03,0.2),(21,20,0.05,0.2)]:
        print(days,U,p,q,'|', '%.1f %%' % (100*run(U,p,q,days,'d1')),'|', '%.2f %%' % (100*run(U,p,q,days,'final')))
    print('no master password typed at all, 8 unlocks a day, 3.1 % typos:')
    for q in (0.2, 0.1, 0.0):
        print('  given up %d %%:' % (q*100), ', '.join('%d days %.1f %%' % (d, 100*run(8,0.031,q,d,'final')) for d in (90,180,365)))
