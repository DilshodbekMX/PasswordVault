#!/bin/bash
# Stops every running driver (python3 processes whose command line names the driver script).
for p in $(pgrep -x python3); do
  if tr '\0' ' ' < /proc/$p/cmdline 2>/dev/null | grep -q "clipexp"; then
    echo "killing driver $p: $(tr '\0' ' ' < /proc/$p/cmdline)"
    kill $p
  fi
done
