#!/bin/bash
# Print every window of the given package (default: the lab app) with its type and flags.
PKG=${1:-com.example.pvlab.window}
/home/asus/Android/Sdk/platform-tools/adb -s emulator-5562 shell dumpsys window windows | awk -v pkg="$PKG" '
/^  Window #/ {show=0; if (index($0,pkg)>0 || index($0,"Popup")>0 || index($0,"Toast")>0) show=1; if (show) print}
show && /mOwnerUid|ty=|fl=|isVisible|mHasSurface|mViewVisibility/ {print}
'
