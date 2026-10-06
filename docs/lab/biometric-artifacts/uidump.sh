#!/usr/bin/env bash
# usage: uidump.sh PORT  -> prints a compact view of the current UI tree
ADB="${ADB:-/home/asus/Android/Sdk/platform-tools/adb}"
PORT="${1:?port}"
"$ADB" -s "emulator-$PORT" shell uiautomator dump /sdcard/ui.xml >/dev/null 2>&1
"$ADB" -s "emulator-$PORT" shell cat /sdcard/ui.xml | python3 -c "
import sys
import xml.etree.ElementTree as ET
data=sys.stdin.read()
try:
    t=ET.fromstring(data)
except Exception as e:
    print('PARSE ERROR', e, data[:200]); sys.exit(1)
for n in t.iter('node'):
    a=n.attrib
    if a.get('text') or a.get('content-desc') or a.get('clickable')=='true':
        print(a.get('package'), '|', a.get('class').split('.')[-1], '| id=',a.get('resource-id').split('/')[-1], '| text=',repr(a.get('text')), '| desc=',repr(a.get('content-desc')), '| click=',a.get('clickable'), '| en=',a.get('enabled'), '|',a.get('bounds'))
"
