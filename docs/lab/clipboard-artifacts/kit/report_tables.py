#!/usr/bin/env python3
"""Builds the tables of the lab report from results/runs-*.jsonl (the same records analyze.py reads).

A cell reads  "cleared/runs: lateness range in ms"  where lateness = (elapsedRealtime at the clear call minus
elapsedRealtime at the copy) minus the requested delay, as logged by the app. "never" = no clear while the
driver waited (40 s past the deadline unless stated); "(reopen)" = it cleared only when the driver brought
the app back to the front afterwards. A run counts as cleared only if ANOTHER app with focus then read an
empty clipboard (or, on Android 7.0, an empty clip).
"""
import glob
import json
import os
import sys
from collections import OrderedDict, defaultdict

LAB = os.path.dirname(os.path.abspath(__file__))
runs = []
for path in sorted(glob.glob(os.path.join(LAB, 'results', 'runs-*.jsonl'))):
    for line in open(path):
        if line.strip():
            r = json.loads(line)
            if 'result' in r and r.get('copy') and r.get('plan') not in ('smoke', 'othercopy', 'overlay', 'fgclear', 'status'):
                runs.append(r)

for r in runs:
    x = r['result']
    gone = next((w for w in r.get('watch', []) if w[1] != 'VALUE' and w[0] < r.get('probeAt', 1e9) * 1000 - 250), None)
    x['watchGoneDt'] = gone[0] if gone else None
    c = r['cond']
    if c == 'swipe' and (r.get('pidAfterSwipe') or '').strip():
        c = 'swipe-missed'           # the gesture did not remove the task; the process lived on
    if c in ('kill', 'kill9') and (r.get('pidAfterKill') or '').strip():
        c = 'kill-refused'           # "am kill" did not kill (Android 7.0)
    if c == 'none' and r['bg'] == 'chainwatch':
        c = 'frozen'
    if c == 'none' and r.get('plan', '').endswith('controls'):
        c = 'frozen-controls' if x['frozenSeen'] else 'controls'
    r['c'] = c
    r['build'] = r.get('build', '2')
    r['sdk'] = r.get('sdk', 37)
    x['ok'] = bool(x['cleared']) and x.get('outcome') != 'skipped' and x['probe'] in ('EMPTY', 'EMPTYTEXT')


def cell(rs, wait_note=False):
    if not rs:
        return ''
    ok = [r for r in rs if r['result']['ok']]
    lates = [r['result']['late'] for r in ok]
    txt = f'{len(ok)}/{len(rs)}'
    if lates:
        lo, hi = min(lates), max(lates)
        txt += f': +{lo}' if lo == hi else f': +{lo} to +{hi}'
    bad = [r for r in rs if not r['result']['ok']]
    if bad:
        reopened = [r for r in bad if r['result'].get('clearOnReturnDt') is not None]
        weird = [r for r in bad if r['result']['cleared']]
        notes = []
        if len(bad) - len(weird) > 0:
            notes.append(f'{len(bad) - len(weird)} never' + (f' ({len(reopened)} on reopen)' if reopened else ''))
        if weird:
            notes.append(f'{len(weird)} logged a clear but clipboard not empty')
        txt += ('; ' if lates else ': ') + ', '.join(notes)
    return txt


def table(title, rows, cols, pick, note=''):
    print(f'\n### {title}\n')
    if note:
        print(note + '\n')
    print('| mechanism | ' + ' | '.join(c[0] for c in cols) + ' |')
    print('|---|' + '---|' * len(cols))
    for label, rf in rows:
        cells = [cell([r for r in runs if rf(r) and cf(r) and pick(r)]) for _, cf in cols]
        if any(cells):
            print(f'| {label} | ' + ' | '.join(c or '-' for c in cells) + ' |')


def m(mech, app='full', build=None):
    return lambda r: r['mech'] == mech and r.get('app', 'full') == app and (build is None or r['build'] == build)


ROWS17 = [
    ('(a) Handler.postDelayed', m('handler')),
    ('(a) coroutine delay', m('coroutine')),
    ('(b) set(), inexact', m('alarm_set')),
    ('(c) setExactAndAllowWhileIdle + USE_EXACT_ALARM', m('alarm_exact')),
    ('(d) setWindow(5 s)', m('alarm_window')),
    ('(d) setAndAllowWhileIdle', m('alarm_awi')),
    ('(e) WorkManager, 30 s initial delay', m('work')),
    ('(f) setExact with OnAlarmListener (no permission)', m('alarm_listener')),
    ('(f) foreground service, type shortService', m('fgs')),
    ('**final: exact alarm + timer** (with permission), first version', m('final', 'full', '2')),
    ('**final: exact alarm + timer** (with permission), as delivered', m('final', 'full', '3')),
    ('final, exact alarm only (timer removed)', m('final_notimer', 'full')),
    ('**final without permission: aimed inexact alarm + timer**, first version', m('final', 'noperm', '2')),
    ('**final without permission: aimed inexact alarm + timer**, as delivered', m('final', 'noperm', '3')),
    ('final without permission, alarm only (timer removed)', m('final_notimer', 'noperm')),
    ('final without permission, alarm only, another alarm forcing early delivery', m('final_decoy', 'noperm')),
]

a17 = lambda r: r['sdk'] == 37
d30 = lambda r: r['delayMs'] == 30000

table('Android 17, 30 s, app in the background, nothing else done (lateness in ms)', ROWS17, [
    ('Home pressed', lambda r: r['c'] == 'none' and r['bg'] == 'home'),
    ('Settings opened', lambda r: r['c'] == 'none' and r['bg'] == 'settings'),
    ('another app opened (the watcher)', lambda r: r['c'] == 'none' and r['bg'] == 'watch'),
    ('app left in front', lambda r: r['c'] == 'none' and r['bg'] == 'stay'),
], lambda r: a17(r) and d30(r))

table('Android 17, 30 s, harder conditions (lateness in ms)', ROWS17, [
    ('process killed: am kill at +8 s', lambda r: r['c'] == 'kill' and r.get('plan') != 'workkill'),
    ('task swiped away from Recents at +5 s', lambda r: r['c'] == 'swipe' and r.get('plan') != 'workkill'),
    ('cached and frozen (Home, Settings, other app)', lambda r: r['c'] == 'frozen'),
    ('frozen after the app\'s own second activity finished', lambda r: r['c'] == 'frozen-controls'),
    ('screen off', lambda r: r['c'] == 'screenoff'),
    ('screen off, PIN-locked', lambda r: r['c'] == 'locked'),
    ('forced deep Doze', lambda r: r['c'] == 'idle'),
    ('force-stopped at +8 s', lambda r: r['c'] == 'forcestop'),
], lambda r: a17(r) and d30(r))

table('Android 17, WorkManager left alone for 240 s after the process was gone', [ROWS17[6]], [
    ('am kill at +8 s', lambda r: r['c'] == 'kill'),
    ('swiped away at +5 s', lambda r: r['c'] == 'swipe'),
], lambda r: a17(r) and r.get('plan') == 'workkill')

table('Android 17, longer delays and power states (lateness in ms)', ROWS17, [
    ('120 s, Home only', lambda r: r['c'] == 'none' and r['delayMs'] == 120000),
    ('120 s, battery saver on', lambda r: r['c'] == 'batsaver'),
    ('120 s, battery usage "Restricted"', lambda r: r['c'] == 'restricted' and r['delayMs'] == 120000),
    ('65 s, battery usage "Restricted"', lambda r: r['c'] == 'restricted' and r['delayMs'] == 65000),
], a17)

ROWSOLD = [
    ('(a) Handler.postDelayed', m('handler')),
    ('(c) setExactAndAllowWhileIdle (no permission needed below API 31)', m('alarm_exact')),
    ('final: exact alarm + timer, first version', m('final', 'full', '2')),
    ('final: exact alarm + timer, as delivered', m('final', 'full', '3')),
]
for sdk, name in ((24, 'Android 7.0 (API 24): the clear writes an empty clip'), (28, 'Android 9 (API 28)')):
    table(f'{name}, 30 s (lateness in ms)', ROWSOLD, [
        ('Home / Settings / other app / in front', lambda r: r['c'] in ('none', 'controls')),
        ('am kill (effective)', lambda r: r['c'] == 'kill'),
        ('am kill refused: process lived on', lambda r: r['c'] == 'kill-refused'),
        ('SIGKILL at +8 s', lambda r: r['c'] == 'kill9'),
        ('force-stopped at +8 s', lambda r: r['c'] == 'forcestop'),
    ], lambda r, sdk=sdk: r['sdk'] == sdk and d30(r))

# ---------------------------------------------------------------- totals quoted in the text
print('\n### Totals\n')
by = defaultdict(list)
for r in runs:
    by[(r['sdk'], r['build'])].append(r)
print('runs per (sdk, build):', {k: len(v) for k, v in sorted(by.items())})


def worst(label, rf):
    rs = [r for r in runs if rf(r)]
    ok = [r for r in rs if r['result']['ok']]
    lates = sorted((r['result']['late'], r['run']) for r in ok)
    conds = OrderedDict()
    for r in rs:
        conds[r['c']] = conds.get(r['c'], 0) + 1
    print(f'{label}: {len(ok)}/{len(rs)} cleared; lateness min {lates[0][0] if lates else None}, '
          f'max {lates[-1][0] if lates else None} ({lates[-1][1] if lates else None}); conditions {dict(conds)}')


excl = ('restricted', 'forcestop', 'swipe-missed', 'kill-refused')
worst('A17 final with permission (build 3), all conditions except restricted and force-stop',
      lambda r: a17(r) and m('final', 'full', '3')(r) and r['c'] not in excl)
worst('A17 final with permission (builds 2+3), all conditions except restricted and force-stop',
      lambda r: a17(r) and m('final', 'full')(r) and r['c'] not in excl)
worst('A17 exact alarm alone (alarm_exact + final_notimer full), same',
      lambda r: a17(r) and (m('alarm_exact')(r) or m('final_notimer', 'full')(r)) and r['c'] not in excl)
worst('A17 no-permission final (final + final_notimer + final_decoy, noperm, builds 2+3), same',
      lambda r: a17(r) and r.get('app') == 'noperm' and r['mech'].startswith('final') and r['c'] not in excl)
worst('A17 no-permission final, build 3 only',
      lambda r: a17(r) and r.get('app') == 'noperm' and r['mech'].startswith('final') and r['build'] == '3' and r['c'] not in excl)
worst('A7.0 final + alarm_exact', lambda r: r['sdk'] == 24 and r['mech'] in ('final', 'alarm_exact') and r['c'] not in excl)
worst('A9 final + alarm_exact', lambda r: r['sdk'] == 28 and r['mech'] in ('final', 'alarm_exact') and r['c'] not in excl)
threw = [r['run'] for r in runs if (r['result'].get('threw'))]
print('runs in which the clear call threw:', threw)
w = [r for r in runs if r['result'].get('watchFirst')]
seen = [r for r in w if r['result']['watchFirst'] == 'VALUE']
print(f'watcher runs: {len(w)}; secret seen by the watcher before the clear: {len(seen)}')
both = [(r['result']['watchGoneDt'] - r['result']['dt']) for r in w if r['result']['ok'] and r['result'].get('watchGoneDt') is not None]
if both:
    print(f'watcher saw the clip gone {min(both)} to {max(both)} ms after the app logged its clear call ({len(both)} runs)')
early = [r['run'] for r in w if r['result'].get('watchGoneDt') is not None and r['result']['watchGoneDt'] < r['delayMs'] - 50]
print('runs where the watcher saw the clip gone BEFORE the requested delay:', early)
earlyc = [r['run'] for r in runs if r['result']['cleared'] and r['result']['late'] is not None and r['result']['late'] < 0]
print('runs where the app logged a clear before the requested delay:', earlyc)
pre = [r for r in runs if r['result'].get('probeBefore')]
print('runs with a look before the deadline:', len(pre), 'value still there in', sum(1 for r in pre if r['result']['probeBefore'] == 'VALUE'))
if '--csv' in sys.argv:
    print('\nrun,android,build,app,mech,bg,cond,delay_s,dt_ms,late_ms,via,threw,probe_after,watcher_gone_ms,frozen_seen,new_process,cleared_on_reopen_ms')
    for r in sorted(runs, key=lambda r: r.get('hostStart', '')):
        x = r['result']
        print(','.join(str(v) for v in (r['run'], {24: '7.0', 28: '9', 37: '17'}[r['sdk']], r['build'], r.get('app', 'full'), r['mech'], r['bg'], r['c'],
                                        r['delayMs'] // 1000, x['dt'], x['late'], x['via'], bool(x['threw']), x['probe'], x['watchGoneDt'],
                                        x['frozenSeen'], x['pidChanged'], x.get('clearOnReturnDt'))))
