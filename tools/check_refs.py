import re, pathlib, sys, collections

root = pathlib.Path(__file__).resolve().parent.parent
kt = list(root.rglob('*.kt'))
kt = [p for p in kt if '/build/' not in str(p)]
# The type-check stubs stand in for AndroidX and the generated R class. Including them here
# would let a stub declaration satisfy an import that has no real counterpart.
kt = [p for p in kt if 'tools/typecheck-stubs' not in str(p)]

MODIFIERS = r'(?:public |internal |private |abstract |sealed |open |data |value |enum |annotation |inline |expect |actual |fun )*'
type_re = re.compile(r'^\s*(?:@\w+\s+)*' + MODIFIERS +
                     r'(?:class|interface|object|enum class|annotation class|typealias)\s+([A-Za-z_]\w*)', re.M)
# Extension functions and properties carry a receiver before the name, which must not be
# mistaken for the declared name: `fun TrustLevel.badgeColor()` declares badgeColor.
fun_re = re.compile(r'^\s*(?:@\w+\s+)*' + MODIFIERS +
                    r'fun\s+(?:<[^>]+>\s*)?(?:[\w.<>?, ]+\.)?([A-Za-z_]\w*)\s*\(', re.M)
prop_re = re.compile(r'^\s*(?:@\w+\s+)*' + MODIFIERS +
                     r'(?:val|var)\s+(?:<[^>]+>\s*)?(?:[\w.<>?, ]+\.)?([A-Za-z_]\w*)\b', re.M)

def declarations(line_or_text):
    for rx in (type_re, fun_re, prop_re):
        for m in rx.finditer(line_or_text):
            yield m.group(1)

# package -> set of top-level declared names
declared = collections.defaultdict(set)
pkg_of = {}
for p in kt:
    text = p.read_text()
    m = re.search(r'^package\s+([\w.]+)', text, re.M)
    pkg = m.group(1) if m else ''
    pkg_of[p] = pkg
    for line in text.splitlines():
        if line and not line[0].isspace():   # top-level only
            for name in declarations(line):
                declared[pkg].add(name)
    # Nested names too, so a qualified import of a nested type or an enum entry resolves.
    for name in declarations(text):
        declared[pkg + '.*nested*'].add(name)

errors = []
for p in kt:
    text = p.read_text()
    for imp in re.findall(r'^import\s+(app\.amethyst[\w.]*)', text, re.M):
        if imp in ('app.amethyst.R',) or imp.startswith('app.amethyst.R.'):
            continue
        parts = imp.split('.')
        # try longest package prefix that we know
        ok = False
        for cut in range(len(parts) - 1, 1, -1):
            pkg = '.'.join(parts[:cut])
            name = parts[cut]
            if pkg in declared:
                if name in declared[pkg] or name in declared.get(pkg + '.*nested*', set()):
                    ok = True
                break
        if not ok:
            errors.append(f"{p.relative_to(root)}: unresolved import {imp}")

# Resource references
res = root / 'app/src/main/res'
res_ids = collections.defaultdict(set)
for f in res.rglob('*.xml'):
    kind = f.parent.name.split('-')[0]
    if kind == 'values':
        for m in re.finditer(r'<(string|color|style)\s+name="([^"]+)"', f.read_text()):
            res_ids[{'string': 'string', 'color': 'color', 'style': 'style'}[m.group(1)]].add(m.group(2))
    else:
        res_ids[kind].add(f.stem)

for p in kt:
    for kind, name in re.findall(r'\bR\.(\w+)\.(\w+)', p.read_text()):
        if name not in res_ids.get(kind, set()):
            errors.append(f"{p.relative_to(root)}: missing resource R.{kind}.{name}")

# Manifest class references
manifest = (root / 'app/src/main/AndroidManifest.xml').read_text()
app_pkg = 'app.amethyst'
classes = {f"{pkg_of[p]}.{n}" for p in kt for n in declared[pkg_of[p]]}
for m in re.finditer(r'android:name="(\.[\w.]+)"', manifest):
    fq = app_pkg + m.group(1)
    if fq not in classes:
        errors.append(f"AndroidManifest.xml: class {fq} not found")
for m in re.finditer(r'android:(?:resource|drawable|icon|theme)="@(\w+)/(\w+)"', manifest):
    kind, name = m.group(1), m.group(2)
    if kind in ('mipmap', 'xml', 'drawable', 'style', 'string') and name not in res_ids.get(kind, set()):
        errors.append(f"AndroidManifest.xml: missing @{kind}/{name}")

if errors:
    print(f"{len(errors)} PROBLEM(E):")
    for e in errors: print("  -", e)
    sys.exit(1)
print(f"OK: {len(kt)} Kotlin-Dateien, alle internen Imports, Ressourcen-IDs und Manifest-Klassen aufgelöst.")
