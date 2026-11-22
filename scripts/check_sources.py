#!/usr/bin/env python3
"""
Source sanity checks that need no Kotlin compiler.

Default (used by CI, exits 1 on failure):
  * the same top-level class/object/interface/typealias declared in more than one file of the same package.
    This is what a stale file left over from an older zip looks like to the compiler ("Redeclaration").

--refs (advisory, never fails unless --strict):
  * `Obj.member` references to project objects where `member` is not declared in that object;
  * named arguments in `Cfg(...)` / `.copy(...)` that are not properties of Cfg.
  Heuristic: it can miss things and can rarely over-report, so CI does not gate on it.
"""
import pathlib, re, sys

ROOT = pathlib.Path(__file__).resolve().parent.parent / "app" / "src"
DECL = re.compile(r'^(?:(?:private|internal|public|data|enum|sealed|abstract|open|annotation|inline|value|fun)\s+)*'
                  r'(class|object|interface|typealias)\s+(\w+)', re.M)

def strip(src):
    out, i, n = [], 0, len(src)
    while i < n:
        if src.startswith('//', i):
            while i < n and src[i] != '\n': i += 1
        elif src.startswith('/*', i):
            j = src.find('*/', i + 2); i = n if j < 0 else j + 2
        elif src.startswith('"""', i):
            j = src.find('"""', i + 3); i = n if j < 0 else j + 3
        elif src[i] == '"':
            i += 1
            while i < n and src[i] != '"':
                i += 2 if src[i] == '\\' else 1
            i += 1
        elif src[i] == "'":
            i += 1
            while i < n and src[i] != "'":
                i += 2 if src[i] == '\\' else 1
            i += 1
        else:
            out.append(src[i]); i += 1
    return ''.join(out)

def files():
    return sorted(ROOT.rglob("*.kt"))

def package_of(text):
    m = re.search(r'^package\s+([\w.]+)', text, re.M)
    return m.group(1) if m else ""

def duplicates():
    seen, problems = {}, []
    for f in files():
        raw = f.read_text()
        code = strip(raw)
        # test sources and main sources are separate compilations: compare within each source set only
        sourceset = f.relative_to(ROOT).parts[0]
        for kind, name in DECL.findall(code):
            seen.setdefault((sourceset, package_of(raw), name), []).append(f.relative_to(ROOT.parent.parent).as_posix())
    for (ss, pkg, name), where in sorted(seen.items()):
        if len(where) > 1:
            problems.append(f"Redeclaration of '{name}' (package {pkg or '<default>'}) in: " + ", ".join(where))
    return problems

def body_after(code, start):
    depth, i = 0, code.index('{', start)
    j = i
    while j < len(code):
        depth += code[j] == '{'; depth -= code[j] == '}'
        j += 1
        if depth == 0: break
    return code[i:j]

def refs():
    sources = {f: strip(f.read_text()) for f in files()}
    allcode = "\n".join(sources.values())
    objects = {}
    for code in sources.values():
        for m in re.finditer(r'^(?:(?:private|internal)\s+)?object\s+(\w+)', code, re.M):
            body = body_after(code, m.end())
            names = set(re.findall(r'\b(?:fun|val|var|const val|class|object)\s+(?:<[^>]*>\s*)?(?:[\w.<>?]+\.)?(\w+)', body))
            objects.setdefault(m.group(1), set()).update(names)
    warn = []
    for obj, members in sorted(objects.items()):
        for f, code in sources.items():
            for ref in set(re.findall(r'\b' + obj + r'\.(\w+)', code)):
                if ref not in members and ref not in ("INSTANCE",):
                    warn.append(f"{f.relative_to(ROOT.parent.parent).as_posix()}: {obj}.{ref} is not declared in object {obj}")
    m = re.search(r'data class Cfg\((.*?)\n\)', allcode, re.S)
    if m:
        fields = set(re.findall(r'val\s+(\w+)\s*:', m.group(1)))
        for f, code in sources.items():
            for call in re.finditer(r'\b(Cfg|copy)\(([^()]*)\)', code):
                names = re.findall(r'(\w+)\s*=(?!=)', call.group(2))
                if not names:
                    continue
                # `copy(...)` could belong to any data class: only judge it when it names at least one Cfg field
                if call.group(1) == 'copy' and not (set(names) & fields):
                    continue
                for name in names:
                    if name not in fields:
                        warn.append(f"{f.relative_to(ROOT.parent.parent).as_posix()}: Cfg argument '{name}' is not a Cfg property")
    return warn

if __name__ == "__main__":
    bad = duplicates()
    for p in bad: print("ERROR:", p)
    if "--refs" in sys.argv:
        r = refs()
        for p in r: print("WARN: ", p)
        print(f"reference check: {len(r)} warning(s)")
        if r and "--strict" in sys.argv:
            sys.exit(1)
    print("duplicate-declaration check:", "FAILED" if bad else "ok", f"({len(files())} Kotlin files)")
    sys.exit(1 if bad else 0)
