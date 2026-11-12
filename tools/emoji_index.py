#!/usr/bin/env python3
"""Builds app/src/main/assets/emoji.tsv, the emoji picker's index, from emojibase-data.

    npm pack emojibase-data && tar xzf emojibase-data-*.tgz
    python3 tools/emoji_index.py package app/src/main/assets/emoji.tsv

One line per emoji, in Unicode's order:
    emoji <TAB> category <TAB> emoji version <TAB> name <TAB> keywords <TAB> skin tones <TAB> their emoji versions
Categories: 0 smileys, 1 people, 3 animals, 4 food, 5 travel, 6 activities, 7 objects,
8 symbols, 9 flags. Keywords are CLDR's and emojibase's, plus GitHub and Slack shortcodes
and emoticons, lowercased, without the words already in the name.
"""
import json
import os
import re
import sys


def words(text):
    return [w for w in re.split(r"[\s_:,]+", text.lower()) if w]


def main(package, out):
    with open(os.path.join(package, "package.json"), encoding="utf-8") as f:
        version = json.load(f)["version"]
    with open(os.path.join(package, "en", "data.json"), encoding="utf-8") as f:
        data = json.load(f)
    shortcodes = {}
    for name in ("github", "iamcal"):
        with open(os.path.join(package, "en", "shortcodes", name + ".json"), encoding="utf-8") as f:
            for hexcode, codes in json.load(f).items():
                shortcodes.setdefault(hexcode, []).extend([codes] if isinstance(codes, str) else codes)
    lines = ["# Emoji index from emojibase-data %s (MIT, Miles Johnson), with names and keywords from"
             " Unicode CLDR (Unicode License v3). Rebuild with tools/emoji_index.py." % version]
    for e in sorted(data, key=lambda x: x.get("order", 1 << 30)):
        group = e.get("group")
        if group is None or group == 2:  # letters for flags; skin tone and hair swatches
            continue
        label = e["label"].replace("\t", " ")
        named = set(words(label))
        extra = []
        for w in list(e.get("tags", [])) + shortcodes.get(e["hexcode"], []):
            for part in [w.lower()] + words(w):
                if part and part not in named and part not in extra:
                    extra.append(part)
        emoticons = e.get("emoticon", [])
        for emoticon in [emoticons] if isinstance(emoticons, str) else emoticons:
            if emoticon not in extra:
                extra.append(emoticon)
        skins = [s["emoji"] for s in e.get("skins", [])]
        skin_versions = ["%g" % s.get("version", 0) for s in e.get("skins", [])]
        v = e.get("version", 0)
        lines.append("\t".join([e["emoji"], str(group), ("%g" % v), label, " ".join(extra), " ".join(skins), " ".join(skin_versions)]))
    with open(out, "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("%d emoji -> %s (%d bytes)" % (len(lines) - 1, out, os.path.getsize(out)))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2])
