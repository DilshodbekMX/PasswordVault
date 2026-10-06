
### Android 17, 30 s, app in the background, nothing else done (lateness in ms)

| mechanism | Home pressed | Settings opened | another app opened (the watcher) | app left in front |
|---|---|---|---|---|
| (a) Handler.postDelayed | 2/2: +25 to +26 | 2/2: +3 to +27 | 2/2: +7 to +12 | - |
| (a) coroutine delay | 2/2: +2 to +3 | 2/2: +2 to +7 | 2/2: +3 to +28 | - |
| (b) set(), inexact | 2/2: +22495 to +22499 | 2/2: +22499 to +22500 | 2/2: +22499 to +22502 | - |
| (c) setExactAndAllowWhileIdle + USE_EXACT_ALARM | 2/2: +2 | 2/2: +1 to +4 | 2/2: +1 to +2 | - |
| (d) setWindow(5 s) | 2/2: +22497 to +22504 | 2/2: +22500 to +22502 | 2/2: +21746 to +22500 | - |
| (d) setAndAllowWhileIdle | 2/2: +22499 to +22501 | 2/2: +22498 to +22502 | 2/2: +22500 | - |
| (e) WorkManager, 30 s initial delay | 2/2: +33 to +34 | 2/2: +28 to +39 | 2/2: +23 to +46 | - |
| (f) setExact with OnAlarmListener (no permission) | 1/1: +1 | 1/1: +0 | 1/1: +0 | - |
| (f) foreground service, type shortService | 1/1: +15 | 1/1: +25 | 1/1: +13 | - |
| **final: exact alarm + timer** (with permission), first version | 2/2: +26 to +30 | 2/2: +20 to +27 | 2/2: +26 to +28 | - |
| **final: exact alarm + timer** (with permission), as delivered | 3/3: +16 to +31 | 3/3: +6 to +27 | 2/2: +27 | 1/1: +22 |
| final, exact alarm only (timer removed) | 1/1: +4 | 1/1: +4 | 2/2: +4 to +8 | - |
| **final without permission: aimed inexact alarm + timer**, first version | 1/1: +3 | 1/1: +27 | 1/1: +27 | - |
| **final without permission: aimed inexact alarm + timer**, as delivered | 2/2: +16 to +28 | 1/1: +3 | 3/3: +6 to +26 | - |
| final without permission, alarm only (timer removed) | 4/4: +2 to +11 | 2/2: +3 | 4/4: +1 to +3 | - |
| final without permission, alarm only, another alarm forcing early delivery | - | - | 7/7: +7 to +25 | - |

### Android 17, 30 s, harder conditions (lateness in ms)

| mechanism | process killed: am kill at +8 s | task swiped away from Recents at +5 s | cached and frozen (Home, Settings, other app) | frozen after the app's own second activity finished | screen off | screen off, PIN-locked | forced deep Doze | force-stopped at +8 s |
|---|---|---|---|---|---|---|---|---|
| (a) Handler.postDelayed | 0/2: 2 never | 0/2: 2 never | 0/3: 3 never (3 on reopen) | 0/1: 1 never (1 on reopen) | 3/3: +26 to +27 | 2/2: +26 to +27 | 1/1: +25 | - |
| (a) coroutine delay | 0/1: 1 never | - | 0/1: 1 never (1 on reopen) | - | - | - | - | - |
| (b) set(), inexact | - | - | 1/1: +22503 | 1/1: +22503 | - | - | 0/1: 1 never (1 on reopen) | - |
| (c) setExactAndAllowWhileIdle + USE_EXACT_ALARM | 5/5: +307 to +369 | 2/2: +405 to +408 | 5/5: +3 to +11 | 1/1: +22 | 5/5: +2 to +6 | 3/3: +3 to +9 | 1/1: +2 | 0/1: 1 never |
| (d) setWindow(5 s) | - | - | - | 1/1: +22507 | - | - | - | - |
| (d) setAndAllowWhileIdle | 3/3: +22813 to +22898 | 1/1: +22838 | 3/3: +22503 to +22508 | 1/1: +22525 | 3/3: +22501 to +22505 | - | 1/1: +22504 | - |
| (e) WorkManager, 30 s initial delay | 3/3: +1224 to +22856 | 0/2: 2 never (2 on reopen) | 3/3: +22528 to +36926 | 1/1: +13535 | 3/3: +25 to +39 | 1/1: +51 | 1/1: +49 | - |
| (f) setExact with OnAlarmListener (no permission) | 0/1: 1 never | - | 0/2: 2 never | - | - | - | - | - |
| **final: exact alarm + timer** (with permission), first version | 3/3: +309 to +346 | 3/3: +323 to +363 | 3/3: +7 to +11 | 1/1: +14 | 3/3: +19 to +26 | 2/2: +7 to +28 | - | - |
| **final: exact alarm + timer** (with permission), as delivered | 4/4: +329 to +463 | 1/1: +343 | 4/4: +4 to +8 | - | 2/2: +14 to +18 | 2/2: +23 to +28 | 1/1: +27 | 0/1: 1 never |
| **final without permission: aimed inexact alarm + timer**, first version | 3/3: +297 to +362 | - | 3/3: +4 to +6 | - | 2/2: +28 to +30 | - | - | - |
| **final without permission: aimed inexact alarm + timer**, as delivered | 2/2: +369 to +447 | - | 3/3: +6 to +13 | - | - | - | - | - |
| final without permission, alarm only (timer removed) | 5/5: +310 to +387 | 4/4: +322 to +396 | 6/6: +5 to +22 | - | 5/5: +3 to +5 | 3/3: +3 to +10 | 1/1: +3 | 0/1: 1 never |
| final without permission, alarm only, another alarm forcing early delivery | 2/2: +4 to +13 | - | - | - | - | - | - | - |

### Android 17, WorkManager left alone for 240 s after the process was gone

| mechanism | am kill at +8 s | swiped away at +5 s |
|---|---|---|
| (e) WorkManager, 30 s initial delay | 1/2: +363; 1 never (1 on reopen) | 0/1: 1 never (1 on reopen) |

### Android 17, longer delays and power states (lateness in ms)

| mechanism | 120 s, Home only | 120 s, battery saver on | 120 s, battery usage "Restricted" | 65 s, battery usage "Restricted" |
|---|---|---|---|---|
| (a) Handler.postDelayed | 0/1: 1 never (1 on reopen) | - | 0/1: 1 never (1 on reopen) | 1/1: +5 |
| (b) set(), inexact | - | 0/1: 1 never | - | - |
| (c) setExactAndAllowWhileIdle + USE_EXACT_ALARM | 1/1: +13 | 1/1: +4 | 0/1: 1 never (1 on reopen) | 0/1: 1 never (1 on reopen) |
| (d) setAndAllowWhileIdle | 1/1: +90002 | - | - | - |
| (e) WorkManager, 30 s initial delay | 0/1: 1 never (1 on reopen) | 0/1: 1 never (1 on reopen) | 0/1: 1 never (1 on reopen) | - |
| **final: exact alarm + timer** (with permission), as delivered | 1/1: +5 | 1/1: +12 | 0/1: 1 never (1 on reopen) | 1/1: +9 |
| **final without permission: aimed inexact alarm + timer**, as delivered | 1/1: +14 | - | - | - |
| final without permission, alarm only (timer removed) | 1/1: +5 | 1/1: +6 | 0/1: 1 never (1 on reopen) | 0/1: 1 never (1 on reopen) |

### Android 7.0 (API 24): the clear writes an empty clip, 30 s (lateness in ms)

| mechanism | Home / Settings / other app / in front | am kill (effective) | am kill refused: process lived on | SIGKILL at +8 s | force-stopped at +8 s |
|---|---|---|---|---|---|
| (a) Handler.postDelayed | 3/3: +33 to +41 | - | 1/1: +33 | 0/1: 1 never | - |
| (c) setExactAndAllowWhileIdle (no permission needed below API 31) | 3/3: +0 to +1 | - | 2/2: +1 to +4 | 1/1: +43 | 0/1: 1 never |
| final: exact alarm + timer, first version | 3/3: +37 to +40 | - | 1/1: +34 | - | - |
| final: exact alarm + timer, as delivered | 5/5: +22 to +41 | - | 2/2: +34 | 2/2: +36 to +42 | 0/1: 1 never |

### Android 9 (API 28), 30 s (lateness in ms)

| mechanism | Home / Settings / other app / in front | am kill (effective) | am kill refused: process lived on | SIGKILL at +8 s | force-stopped at +8 s |
|---|---|---|---|---|---|
| (a) Handler.postDelayed | 3/3: +28 to +41 | 0/1: 1 never | - | 0/1: 1 never | - |
| (c) setExactAndAllowWhileIdle (no permission needed below API 31) | 3/3: +1 | 2/2: +31 to +752 | - | 1/1: +669 | - |
| final: exact alarm + timer, first version | 3/3: +40 to +43 | 1/1: +77 | - | - | - |
| final: exact alarm + timer, as delivered | 5/5: +22 to +43 | 2/2: +632 to +634 | - | 2/2: +644 to +904 | - |

### Totals

runs per (sdk, build): {(24, '2'): 9, (24, '3'): 18, (28, '2'): 9, (28, '3'): 15, (37, '2'): 173, (37, '3'): 91}
A17 final with permission (build 3), all conditions except restricted and force-stop: 25/25 cleared; lateness min 4, max 463 (final.watch.kill.153747.60); conditions {'none': 10, 'kill': 4, 'frozen': 4, 'screenoff': 2, 'idle': 1, 'batsaver': 1, 'swipe': 1, 'locked': 2}
A17 final with permission (builds 2+3), all conditions except restricted and force-stop: 46/46 cleared; lateness min 4, max 463 (final.watch.kill.153747.60); conditions {'none': 16, 'frozen-controls': 1, 'screenoff': 5, 'kill': 7, 'frozen': 7, 'idle': 1, 'swipe': 4, 'locked': 4, 'batsaver': 1}
A17 exact alarm alone (alarm_exact + final_notimer full), same: 34/34 cleared; lateness min 1, max 408 (alarm_exact.home.swipe.144857.70); conditions {'none': 11, 'kill': 5, 'frozen': 5, 'screenoff': 5, 'idle': 1, 'batsaver': 1, 'frozen-controls': 1, 'swipe': 2, 'locked': 3}
A17 no-permission final (final + final_notimer + final_decoy, noperm, builds 2+3), same: 68/68 cleared; lateness min 1, max 447 (final.watch.kill.153014.64); conditions {'none': 28, 'kill': 12, 'frozen': 12, 'screenoff': 7, 'swipe': 4, 'idle': 1, 'batsaver': 1, 'locked': 3}
A17 no-permission final, build 3 only: 37/37 cleared; lateness min 2, max 447 (final.watch.kill.153014.64); conditions {'none': 19, 'frozen': 6, 'swipe': 1, 'kill': 6, 'screenoff': 2, 'idle': 1, 'batsaver': 1, 'locked': 1}
A7.0 final + alarm_exact: 14/14 cleared; lateness min 0, max 43 (alarm_exact.watch.kill9.151601.66); conditions {'none': 9, 'controls': 2, 'kill9': 3}
A9 final + alarm_exact: 19/19 cleared; lateness min 1, max 904 (final.watch.kill9.151635.68); conditions {'none': 9, 'kill': 5, 'controls': 2, 'kill9': 3}
runs in which the clear call threw: []
watcher runs: 169; secret seen by the watcher before the clear: 169
watcher saw the clip gone 0 to 54 ms after the app logged its clear call (144 runs)
runs where the watcher saw the clip gone BEFORE the requested delay: []
runs where the app logged a clear before the requested delay: []
runs with a look before the deadline: 11 value still there in 11

run,android,build,app,mech,bg,cond,delay_s,dt_ms,late_ms,via,threw,probe_after,watcher_gone_ms,frozen_seen,new_process,cleared_on_reopen_ms
handler.home.none.143800.60,17,2,full,handler,home,none,30,30025,25,handler,False,EMPTY,None,False,False,None
alarm_set.home.none.143800.62,17,2,full,alarm_set,home,none,30,52499,22499,receiver,False,EMPTY,None,False,False,None
alarm_exact.home.none.143800.64,17,2,full,alarm_exact,home,none,30,30002,2,receiver,False,EMPTY,None,False,False,None
alarm_window.home.none.143834.60,17,2,full,alarm_window,home,none,30,52504,22504,receiver,False,EMPTY,None,False,False,None
work.home.none.143834.64,17,2,full,work,home,none,30,30034,34,worker attempt=0,False,EMPTY,None,False,False,None
alarm_awi.home.none.143856.62,17,2,full,alarm_awi,home,none,30,52501,22501,receiver,False,EMPTY,None,False,False,None
handler.settings.none.143908.64,17,2,full,handler,settings,none,30,30003,3,handler,False,EMPTY,None,False,False,None
coroutine.home.none.143930.60,17,2,full,coroutine,home,none,30,30003,3,coroutine,False,EMPTY,None,False,False,None
alarm_window.settings.none.143941.64,17,2,full,alarm_window,settings,none,30,52502,22502,receiver,False,EMPTY,None,False,False,None
final.home.none.143947.66,7.0,2,full,final,home,none,30,30037,37,timer,False,EMPTYTEXT,None,False,False,None
final.home.none.143947.68,9,2,full,final,home,none,30,30040,40,timer,False,EMPTY,None,False,False,None
final.home.none.143952.62,17,2,full,final,home,none,30,30030,30,timer,False,EMPTY,None,False,False,None
alarm_set.settings.none.144003.60,17,2,full,alarm_set,settings,none,30,52500,22500,receiver,False,EMPTY,None,False,False,None
alarm_exact.home.none.144020.66,7.0,2,full,alarm_exact,home,none,30,30001,1,receiver,False,EMPTYTEXT,None,False,False,None
alarm_exact.home.none.144020.68,9,2,full,alarm_exact,home,none,30,30001,1,receiver,False,EMPTY,None,False,False,None
alarm_exact.settings.none.144025.62,17,2,full,alarm_exact,settings,none,30,30004,4,receiver,False,EMPTY,None,False,False,None
coroutine.settings.none.144037.64,17,2,full,coroutine,settings,none,30,30007,7,coroutine,False,EMPTY,None,False,False,None
handler.home.none.144052.66,7.0,2,full,handler,home,none,30,30041,41,handler,False,EMPTYTEXT,None,False,False,None
handler.home.none.144052.68,9,2,full,handler,home,none,30,30041,41,handler,False,EMPTY,None,False,False,None
alarm_awi.settings.none.144059.60,17,2,full,alarm_awi,settings,none,30,52498,22498,receiver,False,EMPTY,None,False,False,None
work.settings.none.144059.62,17,2,full,work,settings,none,30,30028,28,worker attempt=0,False,EMPTY,None,False,False,None
alarm_set.watch.none.144110.64,17,2,full,alarm_set,watch,none,30,52499,22499,receiver,False,EMPTY,52522,False,False,None
final.watch.none.144125.66,7.0,2,full,final,watch,none,30,30040,40,timer,False,EMPTYTEXT,30078,False,False,None
final.watch.none.144125.68,9,2,full,final,watch,none,30,30041,41,alarm,False,EMPTY,30042,False,False,None
handler.watch.none.144132.62,17,2,full,handler,watch,none,30,30012,12,handler,False,EMPTY,30020,False,False,None
final.settings.none.144155.60,17,2,full,final,settings,none,30,30020,20,timer,False,EMPTY,None,False,False,None
alarm_exact.watch.none.144158.66,7.0,2,full,alarm_exact,watch,none,30,30000,0,receiver,False,EMPTYTEXT,30038,False,False,None
alarm_exact.watch.none.144158.68,9,2,full,alarm_exact,watch,none,30,30001,1,receiver,False,EMPTY,30051,False,False,None
alarm_window.watch.none.144206.62,17,2,full,alarm_window,watch,none,30,52500,22500,receiver,False,EMPTY,52520,False,False,None
alarm_awi.watch.none.144206.64,17,2,full,alarm_awi,watch,none,30,52500,22500,receiver,False,EMPTY,52518,False,False,None
alarm_exact.watch.none.144229.60,17,2,full,alarm_exact,watch,none,30,30001,1,receiver,False,EMPTY,30043,False,False,None
handler.watch.none.144230.68,9,2,full,handler,watch,none,30,30028,28,handler,False,EMPTY,30038,False,False,None
handler.watch.none.144231.66,7.0,2,full,handler,watch,none,30,30037,37,handler,False,EMPTYTEXT,30057,False,False,None
coroutine.watch.none.144301.62,17,2,full,coroutine,watch,none,30,30028,28,coroutine,False,EMPTY,30061,False,False,None
work.watch.none.144302.60,17,2,full,work,watch,none,30,30046,46,worker attempt=0,False,EMPTY,30063,False,False,None
final.watch.none.144302.64,17,2,full,final,watch,none,30,30028,28,alarm,False,EMPTY,30078,False,False,None
final.watch.kill.144303.68,9,2,full,final,watch,kill,30,30077,77,alarm,False,EMPTY,30108,False,True,None
final.watch.kill.144304.66,7.0,2,full,final,watch,kill-refused,30,30034,34,timer,False,EMPTYTEXT,30078,False,False,None
alarm_set.home.none.144335.62,17,2,full,alarm_set,home,none,30,52495,22495,receiver,False,EMPTY,None,False,False,None
handler.home.none.144336.60,17,2,full,handler,home,none,30,30026,26,handler,False,EMPTY,None,False,False,None
alarm_exact.home.none.144336.64,17,2,full,alarm_exact,home,none,30,30002,2,receiver,False,EMPTY,None,False,False,None
alarm_exact.watch.kill.144336.68,9,2,full,alarm_exact,watch,kill,30,30031,31,receiver,False,EMPTY,30069,False,True,None
alarm_exact.watch.kill.144338.66,7.0,2,full,alarm_exact,watch,kill-refused,30,30001,1,receiver,False,EMPTYTEXT,30040,False,False,None
alarm_exact.home.swipe.144345.70,17,2,full,alarm_exact,home,swipe,30,30405,405,receiver,False,EMPTY,30419,False,True,None
work.home.none.144409.64,17,2,full,work,home,none,30,30033,33,worker attempt=0,False,EMPTY,None,False,False,None
final.home.none.144409.68,9,2,full,final,home,controls,30,30043,43,timer,False,EMPTY,None,False,False,None
alarm_window.home.none.144411.60,17,2,full,alarm_window,home,none,30,52497,22497,receiver,False,EMPTY,None,False,False,None
final.home.none.144412.66,7.0,2,full,final,home,controls,30,30037,37,alarm,False,EMPTYTEXT,None,False,False,None
final.home.swipe.144419.70,17,2,full,final,home,swipe,30,30353,353,alarm,False,EMPTY,30383,False,True,None
alarm_awi.home.none.144431.62,17,2,full,alarm_awi,home,none,30,52499,22499,receiver,False,EMPTY,None,False,False,None
handler.settings.none.144443.64,17,2,full,handler,settings,none,30,30027,27,handler,False,EMPTY,None,False,False,None
final_notimer.home.swipe.144453.70,17,2,noperm,final_notimer,home,swipe,30,30337,337,alarm,False,EMPTY,30377,False,True,None
coroutine.home.none.144507.60,17,2,full,coroutine,home,none,30,30002,2,coroutine,False,EMPTY,None,False,False,None
alarm_window.settings.none.144517.64,17,2,full,alarm_window,settings,none,30,52500,22500,receiver,False,EMPTY,None,False,False,None
work.home.swipe.144526.70,17,2,full,work,home,swipe,30,None,None,None,False,VALUE,None,False,None,72073
final.home.none.144527.62,17,2,full,final,home,none,30,30026,26,timer,False,EMPTY,None,False,False,None
alarm_set.settings.none.144540.60,17,2,full,alarm_set,settings,none,30,52499,22499,receiver,False,EMPTY,None,False,False,None
alarm_exact.settings.none.144601.62,17,2,full,alarm_exact,settings,none,30,30001,1,receiver,False,EMPTY,None,False,False,None
coroutine.settings.none.144613.64,17,2,full,coroutine,settings,none,30,30002,2,coroutine,False,EMPTY,None,False,False,None
work.settings.none.144634.62,17,2,full,work,settings,none,30,30039,39,worker attempt=0,False,EMPTY,None,False,False,None
alarm_awi.settings.none.144637.60,17,2,full,alarm_awi,settings,none,30,52502,22502,receiver,False,EMPTY,None,False,False,None
handler.home.swipe.144641.70,17,2,full,handler,home,swipe,30,None,None,None,False,VALUE,None,False,None,None
alarm_set.watch.none.144646.64,17,2,full,alarm_set,watch,none,30,52502,22502,receiver,False,EMPTY,52542,False,False,None
handler.watch.none.144708.62,17,2,full,handler,watch,none,30,30007,7,handler,False,EMPTY,30012,False,False,None
final.settings.none.144733.60,17,2,full,final,settings,none,30,30027,27,timer,False,EMPTY,None,False,False,None
alarm_window.watch.none.144741.62,17,2,full,alarm_window,watch,none,30,51746,21746,receiver,False,EMPTY,51761,False,False,None
alarm_awi.watch.none.144742.64,17,2,full,alarm_awi,watch,none,30,52500,22500,receiver,False,EMPTY,52531,False,False,None
alarm_awi.home.swipe.144801.70,17,2,full,alarm_awi,home,swipe,30,52838,22838,receiver,False,EMPTY,52859,False,True,None
alarm_exact.watch.none.144806.60,17,2,full,alarm_exact,watch,none,30,30002,2,receiver,False,EMPTY,30047,False,False,None
coroutine.watch.none.144836.62,17,2,full,coroutine,watch,none,30,30003,3,coroutine,False,EMPTY,30045,False,False,None
final.watch.none.144837.64,17,2,full,final,watch,none,30,30026,26,alarm,False,EMPTY,30048,False,False,None
work.watch.none.144839.60,17,2,full,work,watch,none,30,30023,23,worker attempt=0,False,EMPTY,30028,False,False,None
alarm_exact.home.swipe.144857.70,17,2,full,alarm_exact,home,swipe,30,30408,408,receiver,False,EMPTY,30435,False,True,None
alarm_set.home.none.144909.62,17,2,full,alarm_set,home,frozen-controls,30,52503,22503,receiver,False,EMPTY,None,True,False,None
alarm_exact.home.none.144911.64,17,2,full,alarm_exact,home,frozen-controls,30,30022,22,receiver,False,EMPTY,None,True,False,None
handler.home.none.144913.60,17,2,full,handler,home,frozen-controls,30,None,None,None,False,VALUE,None,True,None,71887
final.home.swipe.144930.70,17,2,full,final,home,swipe,30,30363,363,alarm,False,EMPTY,30368,False,True,None
work.home.none.144944.64,17,2,full,work,home,frozen-controls,30,43535,13535,worker attempt=0,False,EMPTY,None,True,False,None
final_notimer.home.swipe.145004.70,17,2,noperm,final_notimer,home,swipe,30,30345,345,alarm,False,EMPTY,30368,False,True,None
alarm_awi.home.none.145005.62,17,2,full,alarm_awi,home,frozen-controls,30,52525,22525,receiver,False,EMPTY,None,True,False,None
alarm_window.home.none.145027.60,17,2,full,alarm_window,home,frozen-controls,30,52507,22507,receiver,False,EMPTY,None,True,False,None
final_notimer.home.none.145031.64,17,2,full,final_notimer,home,none,30,30004,4,alarm,False,EMPTY,None,False,False,None
work.home.swipe.145038.70,17,2,full,work,home,swipe,30,None,None,None,False,VALUE,None,False,None,72056
fgs.home.none.145102.62,17,2,full,fgs,home,none,30,30015,15,shortService,False,EMPTY,None,False,False,None
final_notimer.home.none.145105.64,17,2,noperm,final_notimer,home,none,30,30002,2,alarm,False,EMPTY,None,False,False,None
final.home.none.145123.60,17,2,full,final,home,frozen-controls,30,30014,14,timer,False,EMPTY,None,True,False,None
final_notimer.home.none.145135.62,17,2,noperm,final_notimer,home,none,30,30011,11,alarm,False,EMPTY,None,False,False,None
final_notimer.watch.none.145138.64,17,2,full,final_notimer,watch,none,30,30008,8,alarm,False,EMPTY,30029,False,False,None
handler.home.swipe.145152.70,17,2,full,handler,home,swipe,30,None,None,None,False,VALUE,None,False,None,None
alarm_listener.home.none.145157.60,17,2,full,alarm_listener,home,none,30,30001,1,alarmListener,False,EMPTY,None,False,False,None
fgs.watch.none.145209.62,17,2,full,fgs,watch,none,30,30013,13,shortService,False,EMPTY,30033,False,False,None
final_notimer.watch.none.145212.64,17,2,noperm,final_notimer,watch,none,30,30001,1,alarm,False,EMPTY,30052,False,False,None
final.home.none.145230.60,17,2,noperm,final,home,none,30,30003,3,alarm,False,EMPTY,None,False,False,None
final_notimer.watch.none.145242.62,17,2,noperm,final_notimer,watch,none,30,30002,2,alarm,False,EMPTY,30041,False,False,None
final_notimer.settings.none.145245.64,17,2,full,final_notimer,settings,none,30,30004,4,alarm,False,EMPTY,None,False,False,None
alarm_listener.watch.none.145303.60,17,2,full,alarm_listener,watch,none,30,30000,0,alarmListener,False,EMPTY,30046,False,False,None
alarm_exact.home.swipe.145312.70,17,2,full,alarm_exact,home,swipe-missed,30,30001,1,receiver,False,EMPTY,30046,False,False,None
fgs.settings.none.145315.62,17,2,full,fgs,settings,none,30,30025,25,shortService,False,EMPTY,None,False,False,None
final_notimer.settings.none.145318.64,17,2,noperm,final_notimer,settings,none,30,30003,3,alarm,False,EMPTY,None,False,False,None
final.watch.none.145336.60,17,2,noperm,final,watch,none,30,30027,27,alarm,False,EMPTY,30044,False,False,None
final.home.swipe.145345.70,17,2,full,final,home,swipe,30,30323,323,alarm,False,EMPTY,30356,False,True,None
final_notimer.settings.none.145348.62,17,2,noperm,final_notimer,settings,none,30,30003,3,alarm,False,EMPTY,None,False,False,None
alarm_listener.settings.none.145410.60,17,2,full,alarm_listener,settings,none,30,30000,0,alarmListener,False,EMPTY,None,False,False,None
final_notimer.home.swipe.145419.70,17,2,noperm,final_notimer,home,swipe,30,30322,322,alarm,False,EMPTY,30357,False,True,None
final.settings.none.145443.60,17,2,noperm,final,settings,none,30,30027,27,timer,False,EMPTY,None,False,False,None
alarm_exact.home.locked.145452.70,17,2,full,alarm_exact,home,locked,30,30003,3,receiver,False,EMPTY,None,False,False,None
final.home.locked.145530.70,17,2,full,final,home,locked,30,30007,7,timer,False,EMPTY,None,False,False,None
final_notimer.home.locked.145608.70,17,2,noperm,final_notimer,home,locked,30,30003,3,alarm,False,EMPTY,None,False,False,None
handler.home.locked.145645.70,17,2,full,handler,home,locked,30,30026,26,handler,False,EMPTY,None,False,False,None
final.watch.kill.145708.62,17,2,full,final,watch,kill,30,30346,346,alarm,False,EMPTY,30352,False,True,None
alarm_awi.watch.kill.145708.64,17,2,full,alarm_awi,watch,kill,30,52898,22898,receiver,False,EMPTY,52943,False,True,None
work.home.locked.145723.70,17,2,full,work,home,locked,30,30051,51,worker attempt=0,False,EMPTY,None,False,False,None
final.watch.kill.145741.62,17,2,noperm,final,watch,kill,30,30362,362,alarm,False,EMPTY,30412,False,True,None
alarm_exact.home.locked.145801.70,17,2,full,alarm_exact,home,locked,30,30009,9,receiver,False,EMPTY,None,False,False,None
final_notimer.watch.kill.145804.64,17,2,noperm,final_notimer,watch,kill,30,30387,387,alarm,False,EMPTY,30409,False,True,None
alarm_listener.watch.kill.145815.62,17,2,full,alarm_listener,watch,kill,30,None,None,None,False,VALUE,None,False,None,None
alarm_exact.watch.kill.145837.60,17,2,full,alarm_exact,watch,kill,30,30307,307,receiver,False,EMPTY,30340,False,True,None
coroutine.watch.kill.145838.64,17,2,full,coroutine,watch,kill,30,None,None,None,False,VALUE,None,False,None,None
final.home.locked.145839.70,17,2,full,final,home,locked,30,30028,28,timer,False,EMPTY,None,False,False,None
work.watch.kill.145910.60,17,2,full,work,watch,kill,30,31224,1224,worker attempt=0,False,EMPTY,31247,False,True,None
final_notimer.home.locked.145917.70,17,2,noperm,final_notimer,home,locked,30,30010,10,alarm,False,EMPTY,None,False,False,None
final.watch.kill.145934.62,17,2,full,final,watch,kill,30,30318,318,alarm,False,EMPTY,30352,False,True,None
handler.watch.kill.145945.60,17,2,full,handler,watch,kill,30,None,None,None,False,VALUE,None,False,None,None
handler.home.locked.145954.70,17,2,full,handler,home,locked,30,30027,27,handler,False,EMPTY,None,False,False,None
alarm_awi.watch.kill.145957.64,17,2,full,alarm_awi,watch,kill,30,52816,22816,receiver,False,EMPTY,52855,False,True,None
final.watch.kill.150008.62,17,2,noperm,final,watch,kill,30,30309,309,alarm,False,EMPTY,30311,False,True,None
alarm_exact.home.locked.150032.70,17,2,full,alarm_exact,home,locked,30,30006,6,receiver,False,EMPTY,None,False,False,None
alarm_exact.watch.kill.150041.62,17,2,full,alarm_exact,watch,kill,30,30333,333,receiver,False,EMPTY,30346,False,True,None
final_notimer.watch.kill.150053.64,17,2,noperm,final_notimer,watch,kill,30,30336,336,alarm,False,EMPTY,30342,False,True,None
alarm_exact.watch.kill.150104.60,17,2,full,alarm_exact,watch,kill,30,30369,369,receiver,False,EMPTY,30391,False,True,None
work.watch.kill.150115.62,17,2,full,work,watch,kill,30,52822,22822,worker attempt=0,False,EMPTY,52823,False,True,None
final.watch.kill.150127.64,17,2,full,final,watch,kill,30,30309,309,alarm,False,EMPTY,30310,False,True,None
work.watch.kill.150138.60,17,2,full,work,watch,kill,30,52856,22856,worker attempt=0,False,EMPTY,52881,False,True,None
final.watch.kill.150200.64,17,2,noperm,final,watch,kill,30,30297,297,alarm,False,EMPTY,30318,False,True,None
work.watch.kill.150200.70,17,2,full,work,watch,kill,30,30363,363,worker attempt=0,False,EMPTY,30368,False,True,None
alarm_exact.watch.kill.150211.62,17,2,full,alarm_exact,watch,kill,30,30369,369,receiver,False,EMPTY,30404,False,True,None
handler.watch.kill.150234.60,17,2,full,handler,watch,kill,30,None,None,None,False,VALUE,None,False,None,None
alarm_exact.watch.kill.150234.64,17,2,full,alarm_exact,watch,kill,30,30307,307,receiver,False,EMPTY,30322,False,True,None
work.home.swipe.150234.70,17,2,full,work,home,swipe,30,None,None,None,False,VALUE,None,False,None,241936
final.chainwatch.none.150244.62,17,2,full,final,chainwatch,frozen,30,30011,11,timer,False,EMPTY,30050,True,False,None
alarm_awi.chainwatch.none.150308.64,17,2,full,alarm_awi,chainwatch,frozen,30,52508,22508,receiver,False,EMPTY,52556,True,False,None
final.chainwatch.none.150317.62,17,2,noperm,final,chainwatch,frozen,30,30006,6,timer,False,EMPTY,30039,True,False,None
alarm_listener.chainwatch.none.150350.62,17,2,full,alarm_listener,chainwatch,frozen,30,None,None,None,False,VALUE,None,True,None,None
alarm_awi.watch.kill.150354.60,17,2,full,alarm_awi,watch,kill,30,52813,22813,receiver,False,EMPTY,52817,False,True,None
final_notimer.chainwatch.none.150403.64,17,2,noperm,final_notimer,chainwatch,frozen,30,30022,22,alarm,False,EMPTY,30032,True,False,None
coroutine.chainwatch.none.150436.64,17,2,full,coroutine,chainwatch,frozen,30,None,None,None,False,VALUE,None,True,None,71980
final_notimer.watch.kill.150450.60,17,2,noperm,final_notimer,watch,kill,30,30310,310,alarm,False,EMPTY,30337,False,True,None
alarm_exact.chainwatch.none.150510.62,17,2,full,alarm_exact,chainwatch,frozen,30,30008,8,receiver,False,EMPTY,30013,True,False,None
alarm_exact.chainwatch.none.150523.60,17,2,full,alarm_exact,chainwatch,frozen,30,30003,3,receiver,False,EMPTY,30016,True,False,None
final.home.none.150535.68,9,3,full,final,home,none,30,30040,40,alarm,False,EMPTY,None,False,False,None
final.home.none.150536.66,7.0,3,full,final,home,none,30,30037,37,timer,False,EMPTYTEXT,None,False,False,None
work.chainwatch.none.150543.62,17,2,full,work,chainwatch,frozen,30,52528,22528,worker attempt=0,False,EMPTY,52546,True,False,None
final.chainwatch.none.150551.64,17,2,full,final,chainwatch,frozen,30,30007,7,timer,False,EMPTY,30041,True,False,None
work.chainwatch.none.150556.60,17,2,full,work,chainwatch,frozen,30,52547,22547,worker attempt=0,False,EMPTY,52555,True,False,None
final.watch.none.150608.66,7.0,3,full,final,watch,none,30,30041,41,alarm,False,EMPTYTEXT,30041,False,False,None
final.watch.none.150609.68,9,3,full,final,watch,none,30,30033,33,timer,False,EMPTY,30063,False,False,None
final.chainwatch.none.150624.64,17,2,noperm,final,chainwatch,frozen,30,30006,6,timer,False,EMPTY,30018,True,False,None
handler.chainwatch.none.150639.62,17,2,full,handler,chainwatch,frozen,30,None,None,None,False,VALUE,None,True,None,71895
work.watch.kill.150639.70,17,2,full,work,watch,kill,30,None,None,None,False,VALUE,None,False,None,242064
final.settings.none.150641.66,7.0,3,full,final,settings,none,30,30037,37,timer,False,EMPTYTEXT,None,False,False,None
final.settings.none.150643.68,9,3,full,final,settings,none,30,30040,40,alarm,False,EMPTY,None,False,False,None
handler.chainwatch.none.150652.60,17,2,full,handler,chainwatch,frozen,30,None,None,None,False,VALUE,None,True,None,71795
alarm_listener.chainwatch.none.150657.64,17,2,full,alarm_listener,chainwatch,frozen,30,None,None,None,False,VALUE,None,True,None,None
alarm_exact.watch.none.150715.66,7.0,3,full,alarm_exact,watch,none,30,30001,1,receiver,False,EMPTYTEXT,30020,False,False,None
alarm_exact.watch.none.150717.68,9,3,full,alarm_exact,watch,none,30,30001,1,receiver,False,EMPTY,30045,False,False,None
handler.watch.none.150747.66,7.0,3,full,handler,watch,none,30,30033,33,handler,False,EMPTYTEXT,30054,False,False,None
handler.watch.none.150750.68,9,3,full,handler,watch,none,30,30041,41,handler,False,EMPTY,30080,False,False,None
final.chainwatch.none.150754.62,17,2,full,final,chainwatch,frozen,30,30007,7,timer,False,EMPTY,30016,True,False,None
alarm_set.chainwatch.none.150806.60,17,2,full,alarm_set,chainwatch,frozen,30,52503,22503,receiver,False,EMPTY,52540,True,False,None
alarm_awi.chainwatch.none.150817.64,17,2,full,alarm_awi,chainwatch,frozen,30,52506,22506,receiver,False,EMPTY,52535,True,False,None
final.stay.none.150820.66,7.0,3,full,final,stay,none,30,30022,22,timer,False,EMPTYTEXT,None,False,False,None
final.stay.none.150823.68,9,3,full,final,stay,none,30,30022,22,alarm,False,EMPTY,None,False,False,None
final.chainwatch.none.150827.62,17,2,noperm,final,chainwatch,frozen,30,30004,4,timer,False,EMPTY,30006,True,False,None
final.chainwatch.kill.150853.66,7.0,3,full,final,chainwatch,kill-refused,30,30034,34,timer,False,EMPTYTEXT,30056,False,False,None
final.chainwatch.kill.150857.68,9,3,full,final,chainwatch,kill,30,30632,632,alarm,False,EMPTY,30663,False,True,None
alarm_exact.chainwatch.none.150900.62,17,2,full,alarm_exact,chainwatch,frozen,30,30004,4,receiver,False,EMPTY,30035,True,False,None
alarm_awi.chainwatch.none.150902.60,17,2,full,alarm_awi,chainwatch,frozen,30,52503,22503,receiver,False,EMPTY,52528,True,False,None
final_notimer.chainwatch.none.150913.64,17,2,noperm,final_notimer,chainwatch,frozen,30,30006,6,alarm,False,EMPTY,30043,True,False,None
final.chainwatch.kill.150926.66,7.0,3,full,final,chainwatch,kill-refused,30,30034,34,alarm,False,EMPTYTEXT,30075,False,False,None
final.chainwatch.kill.150930.68,9,3,full,final,chainwatch,kill,30,30634,634,alarm,False,EMPTY,30671,False,True,None
final.home.screenoff.150933.62,17,2,full,final,home,screenoff,30,30026,26,timer,False,EMPTY,None,False,False,None
alarm_exact.chainwatch.none.150946.64,17,2,full,alarm_exact,chainwatch,frozen,30,30011,11,receiver,False,EMPTY,30056,True,False,None
final_notimer.chainwatch.none.150958.60,17,2,noperm,final_notimer,chainwatch,frozen,30,30011,11,alarm,False,EMPTY,30042,True,False,None
alarm_exact.chainwatch.kill.150959.66,7.0,3,full,alarm_exact,chainwatch,kill-refused,30,30004,4,receiver,False,EMPTYTEXT,30017,False,False,None
alarm_exact.chainwatch.kill.151004.68,9,3,full,alarm_exact,chainwatch,kill,30,30752,752,receiver,False,EMPTY,30776,False,True,None
final.home.screenoff.151009.62,17,2,noperm,final,home,screenoff,30,30028,28,alarm,False,EMPTY,None,False,False,None
alarm_awi.home.screenoff.151019.64,17,2,full,alarm_awi,home,screenoff,30,52505,22505,receiver,False,EMPTY,None,False,False,None
alarm_exact.chainwatch.none.151031.60,17,2,full,alarm_exact,chainwatch,frozen,30,30005,5,receiver,False,EMPTY,30007,True,False,None
handler.chainwatch.kill.151032.66,7.0,3,full,handler,chainwatch,kill-refused,30,30033,33,handler,False,EMPTYTEXT,30055,False,False,None
handler.chainwatch.kill.151038.68,9,3,full,handler,chainwatch,kill,30,None,None,None,False,VALUE,None,False,None,None
alarm_exact.home.screenoff.151045.62,17,2,full,alarm_exact,home,screenoff,30,30002,2,receiver,False,EMPTY,None,False,False,None
work.chainwatch.none.151104.60,17,2,full,work,chainwatch,frozen,30,66926,36926,worker attempt=0,False,EMPTY,66935,True,False,None
final.home.none.151105.66,7.0,3,full,final,home,controls,30,30034,34,timer,False,EMPTYTEXT,None,False,False,None
final_notimer.home.screenoff.151118.64,17,2,noperm,final_notimer,home,screenoff,30,30005,5,alarm,False,EMPTY,None,False,False,None
work.home.screenoff.151121.62,17,2,full,work,home,screenoff,30,30036,36,worker attempt=0,False,EMPTY,None,False,False,None
final.home.screenoff.151154.64,17,2,full,final,home,screenoff,30,30019,19,timer,False,EMPTY,None,False,False,None
handler.home.screenoff.151157.62,17,2,full,handler,home,screenoff,30,30026,26,handler,False,EMPTY,None,False,False,None
final.home.none.151158.68,9,3,full,final,home,controls,30,30043,43,timer,False,EMPTY,None,False,False,None
final_notimer.home.none.151209.70,17,3,noperm,final_notimer,home,none,120,120005,5,alarm,False,EMPTY,None,True,False,None
handler.chainwatch.none.151214.60,17,2,full,handler,chainwatch,frozen,30,None,None,None,False,VALUE,None,True,None,71893
final.home.screenoff.151230.64,17,2,noperm,final,home,screenoff,30,30030,30,timer,False,EMPTY,None,False,False,None
alarm_awi.home.screenoff.151234.62,17,2,full,alarm_awi,home,screenoff,30,52501,22501,receiver,False,EMPTY,None,False,False,None
alarm_exact.home.screenoff.151306.64,17,2,full,alarm_exact,home,screenoff,30,30006,6,receiver,False,EMPTY,None,False,False,None
alarm_exact.home.screenoff.151328.60,17,2,full,alarm_exact,home,screenoff,30,30003,3,receiver,False,EMPTY,None,False,False,None
handler.home.screenoff.151332.62,17,2,full,handler,home,screenoff,30,30027,27,handler,False,EMPTY,None,False,False,None
work.home.screenoff.151342.64,17,2,full,work,home,screenoff,30,30025,25,worker attempt=0,False,EMPTY,None,False,False,None
work.home.screenoff.151404.60,17,2,full,work,home,screenoff,30,30039,39,worker attempt=0,False,EMPTY,None,False,False,None
alarm_awi.home.idle.151412.70,17,3,full,alarm_awi,home,idle,30,52504,22504,receiver,False,EMPTY,None,False,False,None
alarm_exact.home.screenoff.151418.64,17,2,full,alarm_exact,home,screenoff,30,30003,3,receiver,False,EMPTY,None,False,False,None
handler.home.screenoff.151440.60,17,2,full,handler,home,screenoff,30,30027,27,handler,False,EMPTY,None,False,False,None
alarm_exact.home.none.151459.62,17,3,full,alarm_exact,home,none,120,120013,13,receiver,False,EMPTY,None,True,False,None
final.home.none.151459.64,17,3,full,final,home,none,120,120005,5,timer,False,EMPTY,None,True,False,None
alarm_exact.home.restricted.151511.70,17,3,full,alarm_exact,home,restricted,120,None,None,None,False,VALUE,None,True,None,167301
alarm_awi.home.screenoff.151516.60,17,2,full,alarm_awi,home,screenoff,30,52501,22501,receiver,False,EMPTY,None,False,False,None
final.watch.kill9.151528.66,7.0,3,full,final,watch,kill9,30,30036,36,alarm,False,EMPTYTEXT,30081,False,True,None
final.watch.kill9.151528.68,9,3,full,final,watch,kill9,30,30644,644,alarm,False,EMPTY,30689,False,True,None
alarm_exact.watch.kill9.151601.66,7.0,3,full,alarm_exact,watch,kill9,30,30043,43,receiver,False,EMPTYTEXT,30055,False,True,None
alarm_exact.watch.kill9.151601.68,9,3,full,alarm_exact,watch,kill9,30,30669,669,receiver,False,EMPTY,30711,False,True,None
final_notimer.home.screenoff.151614.60,17,2,noperm,final_notimer,home,screenoff,30,30004,4,alarm,False,EMPTY,None,False,False,None
final.watch.kill9.151634.66,7.0,3,full,final,watch,kill9,30,30042,42,alarm,False,EMPTYTEXT,30076,False,True,None
final.watch.kill9.151635.68,9,3,full,final,watch,kill9,30,30904,904,alarm,False,EMPTY,30948,False,True,None
final.home.screenoff.151650.60,17,2,full,final,home,screenoff,30,30019,19,timer,False,EMPTY,None,False,False,None
alarm_awi.home.none.151703.62,17,3,full,alarm_awi,home,none,120,210002,90002,receiver,False,EMPTY,None,True,False,None
final.home.none.151703.64,17,3,noperm,final,home,none,120,120014,14,timer,False,EMPTY,None,True,False,None
handler.watch.kill9.151707.66,7.0,3,full,handler,watch,kill9,30,None,None,None,False,VALUE,None,False,None,None
handler.watch.kill9.151709.68,9,3,full,handler,watch,kill9,30,None,None,None,False,VALUE,None,False,None,None
final_notimer.home.screenoff.151726.60,17,2,noperm,final_notimer,home,screenoff,30,30003,3,alarm,False,EMPTY,None,False,False,None
work.home.restricted.151801.70,17,3,full,work,home,restricted,120,None,None,None,False,VALUE,None,True,None,167126
alarm_exact.home.screenoff.151802.60,17,2,full,alarm_exact,home,screenoff,30,30004,4,receiver,False,EMPTY,None,False,False,None
handler.home.none.151844.60,17,3,full,handler,home,none,120,None,None,None,False,VALUE,None,True,None,166832
final_notimer.home.idle.151906.64,17,3,noperm,final_notimer,home,idle,30,30003,3,alarm,False,EMPTY,None,False,False,None
final.home.idle.151943.64,17,3,full,final,home,idle,30,30027,27,alarm,False,EMPTY,None,False,False,None
final_notimer.home.restricted.152019.64,17,3,noperm,final_notimer,home,restricted,120,None,None,None,False,VALUE,None,True,None,167069
alarm_set.home.idle.152037.62,17,3,full,alarm_set,home,idle,30,None,None,None,False,EMPTY,None,True,None,101322
final_notimer.home.restricted.152050.70,17,3,noperm,final_notimer,home,restricted,65,None,None,None,False,VALUE,None,True,None,102083
work.home.none.152133.60,17,3,full,work,home,none,120,None,None,None,False,EMPTY,None,True,None,166452
handler.home.idle.152223.62,17,3,full,handler,home,idle,30,30025,25,handler,False,EMPTY,None,False,False,None
final.home.batsaver.152234.70,17,3,full,final,home,batsaver,120,120012,12,timer,False,EMPTY,None,True,False,None
final.home.restricted.152300.62,17,3,full,final,home,restricted,120,None,None,None,False,VALUE,None,True,None,167014
final.home.restricted.152308.64,17,3,full,final,home,restricted,65,65009,9,timer,False,EMPTY,None,False,False,None
final_notimer.home.batsaver.152417.64,17,3,noperm,final_notimer,home,batsaver,120,120006,6,alarm,False,EMPTY,None,True,False,None
alarm_exact.home.idle.152422.60,17,3,full,alarm_exact,home,idle,30,30002,2,receiver,False,EMPTY,None,False,False,None
final.home.none.152438.70,17,3,full,final,home,none,30,30031,31,timer,False,EMPTY,None,False,False,None
work.home.idle.152458.60,17,3,full,work,home,idle,30,30049,49,worker attempt=0,False,EMPTY,None,False,False,None
final.home.none.152512.70,17,3,noperm,final,home,none,30,30028,28,timer,False,EMPTY,None,False,False,None
handler.home.restricted.152534.60,17,3,full,handler,home,restricted,120,None,None,None,False,VALUE,None,True,None,166833
final.watch.forcestop.152536.66,7.0,3,full,final,watch,forcestop,30,None,None,None,False,VALUE,None,False,None,None
final.home.none.152545.70,17,3,noperm,final,home,none,30,30016,16,timer,False,EMPTY,None,False,False,None
alarm_exact.home.restricted.152549.62,17,3,full,alarm_exact,home,restricted,65,None,None,None,False,VALUE,None,True,None,102245
final_notimer.watch.none.152619.70,17,3,full,final_notimer,watch,none,30,30004,4,alarm,False,EMPTY,30040,False,False,None
final.watch.none.152620.64,17,3,full,final,watch,none,30,30027,27,alarm,False,EMPTY,30038,False,False,None
final_decoy.watch.none.152652.70,17,3,noperm,final_decoy,watch,none,30,30010,10,alarm,False,EMPTY,30039,False,False,None
final.stay.none.152654.64,17,3,full,final,stay,none,30,30022,22,timer,False,EMPTY,None,False,False,None
alarm_exact.watch.forcestop.152655.66,7.0,3,full,alarm_exact,watch,forcestop,30,None,None,None,False,VALUE,None,False,None,None
final.watch.kill.152725.70,17,3,full,final,watch,kill,30,30353,353,alarm,False,EMPTY,30357,False,True,None
final.watch.none.152727.64,17,3,noperm,final,watch,none,30,30006,6,timer,False,EMPTY,30012,False,False,None
alarm_set.home.batsaver.152733.62,17,3,full,alarm_set,home,batsaver,120,None,None,None,False,VALUE,None,True,None,None
final.chainwatch.none.152759.70,17,3,full,final,chainwatch,frozen,30,30006,6,timer,False,EMPTY,30019,True,False,None
final_notimer.watch.none.152800.64,17,3,noperm,final_notimer,watch,none,30,30003,3,alarm,False,EMPTY,30024,False,False,None
final_notimer.watch.forcestop.152815.66,7.0,3,noperm,final_notimer,watch,forcestop,30,None,None,None,False,VALUE,None,False,None,None
handler.home.restricted.152823.60,17,3,full,handler,home,restricted,65,65005,5,handler,False,EMPTY,None,False,False,None
final.chainwatch.none.152832.70,17,3,noperm,final,chainwatch,frozen,30,30006,6,timer,False,EMPTY,30032,True,False,None
final_decoy.watch.none.152834.64,17,3,noperm,final_decoy,watch,none,30,30025,25,alarm,False,EMPTY,30079,False,False,None
final_notimer.chainwatch.none.152906.70,17,3,noperm,final_notimer,chainwatch,frozen,30,30009,9,alarm,False,EMPTY,30029,True,False,None
final_decoy.watch.kill.152907.64,17,3,noperm,final_decoy,watch,kill,30,30004,4,alarm,False,EMPTY,30046,False,True,None
alarm_exact.home.batsaver.152932.60,17,3,full,alarm_exact,home,batsaver,120,120004,4,receiver,False,EMPTY,None,True,False,None
final.home.swipe.152939.70,17,3,full,final,home,swipe,30,30343,343,alarm,False,EMPTY,30369,False,True,None
final.chainwatch.none.152941.64,17,3,full,final,chainwatch,frozen,30,30005,5,timer,False,EMPTY,30029,True,False,None
final_notimer.home.swipe.153013.70,17,3,noperm,final_notimer,home,swipe-missed,30,30003,3,alarm,False,EMPTY,30043,False,False,None
final.watch.kill.153014.64,17,3,noperm,final,watch,kill,30,30447,447,alarm,False,EMPTY,30458,False,True,None
final.settings.none.153029.62,17,3,full,final,settings,none,30,30020,20,timer,False,EMPTY,None,False,False,None
final_notimer.watch.kill.153048.64,17,3,noperm,final_notimer,watch,kill,30,30314,314,alarm,False,EMPTY,30329,False,True,None
final.watch.none.153103.62,17,3,full,final,watch,none,30,30027,27,timer,False,EMPTY,30062,False,False,None
final.home.locked.153120.70,17,3,full,final,home,locked,30,30023,23,timer,False,EMPTY,None,False,False,None
final.home.screenoff.153121.64,17,3,full,final,home,screenoff,30,30018,18,alarm,False,EMPTY,None,False,False,None
work.home.batsaver.153136.60,17,3,full,work,home,batsaver,120,None,None,None,False,VALUE,None,True,None,167248
final.settings.none.153136.62,17,3,noperm,final,settings,none,30,30003,3,timer,False,EMPTY,None,False,False,None
final_notimer.home.screenoff.153158.64,17,3,noperm,final_notimer,home,screenoff,30,30004,4,alarm,False,EMPTY,None,False,False,None
final_notimer.home.locked.153158.70,17,3,noperm,final_notimer,home,locked,30,30003,3,alarm,False,EMPTY,None,False,False,None
final_notimer.watch.none.153210.62,17,3,noperm,final_notimer,watch,none,30,30002,2,alarm,False,EMPTY,30019,False,False,None
final.home.locked.153236.70,17,3,full,final,home,locked,30,30028,28,timer,False,EMPTY,None,False,False,None
final_decoy.watch.none.153243.62,17,3,noperm,final_decoy,watch,none,30,30009,9,alarm,False,EMPTY,30035,False,False,None
final_decoy.watch.kill.153316.62,17,3,noperm,final_decoy,watch,kill,30,30013,13,alarm,False,EMPTY,30019,False,True,None
final.watch.forcestop.153317.70,17,3,full,final,watch,forcestop,30,None,None,None,False,VALUE,None,False,None,None
final.home.none.153346.64,17,3,full,final,home,none,30,30028,28,timer,False,EMPTY,None,False,False,None
final.watch.kill.153350.62,17,3,full,final,watch,kill,30,30384,384,alarm,False,EMPTY,30431,False,True,None
final.settings.none.153419.64,17,3,full,final,settings,none,30,30027,27,timer,False,EMPTY,None,False,False,None
final.watch.kill.153423.62,17,3,noperm,final,watch,kill,30,30369,369,alarm,False,EMPTY,30410,False,True,None
final.home.none.153425.60,17,3,full,final,home,none,30,30016,16,alarm,False,EMPTY,None,False,False,None
alarm_exact.watch.forcestop.153437.70,17,3,full,alarm_exact,watch,forcestop,30,None,None,None,False,VALUE,None,False,None,None
final.watch.none.153452.64,17,3,noperm,final,watch,none,30,30015,15,alarm,False,EMPTY,30035,False,False,None
final_notimer.watch.kill.153457.62,17,3,noperm,final_notimer,watch,kill,30,30333,333,alarm,False,EMPTY,30351,False,True,None
final.settings.none.153500.60,17,3,full,final,settings,none,30,30006,6,alarm,False,EMPTY,None,False,False,None
final_notimer.home.none.153526.64,17,3,noperm,final_notimer,home,none,30,30005,5,alarm,False,EMPTY,None,False,False,None
final.home.screenoff.153530.62,17,3,full,final,home,screenoff,30,30014,14,alarm,False,EMPTY,None,False,False,None
final.watch.none.153533.60,17,3,noperm,final,watch,none,30,30026,26,timer,False,EMPTY,30043,False,False,None
final_notimer.watch.forcestop.153557.70,17,3,noperm,final_notimer,watch,forcestop,30,None,None,None,False,VALUE,None,False,None,None
final_decoy.watch.none.153559.64,17,3,noperm,final_decoy,watch,none,30,30008,8,alarm,False,EMPTY,30053,False,False,None
final_notimer.home.none.153607.60,17,3,noperm,final_notimer,home,none,30,30004,4,alarm,False,EMPTY,None,False,False,None
final_notimer.home.screenoff.153607.62,17,3,noperm,final_notimer,home,screenoff,30,30003,3,alarm,False,EMPTY,None,False,False,None
final_decoy.watch.none.153632.64,17,3,noperm,final_decoy,watch,none,30,30007,7,alarm,False,EMPTY,30045,False,False,None
final_decoy.watch.none.153640.60,17,3,noperm,final_decoy,watch,none,30,30008,8,alarm,False,EMPTY,30059,False,False,None
final.watch.kill.153706.64,17,3,full,final,watch,kill,30,30329,329,alarm,False,EMPTY,30362,False,True,None
final_decoy.watch.none.153713.60,17,3,noperm,final_decoy,watch,none,30,30008,8,alarm,False,EMPTY,30041,False,False,None
final.chainwatch.none.153739.64,17,3,full,final,chainwatch,frozen,30,30004,4,timer,False,EMPTY,30025,True,False,None
final.watch.kill.153747.60,17,3,full,final,watch,kill,30,30463,463,alarm,False,EMPTY,30464,False,True,None
final.chainwatch.none.153812.64,17,3,noperm,final,chainwatch,frozen,30,30009,9,timer,False,EMPTY,30016,True,False,None
final.chainwatch.none.153820.60,17,3,full,final,chainwatch,frozen,30,30008,8,timer,False,EMPTY,30009,True,False,None
final_notimer.chainwatch.none.153846.64,17,3,noperm,final_notimer,chainwatch,frozen,30,30005,5,alarm,False,EMPTY,30022,True,False,None
final.chainwatch.none.153853.60,17,3,noperm,final,chainwatch,frozen,30,30013,13,timer,False,EMPTY,30051,True,False,None
final.home.swipe.153919.64,17,3,full,final,home,swipe-missed,30,30026,26,alarm,False,EMPTY,30064,False,False,None
final_notimer.chainwatch.none.153926.60,17,3,noperm,final_notimer,chainwatch,frozen,30,30008,8,alarm,False,EMPTY,30028,True,False,None
final_notimer.home.swipe.153952.64,17,3,noperm,final_notimer,home,swipe-missed,30,30003,3,alarm,False,EMPTY,30017,False,False,None
final.home.swipe.154000.60,17,3,full,final,home,swipe-missed,30,30025,25,timer,False,EMPTY,30036,False,False,None
final_notimer.home.swipe.154033.60,17,3,noperm,final_notimer,home,swipe,30,30396,396,alarm,False,EMPTY,30397,False,True,None
