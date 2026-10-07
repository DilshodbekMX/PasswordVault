"""Counts words and given names in a dump of the Uzbek Wikipedia (build plan 2.12).

Usage: python3 uzwiki_counts.py uzwiki-YYYYMMDD-pages-articles.xml.bz2 <input directory>

Writes into the input directory:
  uzwiki-words.tsv       "<word>\t<sentences>\t<articles>": the 200,000 words of the articles found
                         in the most distinct sentences. A sentence counts once however often it
                         occurs, after its digits are masked, so that the templated sentences of
                         machine-made stubs ("X is a commune in France. Population: 1234.") count
                         once and not thousands of times.
  uzwiki-bio-names.tsv   "<given name>\t<biographies>\t<Uzbek biographies>": the given name of each
                         biography (an article in a "<year>-yilda tugʻilganlar" category), taken
                         from its title; "Uzbek" means one of its categories names Uzbeks or
                         Uzbekistan.
Words are Uzbek Latin in lower case, oʻ and gʻ written with U+02BB and the tutuq belgisi with U+02BC
whatever apostrophe the article used. Standard library only; about 15 minutes and 3 GB of memory.
"""
import bz2
import re
import sys
import xml.etree.ElementTree as ET
from collections import Counter

APOSTROPHES = "'`´‘’ʻʼʽ′‛‵ʹʾʿ"
APOS = "[" + re.escape(APOSTROPHES) + "]"
# a word: Latin letters, an apostrophe only between a letter and a letter (or after o and g at the end)
WORD = re.compile(r"[a-z]+(?:" + APOS + r"[a-z]+)*(?:(?<=[og])" + APOS + r")?")
SENTENCE_END = re.compile(r"(?<=[.!?…])\s+|\n+")
CATEGORY = re.compile(r"\[\[\s*(?:Turkum|Category)\s*:\s*([^\]|]+)", re.I)
BORN = re.compile(r"^\d{1,4}-yilda tug" + APOS + r"?ilganlar$", re.I)
UZBEK = re.compile(r"o" + APOS + r"?zbek", re.I)
TEMPLATE = re.compile(r"\{\{[^{}]*\}\}")
TABLE = re.compile(r"\{\|.*?\|\}", re.S)
REF = re.compile(r"<ref[^>/]*/>|<ref[^>]*>.*?</ref>", re.S | re.I)
COMMENT = re.compile(r"<!--.*?-->", re.S)
TAG = re.compile(r"<[^>]+>")
ENTITY = re.compile(r"&#?\w+;")
FILE_LINK = re.compile(r"\[\[\s*(?:Fayl|File|Rasm|Image|Tasvir|Turkum|Category)\s*:[^\[\]]*(?:\[\[[^\]]*\]\][^\[\]]*)*\]\]", re.I)
LINK = re.compile(r"\[\[(?:[^\]|]*\|)?([^\]|]*)\]\]")
EXTERNAL = re.compile(r"\[(?:https?:)?//\S+\s*([^\]]*)\]")
URL = re.compile(r"https?://\S+")
QUOTES = re.compile(r"'{2,}")
SURNAME_END = re.compile(r"(?:ov|ova|ev|eva|yev|yeva|iy|iya|in|ina|skiy|skaya)$")


def normalise(word):
    """oʻ and gʻ with U+02BB, every other apostrophe U+02BC."""
    out = []
    for i, c in enumerate(word):
        if c in APOSTROPHES:
            out.append("ʻ" if i > 0 and word[i - 1] in "og" else "ʼ")
        else:
            out.append(c)
    return "".join(out)


def clean(text):
    text = COMMENT.sub(" ", text)
    text = REF.sub(" ", text)
    for _ in range(6):  # nested templates, innermost first
        text, n = TEMPLATE.subn(" ", text)
        if n == 0:
            break
    text = TABLE.sub(" ", text)
    text = FILE_LINK.sub(" ", text)
    text = LINK.sub(r"\1", text)
    text = EXTERNAL.sub(r"\1", text)
    text = URL.sub(" ", text)
    text = TAG.sub(" ", text)
    text = ENTITY.sub(" ", text)
    text = QUOTES.sub("", text)
    return text


def given_name(title):
    words = re.sub(r"\s*\(.*\)$", "", title).split()
    if len(words) < 2:
        return None
    first = words[0]
    if SURNAME_END.search(first.lower()) and len(words) >= 2:
        first = words[1]
    if not re.fullmatch(r"[A-Z][a-z]*(?:" + APOS + r"[a-z]+)*", first) or len(first) < 3:
        return None
    return normalise(first.lower())


def main(dump, out_dir):
    sentences = set()
    sentence_count = Counter()
    article_count = Counter()
    bio = Counter()
    uz_bio = Counter()
    articles = 0
    with bz2.open(dump, "rb") as f:
        title = ns = text = None
        redirect = False
        for event, el in ET.iterparse(f, events=("end",)):
            tag = el.tag.rsplit("}", 1)[-1]
            if tag == "title":
                title = el.text or ""
            elif tag == "ns":
                ns = el.text
            elif tag == "redirect":
                redirect = True
            elif tag == "text":
                text = el.text or ""
            elif tag == "page":
                if ns == "0" and not redirect and text:
                    articles += 1
                    categories = [c.strip() for c in CATEGORY.findall(text)]
                    if any(BORN.match(c) for c in categories):
                        name = given_name(title)
                        if name:
                            bio[name] += 1
                            if any(UZBEK.search(c) for c in categories):
                                uz_bio[name] += 1
                    seen = set()
                    for s in SENTENCE_END.split(clean(text)):
                        s = re.sub(r"\d", "#", s.lower()).strip()
                        if len(s) < 3:
                            continue
                        h = hash(s)
                        if h in sentences:
                            continue
                        sentences.add(h)
                        words = set(normalise(w) for w in WORD.findall(s))
                        for w in words:
                            sentence_count[w] += 1
                        seen |= words
                    for w in seen:
                        article_count[w] += 1
                    if articles % 20000 == 0:
                        print(articles, "articles,", len(sentences), "sentences,", len(sentence_count), "words", file=sys.stderr)
                title = ns = text = None
                redirect = False
                el.clear()
    with open(out_dir + "/uzwiki-words.tsv", "w", encoding="utf-8") as out:
        for w, n in sentence_count.most_common(200000):
            out.write("%s\t%d\t%d\n" % (w, n, article_count[w]))
    with open(out_dir + "/uzwiki-bio-names.tsv", "w", encoding="utf-8") as out:
        for name, n in bio.most_common():
            out.write("%s\t%d\t%d\n" % (name, n, uz_bio[name]))
    print(articles, "articles,", len(sentences), "distinct sentences", file=sys.stderr)


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
