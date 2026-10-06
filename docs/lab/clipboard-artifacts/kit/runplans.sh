#!/bin/bash
# runplans.sh NAME "PLANS for device 1" "PLANS for device 2" "PLANS for device 3"
# One driver per device, in parallel and detached from the caller; results/NAME.done appears when all have ended.
# PORTS (default "5560 5562 5564") names the emulator ports, in the order of the plan arguments.
LAB="$(cd "$(dirname "$0")" && pwd)"; cd "$LAB"
name=$1; shift
rm -f results/$name.done
(
  for port in ${PORTS:-5560 5562 5564}; do
    plans="$1"; shift
    if [ -n "$plans" ]; then
      SERIAL=emulator-$port python3 clipexp.py $plans > results/driver-$name-$port.log 2>&1 &
    fi
  done
  wait
  date > results/$name.done
) > /dev/null 2>&1 < /dev/null &
disown
echo "started $name"
