#!/usr/bin/env python3
"""Apply a patch file to lock.md. Every step must match exactly once, or nothing is written.

Patch file format (plain text, UTF-8):

@@REPLACE
<old text, exact>
@@WITH
<new text>
@@END

@@SECTION <first line of the block, exact start of a line>
@@UNTIL <start of the line where the block ends (not replaced)>      (or @@UNTIL EOF)
<new text for the whole block>
@@END

@@AFTER
<anchor text, exact>
@@INSERT
<text inserted right after the anchor>
@@END
"""
import sys, shutil, time, os

TARGET = '/home/asus/AndroidStudioProjects/PasswordVault/docs/design/lock.md'
BACKUPS = os.path.dirname(os.path.abspath(__file__)) + '/backups'


def parse(text):
    steps, lines, i = [], text.split('\n'), 0
    while i < len(lines):
        ln = lines[i]
        if ln == '@@REPLACE' or ln == '@@AFTER':
            mid = '@@WITH' if ln == '@@REPLACE' else '@@INSERT'
            a, b, i = [], [], i + 1
            while lines[i] != mid: a.append(lines[i]); i += 1
            i += 1
            while lines[i] != '@@END': b.append(lines[i]); i += 1
            steps.append(('replace' if ln == '@@REPLACE' else 'after', '\n'.join(a), '\n'.join(b)))
        elif ln.startswith('@@SECTION '):
            start = ln[len('@@SECTION '):]
            i += 1
            assert lines[i].startswith('@@UNTIL '), 'SECTION needs UNTIL'
            until = lines[i][len('@@UNTIL '):]
            b, i = [], i + 1
            while lines[i] != '@@END': b.append(lines[i]); i += 1
            steps.append(('section', (start, until), '\n'.join(b)))
        elif ln.strip() == '' or ln.startswith('@@#'):
            pass
        else:
            raise SystemExit('patch syntax error at line %d: %r' % (i + 1, ln[:80]))
        i += 1
    return steps


def apply(doc, steps):
    for n, (kind, a, b) in enumerate(steps, 1):
        if kind in ('replace', 'after'):
            c = doc.count(a)
            if c != 1:
                raise SystemExit('step %d (%s): anchor found %d times: %r' % (n, kind, c, a[:120]))
            doc = doc.replace(a, b if kind == 'replace' else a + b)
        else:
            start, until = a
            s = [k for k in range(len(doc)) if doc.startswith(start, k) and (k == 0 or doc[k - 1] == '\n')]
            if len(s) != 1:
                raise SystemExit('step %d (section): start found %d times: %r' % (n, len(s), start[:120]))
            if until == 'EOF':
                e = len(doc)
            else:
                e = doc.find('\n' + until, s[0])
                if e < 0:
                    raise SystemExit('step %d (section): end not found: %r' % (n, until[:120]))
                e += 1
            doc = doc[:s[0]] + b.rstrip('\n') + ('\n\n' if e < len(doc) else '\n') + doc[e:]
    return doc


if __name__ == '__main__':
    patch = open(sys.argv[1], encoding='utf-8').read()
    dry = len(sys.argv) > 2 and sys.argv[2] == 'dry'
    doc = open(TARGET, encoding='utf-8').read()
    steps = parse(patch)
    new = apply(doc, steps)
    if dry:
        print('dry run OK: %d steps, %d -> %d bytes' % (len(steps), len(doc.encode()), len(new.encode())))
    else:
        os.makedirs(BACKUPS, exist_ok=True)
        shutil.copy2(TARGET, '%s/lock.%s.md' % (BACKUPS, time.strftime('%H%M%S')))
        with open(TARGET, 'w', encoding='utf-8') as f:
            f.write(new)
        print('applied %d steps, %d -> %d bytes' % (len(steps), len(doc.encode()), len(new.encode())))
