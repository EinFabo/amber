"""Checks the app's texts: English (values/) and German (values-de/) hold the same strings
with the same placeholders, toasts fit in two lines, and the Compose screens carry no English
sentence of their own.

Usage: python3 -I tools/check_strings.py

A text that must stay English in code (a name, a log line) is not flagged when its line
ends in `// English`, with the reason after it if not obvious.
"""
import pathlib
import re
import sys
import xml.etree.ElementTree as ET

root = pathlib.Path(__file__).resolve().parent.parent
res = root / "app/src/main/res"
errors = []

PLACEHOLDER = re.compile(r"%(?:\d+\$)?[-#+ 0,(]*\d*(?:\.\d+)?[sdfxXc%]")


def load(folder):
    """name -> {quantity or '': text} for every translatable string and plural in a folder."""
    found = {}
    for f in sorted((res / folder).glob("*.xml")):
        tree = ET.parse(f)
        for el in tree.getroot():
            name = el.get("name")
            if el.get("translatable") == "false":
                continue
            if el.tag in ("string", "plurals") and name in found:
                errors.append(f"{folder}: {name} is defined twice")
            if el.tag == "string":
                found[name] = {"": "".join(el.itertext())}
            elif el.tag == "plurals":
                found[name] = {item.get("quantity"): "".join(item.itertext()) for item in el}
    return found


def raw_apostrophes(folder):
    """An unescaped ' breaks AAPT2; caught here with the line, before a long build."""
    for f in sorted((res / folder).glob("*.xml")):
        for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
            for m in re.finditer(r"<(?:string|item)\b[^>]*>(.*?)</(?:string|item)>", line):
                if re.search(r"(?<!\\)'", m.group(1)):
                    errors.append(f"{f.relative_to(root)}:{i}: unescaped ' (write \\')")


def placeholders(text):
    return sorted(p for p in PLACEHOLDER.findall(text) if p != "%%")


en, de = load("values"), load("values-de")
for folder in ("values", "values-de"):
    raw_apostrophes(folder)
for name in sorted(en.keys() - de.keys()):
    errors.append(f"values-de: missing {name}")
for name in sorted(de.keys() - en.keys()):
    errors.append(f"values-de: {name} is not in values (left over?)")
for lang, texts in (("values", en), ("values-de", de)):
    for name, forms in texts.items():
        if any(t != t.strip() for t in forms.values()):
            errors.append(f"{lang}: {name} starts or ends with a space, which Android drops")
for name in sorted(en.keys() & de.keys()):
    en_ph = {p for t in en[name].values() for p in placeholders(t)}
    de_ph = {p for t in de[name].values() for p in placeholders(t)}
    if en_ph != de_ph:
        errors.append(f"{name}: placeholders differ - en {sorted(en_ph)}, de {sorted(de_ph)}")
    if "" not in en[name] and "other" not in de[name]:
        errors.append(f"{name}: German plural has no 'other'")

# Toasts: from target 36 Android shows at most two lines of a text toast and cuts the rest. So a
# toast says the way out first, in about 60-70 characters, and the reason goes to Diagnostics.
# Checked: the strings named here, those whose name starts with one of the prefixes, and the
# composed ones - a reason put before a way out - with each reason that comes with them. A
# placeholder counts as a name of PLACEHOLDER_CHARS characters.
TOAST_LIMIT = 70
COMPOSED_LIMIT = 75
PLACEHOLDER_CHARS = 10
TOAST_PREFIXES = ("toast_", "problem_", "capture_", "quick_done_", "undo_")
TOASTS = {
    "dictation_mic_permission", "dictation_mic_not_opened", "dictation_no_field", "dictation_not_inserted_here",
    "dictation_copied", "dictation_no_engine", "dictation_timed_out", "dictation_too_short", "dictation_nothing_heard",
    "dictation_mic_taken_sent", "dictation_mic_lost_sent", "dictation_service_off",
    "quick_too_late", "quick_open_failed", "bubble_hidden_in",
    "fallback_server_too_slow", "fallback_no_connection", "fallback_no_answer_on_phone", "fallback_other_server",
    "fallback_no_credit", "fallback_model_gone",
}
# The reasons a kept recording's toast names before "Try again" (DictationController.failRecognition).
RETRY_REASONS = [
    "problem_no_connection", "problem_no_answer_in_time", "problem_key_rejected", "problem_no_credit",
    "problem_server_error", "problem_service_down", "problem_rate_limited", "problem_address_not_found",
    "problem_answer_unreadable", "problem_model_not_on_server", "problem_no_api_key",
]
COMPOSED = {
    "toast_try_again": RETRY_REASONS,
    # The cleanup that did not run, with what stands in its way (Texts.cleanupTrouble).
    "toast_cleanup_did_not_run": ["cleanup_trouble_key", "cleanup_trouble_credit", "cleanup_trouble_model"],
}


def shown(text):
    """The text as a toast shows it, a placeholder as a name of PLACEHOLDER_CHARS characters."""
    text = re.sub(r"\\(['\"@?])", r"\1", text)
    return PLACEHOLDER.sub(lambda m: "%" if m.group(0) == "%%" else "x" * PLACEHOLDER_CHARS, text)


for lang, texts in (("values", en), ("values-de", de)):
    for name, forms in sorted(texts.items()):
        if name in TOASTS or name.startswith(TOAST_PREFIXES):
            for text in forms.values():
                if len(shown(text)) > TOAST_LIMIT:
                    errors.append(f"{lang}: {name} is a toast of {len(shown(text))} characters, more than {TOAST_LIMIT} (two lines)")
    for name, reasons in COMPOSED.items():
        for reason in reasons:
            text = shown(texts[name][""].replace("%1$s", texts[reason][""].rstrip(".")))
            if len(text) > COMPOSED_LIMIT:
                errors.append(f"{lang}: {name} with {reason} is a toast of {len(text)} characters, more than {COMPOSED_LIMIT}")

# The catalog's English texts are looked up by their exact text in :core (Texts.catalog): each
# must still be there, or that name or note silently stays English.
core_text = ""
for f in sorted((root / "core/src/main/kotlin").rglob("*.kt")):
    core_text += f.read_text(encoding="utf-8")
core_text = re.sub(r'"\s*\+\s*\n\s*"', "", core_text).replace('\\"', '"').replace("\\$", "$")
for f in sorted((res / "values").glob("strings_catalog.xml")):
    for el in ET.parse(f).getroot():
        text = re.sub(r"\\(['\"@?])", r"\1", "".join(el.itertext()))  # Android's escapes, as getString returns it
        if f'"{text}"' not in core_text:
            errors.append(f"{f.relative_to(root)}: {el.get('name')} is no longer a text in :core: \"{text[:50]}\"")

# English sentences left in the screens. A literal with a capital, a word and a space after
# it is user text unless the line says otherwise.
UI = root / "app/src/main/kotlin/app/amethyst/ui"
# Hidden with the voice commands (Features.VOICE_COMMANDS), English until they come back; and
# the icons, whose names are code.
SKIP = {"Commands.kt", "AmberIcons.kt"}
SENTENCE = re.compile(r'"((?:[^"\\$]|\\.|\$\{[^}]*\}|\$\w+)*[A-Za-z]{2,}[ ,.:?!][^"]*)"')
# One capitalized word ("Reset") is a label too - unless it is a name.
WORD = re.compile(r'"([A-Z][a-z]+)"')
NAMES = {"Amber", "Whisper", "Parakeet", "Tinfoil", "Groq", "Voxtral", "Android", "GitHub", "Apache", "Google"}
for f in sorted(UI.rglob("*.kt")):
    if f.name in SKIP:
        continue
    in_block_comment = False
    for i, line in enumerate(f.read_text(encoding="utf-8").splitlines(), 1):
        stripped = line.strip()
        if in_block_comment or stripped.startswith("/*"):
            in_block_comment = "*/" not in stripped
            continue
        if stripped.startswith(("//", "*", "import ", "@Preview")) or line.rstrip().endswith("// English") \
                or "// English:" in line:
            continue
        code = line.split(" // ")[0]
        for m in WORD.finditer(code):
            if m.group(1) not in NAMES:
                errors.append(f"{f.relative_to(root)}:{i}: English text in code: \"{m.group(1)}\"")
        for m in SENTENCE.finditer(code):
            text = m.group(1)
            # Few letters among digits and symbols: a path or a pattern, not a sentence.
            if len(re.findall(r"(?<![\w])[A-Za-z][a-z]+(?![\w])", text)) < 2 and not re.search(r"[a-z]{3,} [a-z]{2,}", text):
                continue
            if not re.search(r"[A-Za-z]{2,}", re.sub(r"\$\{[^}]*\}|\$\w+", "", text)):
                continue
            if re.fullmatch(r"[\w.\-/:%]+", text):  # ids, keys, urls
                continue
            errors.append(f"{f.relative_to(root)}:{i}: English text in code: \"{text[:60]}\"")

if errors:
    print(f"{len(errors)} PROBLEM(S):")
    for e in errors:
        print("  -", e)
    sys.exit(1)
print(f"OK: {len(en)} texts in English and German with the same placeholders; toasts within {TOAST_LIMIT} characters; no English sentence in the screens.")
