#!/usr/bin/env python3
"""Lists the Go modules compiled into libgojni.so (Tinfoil's verifier) with version, license
and copyright line, in the form NOTICE uses. A license file that appends a second license for
code the module bundles (OpenTelemetry's ends with Go's BSD license) is named with both: the
module's own holder from its source files, the bundled code's from the appended license.

    python3 -I tools/list_go_licenses.py            # the NOTICE block
    python3 -I tools/list_go_licenses.py --check    # fails if NOTICE misses a module or version

Reads third_party/tinfoil-go (the submodule the build uses) and the Go module cache the build
already filled (a cold cache is filled with `go mod download`). Needs Go.
"""
import os
import re
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SRC = os.path.join(ROOT, "third_party", "tinfoil-go")
NOTICE = os.path.join(ROOT, "NOTICE")
LICENSE_FILES = ("LICENSE", "LICENSE.md", "LICENSE.txt", "LICENCE", "COPYING", "LICENSE-APACHE", "LICENSE-MIT")


def go(*args):
    env = dict(os.environ, GOFLAGS="-buildvcs=false", GOOS="android", GOARCH="arm64", CGO_ENABLED="1")
    env.setdefault("GOTOOLCHAIN", "local")
    done = subprocess.run(["go", *args], cwd=SRC, env=env, capture_output=True, text=True)
    if done.returncode != 0:
        sys.exit("go %s failed:\n%s" % (" ".join(args), done.stderr.strip()))
    return done.stdout


def modules():
    """(path, version, dir) of every module the mobile package links, tinfoil-go itself aside."""
    # `go list` needs the sources of the linked modules; a cold cache gets them from `go mod download`.
    args = ("list", "-deps", "-f", "{{if .Module}}{{.Module.Path}} {{.Module.Version}} {{.Module.Dir}}{{end}}", "./mobile")
    try:
        out = go(*args)
    except SystemExit:
        go("mod", "download")
        out = go(*args)
    found = {}
    for line in out.splitlines():
        parts = line.split(" ", 2)
        if len(parts) == 3 and parts[1]:  # no version: the main module
            found[parts[0]] = (parts[1], parts[2])
    # gomobile bind copies golang.org/x/mobile's runtime support (bind/seq) into the library; it is
    # not a dependency of ./mobile itself.
    mobile = go("list", "-m", "-f", "{{.Path}} {{.Version}} {{.Dir}}", "golang.org/x/mobile").split(" ", 2)
    found[mobile[0]] = (mobile[1], mobile[2].strip())
    return sorted((p, v, d) for p, (v, d) in found.items())


def license_text(directory):
    for name in LICENSE_FILES:
        path = os.path.join(directory, name)
        if os.path.isfile(path):
            with open(path, encoding="utf-8", errors="replace") as f:
                return f.read()
    return ""


def classify(text):
    t = " ".join(text.split()).lower()
    if not t:
        return "unknown"
    if "apache license" in t and "version 2.0" in t:
        return "MIT and Apache-2.0" if "permission is hereby granted, free of charge" in t else "Apache-2.0"
    if "mozilla public license" in t:
        return "MPL-2.0"
    if "permission is hereby granted, free of charge" in t:
        return "MIT"
    if "redistribution and use in source and binary forms" in t:
        n = 3 if "neither the name" in t or "may be used to endorse" in t else 2
        return "BSD-%d-Clause" % n
    if "permission to use, copy, modify, and/or distribute" in t or "isc license" in t:
        return "ISC"
    return "unknown"


COPYRIGHT = re.compile(r"^\s*(copyright\s+(\(c\)\s*)?\d{4}|\(c\)\s*\d{4}).*$", re.I | re.M)
APACHE_END = "END OF TERMS AND CONDITIONS"


def copyright_line(text):
    found = COPYRIGHT.search(text)
    return re.sub(r"\s+", " ", found.group(0).strip()) if found else ""


def split_appended(text):
    """(the module's own license, a license appended after it) - the second empty when there is
    none. A copyright line after the Apache License's terms is the module's own when the
    Apache boilerplate follows it (its appendix filled in: "Copyright 2024 The Update Framework
    Authors / Licensed under the Apache License"); before another license (OpenTelemetry's
    "Copyright 2009 The Go Authors" and the BSD text), it is that of code the module bundles,
    and not the module's holder."""
    end = text.find(APACHE_END)
    if end < 0:
        return text, ""
    found = COPYRIGHT.search(text, end)
    if not found:
        return text, ""
    tail = text[found.start():]
    if classify(tail) in ("Apache-2.0", "unknown"):
        return text, ""
    return text[:found.start()], tail


def source_copyright(directory):
    """The copyright line in the header of the module's own Go files ("// Copyright The
    OpenTelemetry Authors"), the first found in the module's top folder."""
    try:
        names = sorted(n for n in os.listdir(directory) if n.endswith(".go") and not n.endswith("_test.go"))
    except OSError:
        return ""
    for name in names:
        with open(os.path.join(directory, name), encoding="utf-8", errors="replace") as f:
            for line in f.read().splitlines()[:5]:
                found = re.match(r"\s*//\s*(Copyright\b.*\S)", line)
                if found:
                    return re.sub(r"\s+", " ", found.group(1))
    return ""


def describe(directory):
    """License, the module's copyright line, and what a license appended for bundled code adds."""
    own, appended = split_appended(license_text(directory))
    if not appended:
        return classify(own), copyright_line(own), ""
    holder = copyright_line(own) or source_copyright(directory)
    bundled = classify(appended)
    line = copyright_line(appended)
    return classify(own), holder, "; bundled code %s%s" % (bundled, (", " + line) if line else "")


def block():
    rows = []
    for path, version, directory in modules():
        lic, holder, bundled = describe(directory)
        rows.append((path, version, lic, holder, bundled))
    return rows


def render(rows):
    lines = []
    for path, version, lic, copyright_, bundled in rows:
        lines.append("  - %s %s - %s%s%s" % (path, version, lic, (", " + copyright_) if copyright_ else "", bundled))
    return "\n".join(lines)


def main():
    rows = block()
    unknown = [r[0] for r in rows if r[2] == "unknown"]
    if "--check" in sys.argv:
        with open(NOTICE, encoding="utf-8") as f:
            notice = f.read()
        missing = ["%s %s" % (p, v) for p, v, _, _, _ in rows if "%s %s" % (p, v) not in notice]
        if missing or unknown:
            sys.exit("NOTICE misses: %s; license not recognised: %s" % (missing, unknown))
        print("NOTICE names all %d Go modules" % len(rows))
        return
    print(render(rows))
    if unknown:
        print("license not recognised, check by hand: %s" % ", ".join(unknown), file=sys.stderr)


if __name__ == "__main__":
    main()
