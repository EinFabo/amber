"""Checks the store listing in fastlane/metadata/android/{en-US,de-DE}: Play's limits (title 30,
short description 80, full description 4000, a changelog 500 characters), no " - " as a dash,
no word the glossary rules out (docs/GLOSSARY.md: "Not" for English, "Not (DE)" for German),
no formal German ("Sie", "Ihnen", "Ihr"), no straight double quotes in the German texts
(„…“), and a changelog for every version in both languages.

Usage: python3 -I tools/check_listing.py
"""
import pathlib
import re
import sys

root = pathlib.Path(__file__).resolve().parent.parent
meta = root / "fastlane/metadata/android"
errors = []

LIMITS = {"title.txt": 30, "short_description.txt": 80, "full_description.txt": 4000}
CHANGELOG_LIMIT = 500
LANGUAGES = {"en-US": 3, "de-DE": 4}  # glossary column of the "Not" words

# The glossary's "Not" words, per language. An entry with a remark in brackets depends on its
# sense and is left to the reader, as in check_strings.py. A word that another row of the table
# uses in its own term ("history" is ruled out for the last dictation, but History is a term)
# is left out as well.
banned = {"en-US": set(), "de-DE": set()}
terms = {"en-US": set(), "de-DE": set()}
for line in (root / "docs/GLOSSARY.md").read_text(encoding="utf-8").splitlines():
    cols = [c.strip() for c in line.strip().strip("|").split("|")]
    if not line.startswith("|") or len(cols) < 5 or cols[0] in ("Thing", "") or set(cols[0]) <= set("-"):
        continue
    for lang, index in LANGUAGES.items():
        terms[lang].update(w.lower() for w in re.findall(r"[\w-]+", re.sub(r"\([^)]*\)", "", cols[index - 2])))
        for word in cols[index].split(","):
            word = word.strip()
            if word and "(" not in word and word != "Einrichtung":
                banned[lang].add(word)

for lang in banned:
    banned[lang] = {w for w in banned[lang] if w.lower() not in terms[lang]}

FORMAL = re.compile(r"(?<![\w-])(Sie|Ihnen|Ihr|Ihre|Ihrem|Ihren|Ihrer|Ihres)(?![\w-])")


def check_text(lang, name, text):
    where = f"{lang}/{name}"
    if " - " in text:
        errors.append(f"{where}: uses \" - \" as a dash")
    for word in sorted(banned[lang]):
        if re.search(r"(?<![\w-])" + re.escape(word) + r"(?![\w-])", text, re.IGNORECASE if word.islower() else 0):
            errors.append(f"{where}: uses \"{word}\" (GLOSSARY.md)")
    if lang == "de-DE":
        for match in FORMAL.finditer(text):
            errors.append(f"{where}: formal \"{match.group(0)}\" (the German text says \"du\")")
        if '"' in re.sub(r"<[^>]*>", "", text):
            errors.append(f"{where}: straight double quote (German quotes are „…“)")
    if text != text.strip() and text.rstrip("\n") != text.strip() or text.endswith("\n\n"):
        errors.append(f"{where}: starts or ends in white space")


for lang in LANGUAGES:
    folder = meta / lang
    for file, limit in LIMITS.items():
        path = folder / file
        if not path.exists():
            errors.append(f"{lang}/{file}: missing")
            continue
        text = path.read_text(encoding="utf-8")
        if len(text) > limit:
            errors.append(f"{lang}/{file}: {len(text)} characters, the limit is {limit}")
        check_text(lang, file, text)
    for path in sorted((folder / "changelogs").glob("*.txt")):
        if not path.stem.isdigit():
            errors.append(f"{lang}/changelogs/{path.name}: not a version code")
            continue
        text = path.read_text(encoding="utf-8")
        if len(text) > CHANGELOG_LIMIT:
            errors.append(f"{lang}/changelogs/{path.name}: {len(text)} characters, the limit is {CHANGELOG_LIMIT}")
        # The older changelogs stay as published; the check is for the one that is next.
        if int(path.stem) >= 71:
            check_text(lang, f"changelogs/{path.name}", text)

codes = {lang: {p.stem for p in (meta / lang / "changelogs").glob("*.txt")} for lang in LANGUAGES}
for lang, others in (("en-US", "de-DE"), ("de-DE", "en-US")):
    for code in sorted(codes[lang] - codes[others], key=int):
        errors.append(f"{lang}/changelogs/{code}.txt has no {others} twin")

if errors:
    print(f"{len(errors)} PROBLEM(S):")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print(f"OK: title, short and full description within Play's limits in {', '.join(LANGUAGES)}; changelogs within {CHANGELOG_LIMIT} characters in both languages; no dash chains, no ruled-out words, no formal German.")
