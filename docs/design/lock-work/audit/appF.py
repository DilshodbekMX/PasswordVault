import random
H = 3_600_000
def observe(credited, seen, now):                                   # the helper of 6.9
    return credited + (now - seen if now >= seen else now), now
def days(opens, need=24 * H, trials=2000, seed=1):
    """The phone is on from 07:00 to 23:00, the app is opened at the hours in `opens` for one minute;
       every time varies by up to 10 minutes. Returns the median and the longest number of days."""
    rnd, out = random.Random(seed), []
    jitter = lambda: rnd.uniform(-10, 10) * 60_000
    for _ in range(trials):
        credited, seen, day, done = 0, None, 0, None
        while done is None:
            boot = 7 * H + jitter()
            for o in opens:
                t = o * H + jitter()
                for now in (t - boot, t - boot + 60_000):           # ON_START and ON_STOP of that visit
                    if seen is None: seen = now                     # the request is made at the first open
                    else: credited, seen = observe(credited, seen, now)
                    if done is None and credited >= need: done = day + (o - opens[0]) / 24
            day += 1
        out.append(done)
    out.sort()
    return out[len(out) // 2], out[-1]
for name, opens in (('morning and evening', [9, 21]), ('once, in the evening', [21]), ('once, two hours after switching on', [9])):
    print('%-36s median %4.1f days, longest %4.1f days' % (name, *days(opens)))
