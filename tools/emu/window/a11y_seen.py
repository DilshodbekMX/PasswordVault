#!/usr/bin/env python3
"""What three observers read of one secret of the app under test (tools/emu/window/a11y.sh).

a11y_seen.py --package PKG --what LABEL --id ID --secret TEXT [--secret TEXT ...]
             --plain-dump FILE --plain-events FILE --tool-dump FILE --tool-events FILE --ui FILE

  PKG     the app's package: only its nodes and its events count
  ID      the resource id of the node that holds the secret (a test tag, such as pv.lock.password)
  TEXT    the secret as it was typed or is shown; with several, any of them counts
  the dumps and events are the helper app's records of its two accessibility services (plain: not
  an accessibility tool; tool: declared as one; LabA11yService.java gives the line formats), the
  events only those since the secret came to the screen; --ui is the screen as `uiautomator dump`
  reads it, in the form of tools/emu/adb.sh's pv_ui_nodes (text|content-desc|resource-id|...).

For each observer one line says what it reads: whether the node is there, what its text is (the
secret in plain, mask characters only, or nothing), where else the secret stands, and how many
events carried it. The secret itself is never printed. The last line is for the script:

  RESULT plain=seen|clean tool=seen|clean ui=seen|clean
"""
import argparse
import sys

MASKS = set("•*●")
TEXT_KEYS = ("text", "desc", "hint", "state", "error", "pane", "tooltip")


def fields(line):
    """A tab-separated record line as (positional parts, {key: value})."""
    parts = line.rstrip("\n").split("\t")
    values = {}
    for part in parts:
        key, sep, value = part.partition("=")
        if sep and key not in values:
            values[key] = value
    return parts, values


def short_id(resource_id):
    return "" if resource_id in ("", "null") else resource_id.rsplit("/", 1)[-1]


def describe_text(text, secrets):
    if any(s == text for s in secrets):
        return "the secret, in plain"
    if any(s in text for s in secrets):
        return "a text with the secret in plain in it"
    if text == "":
        return "nothing"
    if all(c in MASKS for c in text):
        return "%d mask characters" % len(text)
    if len(text) > 1 and all(c in MASKS for c in text[:-1]) and any(s.endswith(text[-1]) for s in secrets):
        return "%d mask characters and the character typed last" % (len(text) - 1)
    return "another text (%d characters)" % len(text)


def service(label, dump_path, events_path, args):
    secrets = args.secret
    nodes = []
    with open(dump_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts, values = fields(line)
            if parts[0] not in ("NODE", "FOCUS") or len(parts) < 5 or parts[2] != args.package:
                continue
            nodes.append((parts[0], short_id(parts[4]), values))
    own = [n for n in nodes if n[0] == "NODE"]
    target = [n for n in own if n[1] == args.id]
    seen = False
    said = []
    if not own:
        said.append("no node of the app at all")
    elif not target:
        said.append("no node %s (among %d nodes of the app)" % (args.id, len(own)))
    else:
        values = target[0][2]
        said.append("the node %s reads %s" % (args.id, describe_text(values.get("text", ""), secrets)))
        if values.get("password") == "true":
            said[-1] += ", marked as a password"
    elsewhere = []
    for kind, node_id, values in nodes:
        for key in TEXT_KEYS:
            if any(s in values.get(key, "") for s in secrets):
                seen = True
                if kind == "FOCUS":
                    place = "the node with the input focus, asked for directly"
                elif node_id == args.id and key == "text":
                    continue
                else:
                    place = "%s of %s" % (key, node_id or "a node without an id")
                if place not in elsewhere:
                    elsewhere.append(place)
    if elsewhere:
        said.append("the secret is also read as: " + "; ".join(elsewhere[:6]))
    total = carrying = masked = 0
    kinds = {}
    with open(events_path, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts, values = fields(line)
            if parts[0] != "EVENT" or len(parts) < 5 or parts[2] != args.package:
                continue
            total += 1
            texts = [values.get("text", ""), values.get("before", ""), values.get("desc", "")]
            if any(s in t for s in secrets for t in texts):
                carrying += 1
                kinds[parts[1]] = kinds.get(parts[1], 0) + 1
            elif short_id(parts[4]) == args.id and values.get("text") and all(c in MASKS for c in values["text"]):
                masked += 1
    if carrying:
        seen = True
        said.append("%d of %d events from the app carry the secret in plain (%s)"
                    % (carrying, total, ", ".join("%s x%d" % kv for kv in sorted(kinds.items()))))
    else:
        said.append("none of %d events from the app carries it" % total
                    + (" (%d about %s carry mask characters only)" % (masked, args.id) if masked else ""))
    print("  %s: %s: %s" % (args.what, label, "; ".join(said)))
    return seen


def uiautomator(args):
    secrets = args.secret
    rows = []
    with open(args.ui, encoding="utf-8", errors="replace") as f:
        for line in f:
            parts = line.rstrip("\n").split("|")
            if len(parts) >= 3:
                rows.append(parts)
    target = [r for r in rows if r[2] == args.id]
    said = []
    if not target:
        said.append("no node %s (among %d nodes of the window)" % (args.id, len(rows)))
    else:
        said.append("the node %s reads %s" % (args.id, describe_text(target[0][0], secrets)))
    elsewhere = []
    seen = False
    for r in rows:
        for index, key in ((0, "text"), (1, "desc")):
            if any(s in r[index] for s in secrets):
                seen = True
                if r[2] == args.id and key == "text":
                    continue
                place = "%s of %s" % (key, r[2] or "a node without an id")
                if place not in elsewhere:
                    elsewhere.append(place)
    if elsewhere:
        said.append("the secret is also read as: " + "; ".join(elsewhere[:6]))
    print("  %s: uiautomator: %s" % (args.what, "; ".join(said)))
    return seen


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--package", required=True)
    parser.add_argument("--what", required=True)
    parser.add_argument("--id", required=True)
    parser.add_argument("--secret", action="append", required=True)
    parser.add_argument("--plain-dump", required=True)
    parser.add_argument("--plain-events", required=True)
    parser.add_argument("--tool-dump", required=True)
    parser.add_argument("--tool-events", required=True)
    parser.add_argument("--ui", required=True)
    args = parser.parse_args()
    args.secret = [s for s in args.secret if s]
    if not args.secret:
        print("a11y_seen.py: no secret given", file=sys.stderr)
        return 2
    plain = service("the service that is not a tool", args.plain_dump, args.plain_events, args)
    tool = service("the service declared as a tool", args.tool_dump, args.tool_events, args)
    ui = uiautomator(args)
    print("RESULT plain=%s tool=%s ui=%s" % tuple("seen" if x else "clean" for x in (plain, tool, ui)))
    return 0


if __name__ == "__main__":
    sys.exit(main())
