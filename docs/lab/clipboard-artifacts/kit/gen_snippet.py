#!/usr/bin/env python3
"""Derives the snippet for the real app from the file that was measured in the lab.

Input : project/app/src/main/java/com/example/pvlab/clip/SensitiveClipboard.kt (lab build 3)
Output: snippet/SensitiveClipboard.kt for package io.github.dilshodbekmx.passwordvault.clipboard

Only three things change: the lines marked LAB-ONLY (measurement logging) and the lab-only function
dropTimerForLab() are removed, the lab paragraph of the header comment is removed, and the package name
and the two string constants that contain it are renamed. Nothing else is touched.
"""
import os
import re

LAB = os.path.dirname(os.path.abspath(__file__))
src = open(os.path.join(LAB, 'project/app/src/main/java/com/example/pvlab/clip/SensitiveClipboard.kt')).read()

# 1. the lab-only function with its comment
src, n = re.subn(r'\n    /\*\* LAB-ONLY: removes the in-process timer.*?\n    }\n', '\n', src, flags=re.S)
assert n == 1
# 2. every line marked LAB-ONLY
lines = [l for l in src.split('\n') if '// LAB-ONLY' not in l]
src = '\n'.join(lines)
# 3. the lab paragraph of the header comment
src, n = re.subn(r' \*\n \* \(Lab build:.*?\n \*/', ' */', src, flags=re.S)
assert n == 1
assert 'Lab.' not in src and 'LAB-ONLY' not in src
# 4. the Runnable whose body is now a single statement, and the blank line the removed function left behind
src = src.replace('        val r = Runnable {\n            clear(app, copyId)\n        }\n',
                  '        val r = Runnable { clear(app, copyId) }\n')
src = src.replace('        Intent(app, ClipboardClearReceiver::class.java).setAction(ACTION_CLEAR)\n\n}',
                  '        Intent(app, ClipboardClearReceiver::class.java).setAction(ACTION_CLEAR)\n}')
# 5. names
src = src.replace('package com.example.pvlab.clip', 'package io.github.dilshodbekmx.passwordvault.clipboard')
src = src.replace('"com.example.pvlab.clip.extra.COPY_ID"', '"io.github.dilshodbekmx.passwordvault.extra.COPY_ID"')
src = src.replace('"com.example.pvlab.clip.action.CLEAR_SENSITIVE_CLIP"',
                  '"io.github.dilshodbekmx.passwordvault.action.CLEAR_CLIPBOARD"')
assert 'pvlab' not in src

os.makedirs(os.path.join(LAB, 'snippet'), exist_ok=True)
out = os.path.join(LAB, 'snippet', 'SensitiveClipboard.kt')
open(out, 'w').write(src)
print(out, len(src), 'bytes')
