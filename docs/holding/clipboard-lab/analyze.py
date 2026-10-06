#!/usr/bin/env python3
"""Summarise results/runs-*.jsonl written by clipexp.py.

  python3 analyze.py            one row per (Android, plan, app variant, mechanism, condition)
  python3 analyze.py -v         also one line per run
  python3 analyze.py -p base    only plans whose name starts with "base" (repeatable: -p kill -p freeze)

Columns
  delay ms        elapsedRealtime at the clear call minus elapsedRealtime at the copy, as logged by the app
  late max        worst (delay - requested delay) among the runs that cleared while we waited
  empty after     the clipboard read with focus by ANOTHER app after the wait: EMPTY (no clip), "" (empty
                  clip, the Android 7 way of clearing), VALUE (the secret is still there)
  watcher gone    for runs with the watcher app in front: when the watcher (polling every 50 ms) first saw the
                  secret gone, measured from the copy; "seen" = runs where it saw the secret before that
"""
import glob
import json
import os
import statistics
import sys
from collections import OrderedDict

LAB = os.path.dirname(os.path.abspath(__file__))
args = sys.argv[1:]
verbose = '-v' in args
plans = [args[i + 1] for i, a in enumerate(args) if a == '-p']
runs = []
for path in sorted(glob.glob(os.path.join(LAB, 'results', 'runs-*.jsonl'))):
    for line in open(path):
        if line.strip():
            runs.append(json.loads(line))
runs = [r for r in runs if 'result' in r and r.get('plan') not in ('smoke',) and r.get('copy')
        and r.get('plan') not in ('othercopy', 'overlay', 'fgclear', 'status')]
if plans:
    runs = [r for r in runs if any(str(r.get('plan', '')).startswith(p) for p in plans)]
runs.sort(key=lambda r: r.get('hostStart', ''))
for r in runs:
    # The watcher's last poll can land while our own probe is taking the focus away and then reads
    # "empty"; such a reading is an artefact of the probe, not a clear.
    x = r['result']
    gone = next((w for w in r.get('watch', []) if w[1] != 'VALUE' and w[0] < r.get('probeAt', 1e9) * 1000 - 250), None)
    x['watchGoneDt'], x['watchGoneTo'] = (gone[0], gone[1]) if gone else (None, None)

ANDROID = {24: '7.0', 28: '9', 37: '17'}
groups = OrderedDict()
for r in runs:
    key = (ANDROID.get(r.get('sdk'), r.get('sdk')), r.get('plan'), r.get('app', 'full'), r['mech'], r['cond'],
           r['delayMs'] // 1000, r.get('build', '2'))
    groups.setdefault(key, []).append(r)


def mmm(xs):
    return f'{min(xs)} / {int(statistics.median(xs))} / {max(xs)}' if xs else '-'


print('| Android | build | plan | app | mechanism | condition | delay s | runs (bg) | cleared in wait | threw | delay ms min / median / max | '
      'late max ms | empty after | watcher gone ms min / median / max (seen) | frozen seen | new process | '
      'not cleared: on return to app |')
print('|' + '---|' * 17)
for (android, plan, app, mech, cond, delay, build), rs in groups.items():
    n = len(rs)
    res = [r['result'] for r in rs]
    bgs = OrderedDict()
    for r in rs:
        bgs[r['bg']] = bgs.get(r['bg'], 0) + 1
    cleared = [x for x in res if x['cleared'] and x.get('outcome') != 'skipped']
    dts = [x['dt'] for x in cleared]
    lates = [x['late'] for x in cleared]
    threw = sum(1 for x in cleared if x['threw'])
    probes = OrderedDict()
    for x in res:
        p = {'EMPTY': 'EMPTY', 'EMPTYTEXT': '""', 'VALUE': 'VALUE'}.get(x['probe'], x['probe'])
        probes[p] = probes.get(p, 0) + 1
    w = [x for x in res if x.get('watchFirst')]
    gone = [x['watchGoneDt'] for x in w if x.get('watchGoneDt') is not None]
    seen = sum(1 for x in w if x.get('watchFirst') == 'VALUE')
    frozen = sum(1 for x in res if x['frozenSeen'])
    newproc = sum(1 for x in cleared if x['pidChanged'])
    notc = [x for x in res if not x['cleared']]
    onret = [x['clearOnReturnDt'] for x in notc if x.get('clearOnReturnDt') is not None]
    ret = '-'
    if notc:
        after = OrderedDict()
        for x in notc:
            after[x.get('probeAfterReturn')] = after.get(x.get('probeAfterReturn'), 0) + 1
        ret = (f'{len(onret)}/{len(notc)} cleared then' + (f' ({mmm(onret)} ms)' if onret else '') +
               '; after: ' + ', '.join(f'{k} {v}' for k, v in after.items()))
    print(f'| {android} | {build} | {plan} | {app} | {mech} | {cond} | {delay} | {n} ({", ".join(f"{k} {v}" for k, v in bgs.items())}) | '
          f'{len(cleared)}/{n} | {threw} | {mmm(dts)} | {max(lates) if lates else "-"} | '
          f'{", ".join(f"{k} {v}" for k, v in probes.items())} | '
          f'{mmm(gone) + f" ({seen}/{len(w)})" if w else "-"} | {frozen}/{n} | {newproc}/{len(cleared)} | {ret} |')

if verbose:
    print()
    for r in runs:
        x = r['result']
        extra = ''
        if r.get('final'):
            extra = ' final=' + ';'.join(f'{e[0]}:{e[1].replace("final_", "")}:{e[2]}' for e in r['final'])
        adj = [s[1].split('adj=')[-1] for s in r.get('samples', [])]
        fro = ['F' if 'frozen=true' in s[1] else ('-' if 'frozen=false' in s[1] else 'x') for s in r.get('samples', [])]
        print(f"{r['run']} sdk={r.get('sdk')} app={r.get('app')} dt={x['dt']} late={x['late']} via={x['via']} "
              f"outcome={x.get('outcome')} threw={x['threw']} newpid={x['pidChanged']} probe={x['probe']} "
              f"pre={x.get('probeBefore')} watch={x['watchFirst']}@{x.get('watchValueFrom')}->{x['watchGoneTo']}@{x['watchGoneDt']} "
              f"onReturn={x['clearOnReturnDt']}/{x.get('probeAfterReturn')} adj={','.join(adj)} frozen={''.join(fro)}{extra}")
