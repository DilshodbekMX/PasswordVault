import sys, re
import xml.etree.ElementTree as ET
path = sys.argv[1]
want = set(sys.argv[2:])
tree = ET.parse(path)
root = tree.getroot()
for pkg in root.iter('remotePackage'):
    p = pkg.get('path')
    parts = p.split(';')
    if len(parts) < 4: continue
    api = parts[1]
    if want and api not in want: continue
    rev = pkg.find('revision')
    revs = '.'.join((c.text or '') for c in rev) if rev is not None else '?'
    td = pkg.find('type-details')
    for arch in pkg.iter('archive'):
        c = arch.find('complete')
        size = c.find('size').text
        chk = c.find('checksum')
        url = c.find('url').text
        ho = arch.find('host-os')
        print(p, 'rev', revs, 'size', size, (chk.get('type') if chk is not None and chk.get('type') else 'sha1'), chk.text if chk is not None else '', url, 'host-os=' + (ho.text if ho is not None else 'any'))
