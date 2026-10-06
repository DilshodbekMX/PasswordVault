#!/usr/bin/env python3
"""Host-side driver for the clipboard-clear lab.

One run: force-stop the app under test, launch it to copy a marked sensitive value and schedule a clear by
one mechanism, put it in the background, apply a condition, wait for the app's clear event, then read the
clipboard from ANOTHER app that has focus. Every run is appended to results/runs-<serial>.jsonl.
App events are JSON lines logged under tag PVClip (see Lab.kt).

Backgrounds   home        HOME key
              settings    Settings opened on top
              watch       the watcher app (another package) opened on top; it polls the clipboard every
                          50 ms with focus, so it is an independent witness of when the clip disappears
              chainwatch  HOME, Settings, then the watcher: the app under test is no longer the "previous"
                          app, becomes cached and is frozen by the cached-apps freezer after 10 s
              stay        the app stays in front
Conditions    none | kill (am kill at +8 s) | screenoff | idle (forced deep Doze) | restricted (battery
              usage "Restricted") | batsaver (battery saver on)

Usage: SERIAL=emulator-5560 python3 clipexp.py PLAN[:i/n] [PLAN...]     (i/n = run every n-th spec, offset i)
"""
import json
import os
import re
import subprocess
import sys
import threading
import time

SDK = '/home/asus/Android/Sdk'
SERIAL = os.environ.get('SERIAL', 'emulator-5560')
TAG = SERIAL[-2:]
ADB = [f'{SDK}/platform-tools/adb', '-s', SERIAL]
NS = 'com.example.pvlab.clip'
FLAVORS = {'full': NS, 'sea': NS + '.sea', 'noperm': NS + '.noperm'}
LAB = os.path.dirname(os.path.abspath(__file__))
OUT = os.path.join(LAB, 'results')
SHOTS = os.path.join(LAB, 'shots')
os.makedirs(OUT, exist_ok=True)
os.makedirs(SHOTS, exist_ok=True)
RUNS = os.path.join(OUT, f'runs-{SERIAL}.jsonl')
SDK_INT = 0
BUILD = os.environ.get('BUILD', '2')  # which build of the test app is installed (2 = first full build, 3 = final candidate)


def adb(*args, timeout=60):
    try:
        r = subprocess.run(ADB + list(args), capture_output=True, text=True, timeout=timeout, errors='replace')
        return (r.stdout + r.stderr).strip()
    except subprocess.TimeoutExpired:
        return 'TIMEOUT'


def sh(cmd, timeout=60):
    return adb('shell', cmd, timeout=timeout)


def say(*a):
    print(time.strftime('%H:%M:%S'), f'[{TAG}]', *a, flush=True)


def act(pkg, cls):
    return f'{pkg}/{NS}.{cls}'


class LogTail:
    """Follows logcat (tag PVClip) in a background thread, reconnecting if adb drops, and keeps the events."""

    def __init__(self, path):
        self.events = []
        self.lock = threading.Lock()
        self.seen = set()
        self.f = open(path, 'a')
        self.stop = False
        self.p = None
        threading.Thread(target=self._loop, daemon=True).start()

    def _loop(self):
        first = True
        while not self.stop:
            try:
                self.p = subprocess.Popen(ADB + ['logcat', '-v', 'raw', '-T', '1' if first else '300', '-s', 'PVClip:I'],
                                          stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True,
                                          errors='replace')
                first = False
                for line in self.p.stdout:
                    self._line(line)
            except Exception as ex:  # noqa
                say('logtail error', ex)
            if not self.stop:
                time.sleep(2.0)

    def _line(self, line):
        line = line.strip()
        if not line.startswith('{'):
            return
        with self.lock:
            if line in self.seen:
                return
            self.seen.add(line)
        self.f.write(line + '\n')
        self.f.flush()
        try:
            e = json.loads(line)
        except ValueError:
            return
        e['_host'] = time.time()
        with self.lock:
            self.events.append(e)

    def recover(self):
        """Re-reads the device log buffer; fills in anything the tail missed (adb hiccup)."""
        out = adb('logcat', '-d', '-v', 'raw', '-s', 'PVClip:I', timeout=30)
        for line in out.splitlines():
            self._line(line)

    def find(self, pred):
        with self.lock:
            for e in self.events:
                if pred(e):
                    return e
        return None

    def all(self, pred):
        with self.lock:
            return [e for e in self.events if pred(e)]

    def wait(self, pred, timeout):
        end = time.time() + timeout
        while True:
            e = self.find(pred)
            if e or time.time() >= end:
                return e
            time.sleep(0.1)


LOG = None


class Sampler(threading.Thread):
    """Samples pid, frozen flag and oom_score_adj of the app process."""

    def __init__(self, t0, pkg, period=2.0):
        super().__init__(daemon=True)
        self.t0, self.pkg, self.period = t0, pkg, period
        self.samples = []
        self.stop_flag = False

    def run(self):
        while not self.stop_flag:
            out = sh(f'P=$(pidof {self.pkg}); F=$(am isfrozen {self.pkg} 2>&1 | head -1); '
                     f'A=$(cat /proc/$P/oom_score_adj 2>/dev/null); echo "pid=$P frozen=$F adj=$A"', timeout=20)
            self.samples.append([round(time.time() - self.t0, 1), out.replace('\n', ' ')[:120]])
            end = time.time() + self.period
            while time.time() < end and not self.stop_flag:
                time.sleep(0.1)

    def stop(self):
        self.stop_flag = True
        self.join(timeout=30)


def ensure_device():
    for _ in range(90):
        if adb('get-state', timeout=10) == 'device' and sh('getprop sys.boot_completed', timeout=10).strip() == '1':
            return
        time.sleep(2)
    raise RuntimeError(f'{SERIAL} is not available')


def screen_state():
    out = sh('dumpsys power | grep -E "mWakefulness="')
    return ' '.join(out.split())


PIN = None  # set while the 'locked' condition is in use


def lock_state():
    out = sh('dumpsys trust 2>/dev/null | grep -o "deviceLocked=[01]" | head -1; '
             'dumpsys power | grep -o "mWakefulness=[A-Za-z]*" | head -1')
    return ' '.join(out.split())


def set_pin():
    global PIN
    PIN = '1111'
    say('set-pin:', sh(f'locksettings set-pin {PIN}').splitlines()[-1:])


def clear_pin():
    global PIN
    if PIN:
        say('clear pin:', sh(f'locksettings clear --old {PIN}').splitlines()[-1:])
        PIN = None


def wake_unlock():
    st = lock_state()
    if 'mWakefulness=Awake' in st and 'deviceLocked=1' not in st:
        return
    sh('input keyevent KEYCODE_WAKEUP')
    time.sleep(0.7)
    sh('wm dismiss-keyguard')
    time.sleep(1.2)
    if PIN:
        sh(f'input text {PIN}')
        sh('input keyevent 66')
        time.sleep(1.5)


def start_watch(other, rid, ms):
    sh(f'am start -W -f 0x10008000 -n {act(other, "ProbeActivity")} --es run {rid} --ez watch true --el watchMs {int(ms)}')


def background(bg, rid, other, watch_ms):
    if bg == 'home':
        sh('input keyevent KEYCODE_HOME')
    elif bg == 'settings':
        sh('am start -W -a android.settings.SETTINGS')
    elif bg == 'watch':
        start_watch(other, rid, watch_ms)
    elif bg == 'chainwatch':
        sh('input keyevent KEYCODE_HOME')
        time.sleep(1.0)
        sh('am start -W -a android.settings.SETTINGS')
        time.sleep(1.5)
        start_watch(other, rid, watch_ms)
    elif bg == 'stay':
        pass
    else:
        raise ValueError(bg)


def probe(rid, pkg, timeout=15):
    sh(f'am start -W -f 0x10008000 -n {act(pkg, "ProbeActivity")} --es run {rid}')
    return LOG.wait(lambda e: e.get('ev') == 'probe' and e.get('run') == rid, timeout)


def alarm_lines(pkg):
    return sh(f'dumpsys alarm | grep -A7 "Alarm{{.*{pkg}}}"')[:3000]


def job_lines(pkg):
    """The pending WorkManager job as JobScheduler sees it (constraints, readiness, run time)."""
    out = sh(f'dumpsys jobscheduler | grep -A34 "^  JOB .*@{pkg}/androidx" | head -40')
    keep = [l.strip() for l in out.splitlines() if re.search(
        r'JOB |Minimum latency|Required constraints|Satisfied constraints|Unsatisfied constraints|Uid:|'
        r'Standby bucket|Enqueue time|Run time|Restricted due|Ready:', l)]
    return ' || '.join(keep)[:2500]


def jobdelay_alarm():
    """JobScheduler's own alarm for the next job whose minimum latency expires."""
    out = sh('dumpsys alarm | grep -B1 -A5 "tag=.*job.delay" | head -16')
    keep = [l.strip() for l in out.splitlines() if re.search(r'Alarm\{|tag=|type=|policyWhenElapsed|whenElapsed=', l)]
    return ' || '.join(keep)[:1200]


def save(rec):
    rec['serial'] = SERIAL
    rec['sdk'] = SDK_INT
    rec['build'] = BUILD
    with open(RUNS, 'a') as f:
        f.write(json.dumps(rec) + '\n')


def sleep_until(t):
    d = t - time.time()
    if d > 0:
        time.sleep(d)


def shot(name):
    path = os.path.join(SHOTS, name)
    with open(path, 'wb') as f:
        f.write(subprocess.run(ADB + ['exec-out', 'screencap', '-p'], capture_output=True, timeout=30).stdout)
    return path


def ui_nodes(name):
    """uiautomator dump; returns the SystemUI clipboard nodes and every non-empty text on screen."""
    # --windows: every window, so that the SystemUI clipboard overlay is included (not only the focused app).
    sh('uiautomator dump --windows /sdcard/pvui.xml' if SDK_INT >= 33 else 'uiautomator dump /sdcard/pvui.xml', timeout=30)
    xml = adb('exec-out', 'cat', '/sdcard/pvui.xml', timeout=30)
    with open(os.path.join(SHOTS, name), 'w') as f:
        f.write(xml)
    out = []
    for m in re.finditer(r'<node [^>]*?>', xml):
        n = m.group(0)

        def g(k):
            mm = re.search(k + r'="([^"]*)"', n)
            return mm.group(1) if mm else ''
        if g('text') or g('content-desc') or 'clipboard' in g('resource-id'):
            out.append([g('package'), g('resource-id'), g('text'), g('content-desc'), g('bounds')])
    return out


def classify(p, value):
    if not p:
        return 'NOPROBE'
    if p.get('has') is False:
        return 'EMPTY'
    t = p.get('text')
    if t == value:
        return 'VALUE'
    if t == '':
        return 'EMPTYTEXT'
    return f'OTHER:{t}'


def find_clear(rid, copy):
    """The app's clear event for this run, normalised (mechanisms final* log 'final_clear' keyed by copyId)."""
    e = LOG.find(lambda e: e.get('ev') == 'clear' and e.get('run') == rid)
    if e:
        return e
    cid = copy.get('copyId')
    if not cid:
        return None
    f = LOG.find(lambda e: e.get('ev') == 'final_clear' and e.get('copyId') == cid)
    if not f:
        return None
    before = LOG.all(lambda e: e.get('ev') in ('final_timer', 'final_alarm') and e.get('copyId') == cid
                     and e.get('el', 0) <= f.get('el', 0))
    via = before[-1]['ev'].replace('final_', '') if before else '?'
    if f.get('outcome') == 'skipped':
        return {'ev': 'clear', 'pid': f['pid'], 'el': f['el'], 'dt': f['el'] - copy['copyEl'],
                'late': f['el'] - copy['copyEl'] - copy['delayMs'], 'dtUp': None, 'threw': None,
                'outcome': 'skipped', 'via': via, 'state': f.get('state'), '_host': f['_host']}
    return {'ev': 'clear', 'pid': f['pid'], 'el': f['el'], 'dt': f['el0'] - copy['copyEl'],
            'late': f['el0'] - copy['copyEl'] - copy['delayMs'], 'dtUp': f['up0'] - copy['copyUp'],
            'threw': f.get('threw'), 'outcome': f.get('outcome'), 'via': via, 'state': f.get('state'),
            'bgHas': f.get('bgHas'), '_host': f['_host']}


def collect(rec, rid, copy, pkg, other, t0):
    """Adds the per-run event summaries (watcher timeline, listener callbacks, restarts, final-path events)."""
    value, el0 = copy.get('value'), copy['copyEl']
    w = LOG.all(lambda e: e.get('ev') == 'watch' and e.get('run') == rid)
    rec['watch'] = [[e['el'] - el0, classify(e, value), e.get('n')] for e in w]
    rec['watchFocus'] = [[e['el'] - el0, e.get('focus')] for e in
                         LOG.all(lambda e: e.get('ev') == 'watch_focus' and e.get('run') == rid)]
    rec['restarts'] = [[e['el'] - el0, e['pid']] for e in
                       LOG.all(lambda e: e.get('ev') == 'process_start' and e.get('proc') == pkg
                               and e.get('el', 0) > el0 - 1500 and e.get('_host', 0) > t0 - 5)]
    rec['listener'] = [[e['el'] - el0, e.get('has'), e.get('label'), e.get('state')] for e in
                       LOG.all(lambda e: e.get('ev') == 'listener' and e.get('pkg') == pkg
                               and e.get('_host', 0) >= t0 - 1)]
    rec['fgs'] = [[e['el'] - el0, e['ev']] for e in
                  LOG.all(lambda e: str(e.get('ev', '')).startswith('fgs') and e.get('_host', 0) >= t0 - 1)]
    rec['decoy'] = [[e['el'] - el0, e['ev'], e.get('windowEnd') and e['windowEnd'] - el0] for e in
                    LOG.all(lambda e: e.get('ev') in ('decoy', 'decoy_set') and e.get('pkg') == pkg
                            and e.get('el', 0) >= el0 - 50 and e.get('_host', 0) >= t0 - 1)]
    cid = copy.get('copyId')
    if cid:
        rec['final'] = [[e['el'] - el0, e['ev'], e.get('kind') or e.get('outcome') or e.get('wait'), e.get('at'), e['pid']]
                        for e in LOG.all(lambda e: str(e.get('ev', '')).startswith('final_') and e.get('copyId') == cid)]


def run_once(mech, bg='home', cond='none', delay_ms=30000, app='full', probe_before=None, max_wait=None,
             plan='', sensitive=True, kill_at=8.0, trace_every=None, extra_args=''):
    pkg = FLAVORS[app]
    other = FLAVORS['noperm' if app != 'noperm' else 'full']
    rid = f'{mech}.{bg}.{cond}.{time.strftime("%H%M%S")}.{TAG}'
    rec = {'run': rid, 'mech': mech, 'bg': bg, 'cond': cond, 'delayMs': delay_ms, 'plan': plan, 'app': app,
           'pkg': pkg, 'hostStart': time.strftime('%Y-%m-%d %H:%M:%S')}
    say(f'--- run {rid} app={app} delay={delay_ms}')
    ensure_device()
    if max_wait is None:
        max_wait = delay_ms / 1000 + 40
    # Clean start: both apps stopped with their data cleared (cancels alarms and jobs, and also empties
    # WorkManager's database, which would otherwise re-schedule an unfinished work of an earlier run),
    # screen on, unlocked, at home.
    rec['clearOut'] = ' '.join(sh(f'pm clear {pkg}; pm clear {other}').split())
    if cond == 'locked' and not PIN:
        set_pin()
    if cond == 'restricted':
        rec['restrictOut'] = sh(f'cmd appops set {pkg} RUN_ANY_IN_BACKGROUND ignore; cmd appops get {pkg} RUN_ANY_IN_BACKGROUND')
    if cond == 'batsaver':
        rec['batsaverOut'] = sh('cmd battery unplug; cmd power set-mode 1; settings get global low_power')
    wake_unlock()
    sh('input keyevent KEYCODE_HOME')
    time.sleep(1.0)

    # 1. Copy with the app in front and focused.
    sh(f'am start -W -n {act(pkg, "MainActivity")} --es cmd copy --es mech {mech} --es run {rid} --el delayMs {delay_ms}'
       + ('' if sensitive else ' --ez sensitive false') + extra_args)
    copy = LOG.wait(lambda e: e.get('ev') == 'copy' and e.get('run') == rid, 15)
    if not copy:
        LOG.recover()
        copy = LOG.find(lambda e: e.get('ev') == 'copy' and e.get('run') == rid)
    sched = LOG.wait(lambda e: e.get('ev') == 'scheduled' and e.get('run') == rid, 5)
    rec['copy'], rec['scheduled'] = copy, sched
    if not copy:
        rec['error'] = 'no copy event'
        save(rec)
        say('  ERROR no copy event')
        return rec
    t0 = copy['_host']
    pid0 = copy['pid']
    value = copy.get('value')
    if mech.startswith('alarm') or mech.startswith('final'):
        rec['alarmAtSchedule'] = alarm_lines(pkg)
    if mech == 'work':
        rec['jobAtSchedule'] = job_lines(pkg)

    # 2. Background.
    sleep_until(t0 + 1.0)
    background(bg, rid, other, (max_wait + 6) * 1000)
    rec['bgAt'] = round(time.time() - t0, 1)
    sampler = Sampler(t0, pkg, period=6.0 if cond in ('screenoff', 'idle', 'locked') else 2.0)
    sampler.start()

    # 3. Condition.
    if cond in ('kill', 'kill9', 'forcestop'):
        sleep_until(t0 + kill_at)
        rec['pidBeforeKill'] = sh(f'pidof {pkg}')
        if cond == 'forcestop':
            # What Settings > Apps > Force stop does: kills the app AND removes its alarms and jobs.
            rec['killOut'] = sh(f'am force-stop {pkg}')
        elif cond == 'kill9':
            # SIGKILL from the app's own uid (the test app is debuggable): the process just dies, as when
            # the low-memory killer takes it. Used where "am kill" declines to kill (Android 7.0).
            rec['killOut'] = sh(f'run-as {pkg} kill -9 {rec["pidBeforeKill"]}')
        else:
            rec['killOut'] = sh(f'am kill {pkg}')
        time.sleep(1.0)
        rec['pidAfterKill'] = sh(f'pidof {pkg}')
        rec['alarmAfterKill'] = alarm_lines(pkg)
        rec['jobAfterKill'] = job_lines(pkg) if mech == 'work' else None
    elif cond == 'screenoff':
        sh('input keyevent KEYCODE_SLEEP')
        time.sleep(1.0)
        rec['screenAfterSleep'] = screen_state()
        rec['screenOffAt'] = round(time.time() - t0, 1)
    elif cond == 'locked':
        # Power key: screen off and, with a PIN set, locked at once.
        sh('input keyevent KEYCODE_POWER')
        time.sleep(1.5)
        rec['lockAfterPower'] = lock_state()
        rec['screenOffAt'] = round(time.time() - t0, 1)
    elif cond == 'swipe':
        # The user is at Home, opens Recents and swipes our app away.
        sleep_until(t0 + 5.0)
        rec['pidBeforeSwipe'] = sh(f'pidof {pkg}')
        sh('input keyevent KEYCODE_APP_SWITCH')
        time.sleep(1.8)
        sh('input swipe 540 1300 540 250 120')
        time.sleep(1.5)
        sh('input keyevent KEYCODE_HOME')
        time.sleep(0.6)
        rec['pidAfterSwipe'] = sh(f'pidof {pkg}')
        rec['recentsAfterSwipe'] = sh(f'dumpsys activity recents | grep -c "{pkg}/"')
        rec['alarmAfterSwipe'] = alarm_lines(pkg)
        rec['jobAfterSwipe'] = job_lines(pkg) if mech == 'work' else None
        rec['killLog'] = sh(f'logcat -d -v time -s ActivityManager:I | grep "Killing.*{pkg}/" | tail -1')
        rec['swipeDoneAt'] = round(time.time() - t0, 1)
        start_watch(other, rid, (max_wait + 6) * 1000)
    elif cond == 'idle':
        sh('dumpsys battery unplug')
        sh('input keyevent KEYCODE_SLEEP')
        time.sleep(1.0)
        rec['forceIdle'] = sh('dumpsys deviceidle force-idle')
        rec['idleState'] = sh('dumpsys deviceidle get deep') + ' / light=' + sh('dumpsys deviceidle get light')
        rec['screenAfterSleep'] = screen_state()
        rec['idleAt'] = round(time.time() - t0, 1)
        if mech.startswith('alarm') or mech.startswith('final'):
            rec['alarmInIdle'] = alarm_lines(pkg)

    if probe_before:
        # Same package on purpose: another app's probe would take our "previous app" slot.
        sleep_until(t0 + probe_before)
        rec['probeBefore'] = probe(rid + '-pre', pkg)
        rec['probeBeforeAt'] = round(time.time() - t0, 1)
        time.sleep(0.5)
        background('home' if bg not in ('settings',) else 'settings', rid, other, 0)

    # 4. Wait for the clear.
    end = t0 + max_wait
    clear = None
    next_trace = time.time() if trace_every else None
    rec['trace'] = []
    while time.time() < end:
        clear = find_clear(rid, copy)
        if clear:
            break
        if next_trace and time.time() >= next_trace:
            # What the system holds for this run while nothing else happens on the device.
            rec['trace'].append([round(time.time() - t0, 1), sh(f'pidof {pkg}'),
                                 job_lines(pkg) if mech == 'work' else alarm_lines(pkg),
                                 jobdelay_alarm() if mech == 'work' else ''])
            next_trace = time.time() + trace_every
        time.sleep(0.1)
    if not clear:
        LOG.recover()
        clear = find_clear(rid, copy)
    rec['clear'] = clear
    rec['clearSeenAt'] = round(time.time() - t0, 1)
    if cond == 'idle':
        rec['idleStateAtEnd'] = sh('dumpsys deviceidle get deep')
    if cond in ('screenoff', 'idle'):
        rec['screenAtEnd'] = screen_state()
    if cond == 'locked':
        rec['lockAtEnd'] = lock_state()
    if not clear and (mech.startswith('alarm') or mech.startswith('final')):
        rec['alarmPendingAtTimeout'] = alarm_lines(pkg)
    if not clear and mech == 'work':
        rec['jobPendingAtTimeout'] = job_lines(pkg)
    time.sleep(1.0)  # let the watcher log the change
    sampler.stop()
    rec['samples'] = sampler.samples

    # 5. Restore the device, then read the clipboard from the other app (the app under test is not woken).
    if cond == 'idle':
        rec['unforce'] = sh('dumpsys deviceidle unforce') + ' | ' + sh('dumpsys battery reset')
    if cond in ('screenoff', 'idle', 'locked'):
        wake_unlock()
        time.sleep(0.5)
        rec['lockAfterUnlock'] = lock_state()
    rec['probeAt'] = round(time.time() - t0, 1)
    rec['probe'] = probe(rid, other)
    if not clear:
        # Does the clear happen once the user returns to the app? (an in-process timer fires on thaw)
        sh(f'am start -W -n {act(pkg, "MainActivity")}')
        end = time.time() + 6
        late = None
        while time.time() < end and not late:
            late = find_clear(rid, copy)
            time.sleep(0.1)
        rec['clearOnReturn'] = late
        rec['returnAt'] = round(time.time() - t0, 1)
        rec['probeAfterReturn'] = probe(rid + '-ret', other)
    collect(rec, rid, copy, pkg, other, t0)
    if cond == 'restricted':
        sh(f'cmd appops set {pkg} RUN_ANY_IN_BACKGROUND allow')
    if cond == 'batsaver':
        sh('cmd power set-mode 0; cmd battery reset')

    c = clear
    # A poll that lands while our own probe is taking the focus away reads "empty": ignore those.
    gone = next((w for w in rec['watch'] if w[1] != 'VALUE' and w[0] < rec['probeAt'] * 1000 - 250), None)
    seen = [w for w in rec['watch'] if w[1] == 'VALUE']
    adjs = [int(m.group(1)) for s in rec['samples'] for m in [re.search(r'adj=(-?\d+)', s[1])] if m]
    rec['result'] = {
        'cleared': bool(c), 'dt': c['dt'] if c else None, 'late': c['late'] if c else None,
        'threw': c.get('threw') if c else None, 'via': c.get('via') if c else None,
        'outcome': c.get('outcome') if c else None,
        'pidChanged': (c['pid'] != pid0) if c else None,
        'probe': classify(rec['probe'], value),
        'watchFirst': rec['watch'][0][1] if rec['watch'] else None,
        'watchValueFrom': seen[0][0] if seen else None,
        'watchGoneDt': gone[0] if gone else None, 'watchGoneTo': gone[1] if gone else None,
        'frozenSeen': any('frozen=true' in s[1] for s in rec['samples']),
        'adjMax': max(adjs) if adjs else None,
        'clearOnReturnDt': rec['clearOnReturn']['dt'] if rec.get('clearOnReturn') else None,
        'probeAfterReturn': classify(rec.get('probeAfterReturn'), value) if 'probeAfterReturn' in rec else None,
        'probeBefore': classify(rec.get('probeBefore'), value) if 'probeBefore' in rec else None,
    }
    save(rec)
    sh('input keyevent KEYCODE_HOME')
    r = rec['result']
    say(f'  clear={"%d ms (late %+d)" % (r["dt"], r["late"]) if c else "NONE"} via={r["via"]} threw={r["threw"]} '
        f'pidChanged={r["pidChanged"]} probe={r["probe"]} watch={r["watchFirst"]}->{r["watchGoneTo"]}@{r["watchGoneDt"]} '
        f'frozen={r["frozenSeen"]} adjMax={r["adjMax"]} onReturn={r["clearOnReturnDt"]} pre={r["probeBefore"]}')
    return rec


# ---------------------------------------------------------------------------------------- special scenarios

def start_copy(pkg, mech, rid, delay_ms, sensitive=True, cmd='copy'):
    sh(f'am start -W -n {act(pkg, "MainActivity")} --es cmd {cmd} --es mech {mech} --es run {rid} --el delayMs {delay_ms}'
       + ('' if sensitive else ' --ez sensitive false'))
    return LOG.wait(lambda e: e.get('ev') == 'copy' and e.get('run') == rid, 15)


def special_overlay(app='full'):
    """Does the system show its own 'copied' overlay, and does the sensitive flag hide the value in it?"""
    pkg = FLAVORS[app]
    for sensitive in (True, False):
        rid = f'overlay-{"sensitive" if sensitive else "plain"}.{time.strftime("%H%M%S")}.{TAG}'
        say(f'--- {rid}')
        sh(f'am force-stop {pkg}')
        wake_unlock()
        sh('input keyevent KEYCODE_HOME')
        time.sleep(1.5)
        copy = start_copy(pkg, 'none', rid, 0, sensitive)
        rec = {'run': rid, 'plan': 'overlay', 'mech': 'none', 'bg': 'stay', 'cond': 'none', 'app': app,
               'sensitive': sensitive, 'copy': copy}
        t0 = copy['_host'] if copy else time.time()
        sleep_until(t0 + 0.9)
        rec['shot1'] = shot(f'{rid}-1s.png')
        rec['nodes1'] = ui_nodes(f'{rid}-1s-ui.xml')
        rec['nodes1At'] = round(time.time() - t0, 1)
        sleep_until(t0 + 5.0)
        rec['shot5'] = shot(f'{rid}-5s.png')
        sleep_until(t0 + 12.0)
        rec['shot12'] = shot(f'{rid}-12s.png')
        rec['nodes12'] = ui_nodes(f'{rid}-12s-ui.xml')
        rec['probe'] = probe(rid, pkg)
        rec['listener'] = [[e['el'] - copy['copyEl'], e.get('has'), e.get('label'), e.get('state')] for e in
                           LOG.all(lambda e: e.get('ev') == 'listener' and e.get('pkg') == pkg
                                   and e.get('_host', 0) >= t0 - 1)] if copy else None
        save(rec)
        clip = [n for n in rec['nodes1'] if 'clipboard' in n[1] or 'preview' in n[1]]
        say(f'  overlay nodes at {rec["nodes1At"]}s: {clip}')
        say(f'  nodes at 12 s with clipboard ids: {[n for n in rec["nodes12"] if "clipboard" in n[1]]}')
        sh('input keyevent KEYCODE_HOME')


def special_fgclear(app='full'):
    """clearPrimaryClip() while the app is in front: what the app, its listener and the screen show afterwards."""
    pkg = FLAVORS[app]
    rid = f'fgclear.{time.strftime("%H%M%S")}.{TAG}'
    say(f'--- {rid}')
    sh(f'am force-stop {pkg}')
    wake_unlock()
    sh('input keyevent KEYCODE_HOME')
    time.sleep(1.5)
    copy = start_copy(pkg, 'handler', rid, 9000)
    rec = {'run': rid, 'plan': 'fgclear', 'mech': 'handler', 'bg': 'stay', 'cond': 'none', 'app': app, 'copy': copy}
    t0 = copy['_host']
    clear = LOG.wait(lambda e: e.get('ev') == 'clear' and e.get('run') == rid, 15)
    rec['clear'] = clear
    time.sleep(0.6)
    rec['shotAfterClear'] = shot(f'{rid}-after-clear.png')
    rec['nodesAfterClear'] = ui_nodes(f'{rid}-after-clear-ui.xml')
    rec['probe'] = probe(rid, pkg)
    rec['listener'] = [[e['el'] - copy['copyEl'], e.get('has'), e.get('label'), e.get('state')] for e in
                       LOG.all(lambda e: e.get('ev') == 'listener' and e.get('pkg') == pkg and e.get('_host', 0) >= t0 - 1)]
    save(rec)
    say(f'  clear={clear and clear["dt"]} listener={rec["listener"]} probe={rec["probe"]}')
    say(f'  clipboard nodes after clear: {[n for n in rec["nodesAfterClear"] if "clipboard" in n[1]]}')
    sh('input keyevent KEYCODE_HOME')


def special_othercopy(mech='alarm_exact', app='full', front_at=None, delay_ms=30000):
    """The user copies something else in another app while ours is in the background. Does our listener hear
    of it? What does our scheduled clear do to the later copy? With front_at the user then returns to our app."""
    pkg = FLAVORS[app]
    other = FLAVORS['noperm' if app != 'noperm' else 'full']
    rid = f'othercopy-{mech}{"-front" if front_at else ""}.{time.strftime("%H%M%S")}.{TAG}'
    say(f'--- {rid} app={app}')
    ensure_device()
    sh(f'pm clear {pkg}; pm clear {other}')
    wake_unlock()
    sh('input keyevent KEYCODE_HOME')
    time.sleep(1.5)
    copy = start_copy(pkg, mech, rid, delay_ms)
    rec = {'run': rid, 'plan': 'othercopy', 'mech': mech, 'bg': 'home', 'cond': 'front' if front_at else 'none',
           'app': app, 'pkg': pkg, 'delayMs': delay_ms, 'copy': copy}
    if not copy:
        rec['error'] = 'no copy'
        save(rec)
        return
    t0 = copy['_host']
    sleep_until(t0 + 1.0)
    sh('input keyevent KEYCODE_HOME')
    sleep_until(t0 + 8.0)
    ocopy = start_copy(other, 'none', rid + '-o', 0, cmd='copyother')
    rec['otherCopy'] = ocopy
    rec['otherCopyDt'] = (ocopy['copyEl'] - copy['copyEl']) if ocopy else None
    if front_at:
        sleep_until(t0 + front_at)
        sh(f'am start -W -n {act(pkg, "MainActivity")}')
        rec['frontAt'] = round(time.time() - t0, 1)
    else:
        sleep_until(t0 + 10.5)
        start_watch(other, rid, (delay_ms / 1000 - 10.5 + 9) * 1000)
    end = t0 + delay_ms / 1000 + 8
    ev = None
    while time.time() < end and not ev:
        ev = find_clear(rid, copy) or LOG.find(lambda e: e.get('ev') == 'peek' and e.get('run') == rid)
        time.sleep(0.1)
    rec['clearOrPeek'] = ev
    time.sleep(1.5)
    rec['probe'] = probe(rid, other)
    collect(rec, rid, copy, pkg, other, t0)
    ovalue = ocopy.get('value') if ocopy else None
    rec['watchOther'] = [[e['el'] - copy['copyEl'], classify(e, ovalue)] for e in
                         LOG.all(lambda e: e.get('ev') == 'watch' and e.get('run') == rid)]
    rec['result'] = {
        'listenerCallbacksAfterBackground': [x for x in rec['listener'] if x[0] > 2000],
        'probeIsOtherValue': classify(rec['probe'], ovalue),
        'event': (ev.get('ev'), ev.get('outcome'), ev.get('dt'), ev.get('has'), ev.get('text'), ev.get('desc')) if ev else None,
    }
    save(rec)
    say(f'  otherCopy at {rec["otherCopyDt"]} ms; listener callbacks of the first app after +2 s: '
        f'{rec["result"]["listenerCallbacksAfterBackground"]}; all={rec["listener"]}')
    say(f'  event={rec["result"]["event"]} watch(other value)={rec["watchOther"]} final probe={rec["result"]["probeIsOtherValue"]}')
    sh('input keyevent KEYCODE_HOME')


def special_recopy(app='full'):
    """A second copy 10 s after the first (user name, then password): the first deadline must pass without a
    clear, and the clear must come 30 s after the SECOND copy."""
    pkg = FLAVORS[app]
    other = FLAVORS['noperm' if app != 'noperm' else 'full']
    rid = f'recopy.{time.strftime("%H%M%S")}.{TAG}'
    say(f'--- {rid} app={app}')
    ensure_device()
    sh(f'pm clear {pkg}; pm clear {other}')
    wake_unlock()
    sh('input keyevent KEYCODE_HOME')
    time.sleep(1.5)
    c1 = start_copy(pkg, 'final', rid, 30000)
    rec = {'run': rid, 'plan': 'recopy', 'mech': 'final', 'bg': 'watch', 'cond': 'recopy', 'app': app, 'pkg': pkg, 'copy1': c1}
    t0 = c1['_host']
    sleep_until(t0 + 1.0)
    sh('input keyevent KEYCODE_HOME')
    sleep_until(t0 + 10.0)
    sh(f'am start -W --activity-single-top -n {act(pkg, "MainActivity")} --es cmd copy --es mech final --es run {rid}-b --el delayMs 30000')
    c2 = LOG.wait(lambda e: e.get('ev') == 'copy' and e.get('run') == rid + '-b', 15)
    rec['copy2'] = c2
    rec['secondCopyAt'] = c2['copyEl'] - c1['copyEl'] if c2 else None
    rec['alarmAfterSecondCopy'] = alarm_lines(pkg)
    sleep_until(t0 + 12.0)
    start_watch(other, rid, 42000)
    end = t0 + 56
    done = None
    while time.time() < end and not done and c2:
        done = LOG.find(lambda e: e.get('ev') == 'final_clear' and e.get('copyId') == c2.get('copyId'))
        time.sleep(0.1)
    time.sleep(1.2)
    el1 = c1['copyEl']
    rec['eventsFirstCopy'] = [[e['el'] - el1, e['ev'], e.get('outcome')] for e in
                              LOG.all(lambda e: str(e.get('ev', '')).startswith('final_') and e.get('copyId') == c1.get('copyId')
                                      and e.get('ev') != 'final_sched')]
    rec['eventsSecondCopy'] = [[e['el'] - el1, e['ev'], e.get('outcome') or e.get('kind') or e.get('wait')] for e in
                               LOG.all(lambda e: c2 and str(e.get('ev', '')).startswith('final_') and e.get('copyId') == c2.get('copyId'))]
    v2 = c2.get('value') if c2 else None
    rec['watchSecondValue'] = [[e['el'] - el1, classify(e, v2)] for e in
                               LOG.all(lambda e: e.get('ev') == 'watch' and e.get('run') == rid)]
    rec['probe'] = probe(rid, other)
    rec['clearAfterSecondCopyMs'] = (done['el0'] - c2['copyEl']) if done and c2 else None
    save(rec)
    say(f'  second copy at +{rec["secondCopyAt"]} ms; events of the FIRST copy after that: {rec["eventsFirstCopy"]}')
    say(f'  second copy: {rec["eventsSecondCopy"]}; cleared {rec["clearAfterSecondCopyMs"]} ms after the second copy')
    say(f'  watcher (second value): {rec["watchSecondValue"]}; probe={classify(rec["probe"], v2)}')
    say(f'  alarm after second copy: {" ".join(rec["alarmAfterSecondCopy"].split())[:230]}')
    sh('input keyevent KEYCODE_HOME')


def special_clearnow(app='full'):
    """clear(context, null) 6 s after the copy: the clipboard is emptied at once and the pending alarm and timer
    are cancelled, so that what another app copies afterwards is NOT wiped at the old deadline."""
    pkg = FLAVORS[app]
    other = FLAVORS['noperm' if app != 'noperm' else 'full']
    rid = f'clearnow.{time.strftime("%H%M%S")}.{TAG}'
    say(f'--- {rid} app={app}')
    ensure_device()
    sh(f'pm clear {pkg}; pm clear {other}')
    wake_unlock()
    sh('input keyevent KEYCODE_HOME')
    time.sleep(1.5)
    c1 = start_copy(pkg, 'final', rid, 30000)
    rec = {'run': rid, 'plan': 'clearnow', 'mech': 'final', 'bg': 'stay', 'cond': 'clearnow', 'app': app, 'pkg': pkg, 'copy1': c1}
    t0 = c1['_host']
    rec['alarmBefore'] = alarm_lines(pkg)
    sleep_until(t0 + 6.0)
    sh(f'am start -W --activity-single-top -n {act(pkg, "MainActivity")} --es cmd clearnow')
    ev = LOG.wait(lambda e: e.get('ev') == 'clearnow' and e.get('pkg') == pkg and e.get('_host', 0) > t0, 8)
    rec['clearnow'] = ev
    rec['clearNowAt'] = ev['el'] - c1['copyEl'] if ev else None
    time.sleep(0.5)
    rec['alarmAfter'] = alarm_lines(pkg)
    rec['probeAfterClear'] = probe(rid + '-a', other)
    sleep_until(t0 + 10.0)
    oc = start_copy(other, 'none', rid + '-o', 0, cmd='copyother')
    rec['otherCopyAt'] = oc['copyEl'] - c1['copyEl'] if oc else None
    sleep_until(t0 + 12.0)
    start_watch(other, rid, 27000)
    sleep_until(t0 + 37.0)
    el1 = c1['copyEl']
    rec['eventsAfterClearNow'] = [[e['el'] - el1, e['ev'], e.get('outcome')] for e in
                                  LOG.all(lambda e: e.get('ev') in ('final_timer', 'final_alarm', 'final_clear')
                                          and e.get('pkg') == pkg and e.get('_host', 0) > t0 and (not ev or e.get('el', 0) > ev['el']))]
    ov = oc.get('value') if oc else None
    rec['watchOtherValue'] = [[e['el'] - el1, classify(e, ov)] for e in
                              LOG.all(lambda e: e.get('ev') == 'watch' and e.get('run') == rid)]
    rec['probeEnd'] = probe(rid, other)
    save(rec)
    say(f'  clear(null) at +{rec["clearNowAt"]} ms returned {ev and ev.get("cleared")}; clipboard then: {classify(rec["probeAfterClear"], c1.get("value"))}')
    say(f'  alarm before: {" ".join(rec["alarmBefore"].split())[:120]} | alarm after: {rec["alarmAfter"]!r}')
    say(f'  other app copied at +{rec["otherCopyAt"]} ms; events of our app after the manual clear: {rec["eventsAfterClearNow"]}')
    say(f'  watcher (other value): {rec["watchOtherValue"]}; at +37 s the other value is: {classify(rec["probeEnd"], ov)}')
    sh('input keyevent KEYCODE_HOME')


def special_status():
    """canScheduleExactAlarms() and what scheduling an exact alarm does, per manifest variant."""
    for app in ('full', 'sea', 'noperm'):
        pkg = FLAVORS[app]
        rid = f'status-{app}.{time.strftime("%H%M%S")}.{TAG}'
        say(f'--- {rid}')
        rec = {'run': rid, 'plan': 'status', 'mech': 'none', 'bg': 'stay', 'cond': 'none', 'app': app, 'pkg': pkg}
        sh(f'am force-stop {pkg}')
        wake_unlock()

        def status(tag):
            sh(f'am start -W -n {act(pkg, "MainActivity")} --es cmd status --es tag {tag}')
            return LOG.wait(lambda e: e.get('ev') == 'status' and e.get('tag') == tag and e.get('pkg') == pkg, 10)

        def try_sched(mech, tag):
            sh(f'am force-stop {pkg}')
            r = f'{rid}-{mech}-{tag}'
            start_copy(pkg, mech, r, 30000)
            s = LOG.wait(lambda e: e.get('ev') == 'scheduled' and e.get('run') == r, 5)
            lines = alarm_lines(pkg)
            sh(f'am force-stop {pkg}')
            return {'scheduled': s, 'alarm': lines}

        rec['status'] = status(rid)
        rec['requested'] = sh(f'dumpsys package {pkg} | grep -E "EXACT_ALARM"')
        rec['appop'] = sh(f'cmd appops get {pkg} SCHEDULE_EXACT_ALARM')
        rec['exact'] = try_sched('alarm_exact', 'a')
        rec['listener'] = try_sched('alarm_listener', 'a')
        rec['final'] = try_sched('final', 'a')
        if app == 'sea':
            rec['grantOut'] = sh(f'cmd appops set {pkg} SCHEDULE_EXACT_ALARM allow; cmd appops get {pkg} SCHEDULE_EXACT_ALARM')
            rec['statusAfterGrant'] = status(rid + '-g')
            rec['exactAfterGrant'] = try_sched('alarm_exact', 'g')
            rec['resetOut'] = sh(f'cmd appops set {pkg} SCHEDULE_EXACT_ALARM default; cmd appops get {pkg} SCHEDULE_EXACT_ALARM')
        save(rec)
        st = rec['status'] or {}
        say(f'  canScheduleExactAlarms={st.get("canScheduleExactAlarms")} bucket={st.get("standbyBucket")} '
            f'bgRestricted={st.get("bgRestricted")}')
        say(f'  requested: {" ".join(rec["requested"].split())}')
        say(f'  appop: {rec["appop"]}')
        for k in ('exact', 'listener', 'final', 'exactAfterGrant'):
            if k in rec:
                s = rec[k]['scheduled'] or {}
                al = re.findall(r'(window=\S+|exactAllowReason=\S+|flags=\S+)', rec[k]['alarm'])
                say(f'  {k}: threw={s.get("threw")} info={s.get("info")} alarm={al}')
        if app == 'sea':
            say(f'  after grant: canScheduleExactAlarms={(rec["statusAfterGrant"] or {}).get("canScheduleExactAlarms")}')
    sh('input keyevent KEYCODE_HOME')


# ------------------------------------------------------------------------------------------- plans

def P(mech, bg='home', cond='none', **kw):
    return (run_once, dict(mech=mech, bg=bg, cond=cond, **kw))


REQUIRED = ['handler', 'alarm_set', 'alarm_exact', 'alarm_window', 'alarm_awi', 'work']


def plan_base():
    out = []
    for _ in range(2):
        for bg in ('home', 'settings', 'watch'):
            for m in REQUIRED + ['coroutine', 'final']:
                out.append(P(m, bg, plan='base'))
    return out


def plan_controls():
    return [P(m, 'home', probe_before=12, plan='controls') for m in REQUIRED + ['final']]


def plan_extra():
    out = []
    for bg in ('home', 'watch', 'settings'):
        out += [P('alarm_listener', bg, plan='extra'), P('fgs', bg, plan='extra'),
                P('final_notimer', bg, plan='extra'),
                P('final', bg, app='noperm', plan='extra'),
                P('final_notimer', bg, app='noperm', plan='extra'),
                P('final_notimer', bg, app='noperm', plan='extra')]
    return out


def plan_special():
    return [(special_status, {}), (special_overlay, {}), (special_fgclear, {}),
            (special_othercopy, dict(mech='alarm_exact')), (special_othercopy, dict(mech='alarm_peek')),
            (special_othercopy, dict(mech='final')), (special_othercopy, dict(mech='final', front_at=14))]


def mix(items):
    """[(mech, app, n)] -> list of (mech, app) with the repeats spread out."""
    out, k = [], 0
    while any(n > k for _, _, n in items):
        out += [(m, a) for m, a, n in items if n > k]
        k += 1
    return out


def plan_kill():
    items = [('alarm_exact', 'full', 5), ('final', 'full', 3), ('alarm_awi', 'full', 3), ('work', 'full', 3),
             ('final', 'noperm', 3), ('final_notimer', 'noperm', 3), ('handler', 'full', 2),
             ('alarm_listener', 'full', 1), ('coroutine', 'full', 1)]
    return [P(m, 'watch', 'kill', app=a, plan='kill') for m, a in mix(items)]


def plan_freeze():
    items = [('alarm_exact', 'full', 5), ('final', 'full', 3), ('alarm_awi', 'full', 3), ('work', 'full', 3),
             ('final', 'noperm', 3), ('final_notimer', 'noperm', 3), ('handler', 'full', 3),
             ('alarm_listener', 'full', 2), ('coroutine', 'full', 1), ('alarm_set', 'full', 1)]
    return [P(m, 'chainwatch', 'none', app=a, plan='freeze') for m, a in mix(items)]


def plan_screenoff():
    items = [('alarm_exact', 'full', 5), ('final', 'full', 3), ('alarm_awi', 'full', 3), ('work', 'full', 3),
             ('final', 'noperm', 2), ('final_notimer', 'noperm', 3), ('handler', 'full', 3)]
    return [P(m, 'home', 'screenoff', app=a, plan='screenoff') for m, a in mix(items)]


def plan_swipe():
    items = [('alarm_exact', 'full', 3), ('final', 'full', 3), ('final_notimer', 'noperm', 3), ('work', 'full', 2),
             ('handler', 'full', 2), ('alarm_awi', 'full', 1)]
    return [P(m, 'home', 'swipe', app=a, plan='swipe') for m, a in mix(items)]


def plan_locked():
    items = [('alarm_exact', 'full', 3), ('final', 'full', 2), ('final_notimer', 'noperm', 2), ('handler', 'full', 2),
             ('work', 'full', 1)]
    return [P(m, 'home', 'locked', app=a, plan='locked') for m, a in mix(items)]


def plan_verify():
    """The final candidate (build 3) through every condition. v = plan tag."""
    v = 'verify'
    out = []
    for bg in ('home', 'settings', 'watch', 'home', 'settings', 'watch', 'stay'):
        out.append(P('final', bg, plan=v))
    for bg in ('home', 'watch', 'settings', 'watch', 'home'):
        out.append(P('final', bg, app='noperm', plan=v))
    for bg in ('home', 'watch', 'watch'):
        out.append(P('final_notimer', bg, app='noperm', plan=v))
    out.append(P('final_notimer', 'watch', plan=v))
    # The clear alarm of the no-permission path delivered early by another app's alarm:
    for ms in (10900, 13700, 10900, 13700, 10900):
        out.append(P('final_decoy', 'watch', app='noperm', plan=v + '-decoy', extra_args=f' --el decoyMs {ms}'))
    for ms in (10900, 13700):
        out.append(P('final_decoy', 'watch', 'kill', app='noperm', plan=v + '-decoy', extra_args=f' --el decoyMs {ms}'))
    for m, a, n in [('final', 'full', 3), ('final', 'noperm', 2), ('final_notimer', 'noperm', 2)]:
        out += [P(m, 'watch', 'kill', app=a, plan=v)] * n
        out += [P(m, 'chainwatch', app=a, plan=v + '-freeze')] * n
    for m, a, n in [('final', 'full', 2), ('final_notimer', 'noperm', 2)]:
        out += [P(m, 'home', 'screenoff', app=a, plan=v)] * n
        out += [P(m, 'home', 'swipe', app=a, plan=v)] * n
    out += [(special_othercopy, dict(mech='final')), (special_othercopy, dict(mech='final', front_at=14)),
            (special_othercopy, dict(mech='final', front_at=14, app='noperm'))]
    return out


def plan_verify_locked():
    return [P('final', 'home', 'locked', plan='verify'), P('final_notimer', 'home', 'locked', app='noperm', plan='verify'),
            P('final', 'home', 'locked', plan='verify')]


def plan_old2():
    """Android 7.0 / 9 with the final candidate (build 3). The kill is done on a cached app (chainwatch)."""
    v = 'old2'
    out = [P('final', 'home', plan=v), P('final', 'watch', plan=v), P('final', 'settings', plan=v),
           P('alarm_exact', 'watch', plan=v), P('handler', 'watch', plan=v), P('final', 'stay', plan=v),
           P('final', 'chainwatch', 'kill', plan=v + '-kill', kill_at=10.0),
           P('final', 'chainwatch', 'kill', plan=v + '-kill', kill_at=10.0),
           P('alarm_exact', 'chainwatch', 'kill', plan=v + '-kill', kill_at=10.0),
           P('handler', 'chainwatch', 'kill', plan=v + '-kill', kill_at=10.0),
           P('final', 'home', probe_before=12, plan=v + '-controls'),
           (special_othercopy, dict(mech='final')), (special_othercopy, dict(mech='alarm_peek')),
           (special_othercopy, dict(mech='alarm_exact'))]
    return out


def plan_forcestop():
    return [P('final', 'watch', 'forcestop', plan='forcestop'), P('alarm_exact', 'watch', 'forcestop', plan='forcestop'),
            P('final_notimer', 'watch', 'forcestop', app='noperm', plan='forcestop')]


def plan_api():
    return [(special_recopy, {}), (special_clearnow, {}), (special_recopy, dict(app='noperm')),
            (special_clearnow, dict(app='noperm')), (special_recopy, {})]


def plan_old3():
    return [P('final', 'watch', 'kill9', plan='old3-kill9'), P('alarm_exact', 'watch', 'kill9', plan='old3-kill9'),
            P('final', 'watch', 'kill9', plan='old3-kill9'), P('handler', 'watch', 'kill9', plan='old3-kill9')]


def plan_overlay():
    return [(special_overlay, {}), (special_fgclear, {})]


def plan_workkill():
    """WorkManager after the process is gone, left alone for four minutes (no activity launches meanwhile)."""
    return [P('work', 'watch', 'kill', max_wait=240, trace_every=15, plan='workkill'),
            P('work', 'home', 'swipe', max_wait=240, trace_every=15, plan='workkill'),
            P('work', 'watch', 'kill', max_wait=240, trace_every=15, plan='workkill')]


def plan_long():
    """120 s delay with nothing but HOME: the app's own decay from 'previous' to cached to frozen."""
    out = []
    for m, a in [('handler', 'full'), ('alarm_exact', 'full'), ('final', 'full'), ('final_notimer', 'noperm'),
                 ('work', 'full'), ('alarm_awi', 'full'), ('final', 'noperm')]:
        out.append(P(m, 'home', 'none', app=a, delay_ms=120000, max_wait=215 if m == 'alarm_awi' else 165, plan='long'))
    return out


def plan_power():
    out = []
    for m, a in [('alarm_exact', 'full'), ('alarm_set', 'full'), ('final_notimer', 'noperm'), ('alarm_awi', 'full'),
                 ('work', 'full'), ('handler', 'full'), ('final', 'full')]:
        out.append(P(m, 'home', 'idle', app=a, max_wait=100, plan='idle'))
    for m, a in [('alarm_exact', 'full'), ('handler', 'full'), ('final', 'full'), ('final_notimer', 'noperm'),
                 ('work', 'full')]:
        out.append(P(m, 'home', 'restricted', app=a, delay_ms=120000, max_wait=165, plan='restricted-long'))
    for m, a in [('handler', 'full'), ('alarm_exact', 'full'), ('final', 'full'), ('final_notimer', 'noperm')]:
        out.append(P(m, 'home', 'restricted', app=a, delay_ms=65000, max_wait=100, plan='restricted-65'))
    for m, a in [('alarm_exact', 'full'), ('alarm_set', 'full'), ('final_notimer', 'noperm'), ('final', 'full'),
                 ('work', 'full')]:
        out.append(P(m, 'home', 'batsaver', app=a, delay_ms=120000, max_wait=165, plan='batsaver-long'))
    return out


def plan_old():
    """Android 7.0 / 9: the two most promising mechanisms plus the in-process timer."""
    out = []
    for bg in ('home', 'watch'):
        for m in ('final', 'alarm_exact', 'handler'):
            out.append(P(m, bg, plan='old'))
    out += [P('final', 'watch', 'kill', plan='old-kill'), P('alarm_exact', 'watch', 'kill', plan='old-kill'),
            P('final', 'home', probe_before=12, plan='old-controls'),
            (special_othercopy, dict(mech='final')), (special_othercopy, dict(mech='alarm_peek')),
            (special_othercopy, dict(mech='alarm_exact'))]
    return out


def plan_smoke():
    return [P('handler', 'home', delay_ms=8000, max_wait=20, plan='smoke'),
            P('final', 'watch', delay_ms=8000, max_wait=20, plan='smoke'),
            P('final_notimer', 'watch', app='noperm', delay_ms=12000, max_wait=30, plan='smoke'),
            P('alarm_exact', 'chainwatch', delay_ms=8000, max_wait=20, plan='smoke'),
            P('work', 'watch', 'kill', delay_ms=10000, max_wait=40, kill_at=4.0, plan='smoke'),
            P('handler', 'watch', 'kill', delay_ms=10000, max_wait=14, kill_at=4.0, plan='smoke')]


PLANS = {'smoke': plan_smoke, 'base': plan_base, 'controls': plan_controls, 'extra': plan_extra,
         'special': plan_special, 'kill': plan_kill, 'freeze': plan_freeze, 'screenoff': plan_screenoff,
         'long': plan_long, 'power': plan_power, 'old': plan_old, 'swipe': plan_swipe, 'locked': plan_locked, 'workkill': plan_workkill, 'overlay': plan_overlay, 'verify': plan_verify,
         'verify_locked': plan_verify_locked, 'old2': plan_old2, 'old3': plan_old3, 'forcestop': plan_forcestop, 'api': plan_api}


def main():
    global LOG, SDK_INT
    ensure_device()
    SDK_INT = int(sh('getprop ro.build.version.sdk').strip() or 0)
    LOG = LogTail(os.path.join(OUT, f'pvclip-events-{SERIAL}.log'))
    time.sleep(1.0)
    say('device sdk', SDK_INT, 'screen:', screen_state(), 'installed:',
        ' '.join(sorted(sh('pm list packages com.example.pvlab.clip').split())))
    for arg in sys.argv[1:]:
        name, _, shard = arg.partition(':')
        i, n = (int(x) for x in shard.split('/')) if shard else (0, 1)
        specs = [s for k, s in enumerate(PLANS[name]()) if k % n == i]
        say(f'===== plan {name} shard {i}/{n}: {len(specs)} runs')
        for fn, kw in specs:
            try:
                fn(**kw)
            except Exception as ex:  # keep going: one broken run must not lose the rest
                say(f'  EXCEPTION in {fn.__name__} {kw}: {ex!r}')
                time.sleep(5)
    clear_pin()
    say('===== all plans done')
    LOG.stop = True
    if LOG.p:
        LOG.p.terminate()


if __name__ == '__main__':
    main()
