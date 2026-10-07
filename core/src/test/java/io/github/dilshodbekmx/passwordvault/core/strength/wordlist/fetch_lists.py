"""Fetches the inputs of the strength word list that come from wiki APIs (build plan 2.12).

Writes into the directory given as the only argument:
  enwiktionary-given-names.tsv   en.wiktionary: Russian and Uzbek given names and Russian
                                 diminutives, one "<category>\t<title>" per line
  uzwiki-given-names.tsv         uz.wikipedia: the articles of three categories of names,
                                 one "<category>\t<title>" per line
The category lists change as the wikis are edited; WordListBuilder records what it was built from.
Standard library only. Usage: python3 fetch_lists.py <input directory>
"""
import json
import sys
import time
import urllib.parse
import urllib.request

UA = "PasswordVault-strength-list-builder/1.0 (offline password manager; builds a bundled word list)"


def get(url, accept="application/json"):
    for attempt in range(8):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": accept})
            with urllib.request.urlopen(req, timeout=120) as r:
                data = r.read()
            time.sleep(0.5)
            return json.loads(data)
        except Exception as e:  # rate limits and passing network errors: wait and try again
            print("retry", attempt + 1, url[:100], e, file=sys.stderr)
            time.sleep(5 + attempt * 15)
    raise RuntimeError("failed: " + url)


def api(host, **params):
    params["format"] = "json"
    return get("https://%s/w/api.php?%s" % (host, urllib.parse.urlencode(params)))


def members(host, category):
    out, cont = [], {}
    while True:
        d = api(host, action="query", list="categorymembers", cmtitle=category, cmlimit="500", cmnamespace="0", **cont)
        out += [m["title"] for m in d["query"]["categorymembers"]]
        if "continue" not in d:
            return out
        cont = {"cmcontinue": d["continue"]["cmcontinue"]}


def main(out_dir):
    en = [
        "Category:Russian male given names",
        "Category:Russian female given names",
        "Category:Russian diminutives of male given names",
        "Category:Russian diminutives of female given names",
        "Category:Uzbek male given names",
        "Category:Uzbek female given names",
        "Category:Uzbek unisex given names",
        "Category:Uzbek given names",
    ]
    with open(out_dir + "/enwiktionary-given-names.tsv", "w", encoding="utf-8") as f:
        for c in en:
            for t in members("en.wiktionary.org", c):
                f.write("%s\t%s\n" % (c, t))
    uz = [
        "Turkum:Oʻzbekcha erkaklar ismlari",
        "Turkum:Oʻzbekcha ayollar ismlari",
        "Turkum:Alifbo boʻyicha ismlar",
    ]
    with open(out_dir + "/uzwiki-given-names.tsv", "w", encoding="utf-8") as f:
        for c in uz:
            for t in members("uz.wikipedia.org", c):
                f.write("%s\t%s\n" % (c, t))


if __name__ == "__main__":
    main(sys.argv[1])
