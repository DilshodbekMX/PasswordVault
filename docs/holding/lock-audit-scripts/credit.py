import random
H = 3600_000
def observe(credited, seen, now):
    gained = now - seen if now >= seen else now
    return credited + gained, now
def days_needed(opens, on=7, off=23, jitter_min=10, need=24*H, trials=2000, seed=1, visit_ms=60_000, max_days=120):
    """Phone on from `on` to `off` o'clock every day. The app is opened at the hours in `opens` (each visit lasts visit_ms:
       one observation at ON_START and one at ON_STOP). The request is made at the first open. Returns median and worst days."""
    rnd, out = random.Random(seed), []
    for _ in range(trials):
        credited = 0; seen = None; day = 0; done = None
        while done is None and day < max_days:
            boot = on * H + rnd.uniform(-jitter_min, jitter_min) * 60_000
            for o in opens:
                t = o * H + rnd.uniform(-jitter_min, jitter_min) * 60_000
                for now in (t - boot, t - boot + visit_ms):
                    if seen is None: seen = now                        # the request: credited 0
                    else: credited, seen = observe(credited, seen, now)
                    if credited >= need and done is None: done = day + (o - opens[0]) / 24
            day += 1
        out.append(done if done is not None else float('inf'))
    out.sort()
    return out[len(out)//2], out[-1]
for name, opens in (('once a day, 2 h after the phone is switched on', [9]), ('once a day in the evening', [21]),
                    ('morning and evening', [9, 21]), ('three times a day', [9, 14, 21])):
    print('%-48s median %5.1f days, worst %5.1f days' % (name, *days_needed(opens)))
