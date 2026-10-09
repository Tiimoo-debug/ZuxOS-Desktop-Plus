#!/usr/bin/env python3
"""What nothing in the module ever reaches, so it can be deleted.

Four checks, each one a different kind of dead code:

- methods and classes no entry point reaches: the module is compiled and handed to ProGuard,
  kept from what Android and LSPosed start (the Xposed entry point and the manifest's
  components), and asked what it would remove;
- fields that are written but never read, from the bytecode;
- constants, imports and private methods named nowhere else, and doc comments left behind with
  no code under them, from the source.

Before deleting a finding, check it is not reached by name: a string handed to reflection or
Xposed, a resource, or another tool that reads the source. ``KEEP`` below lists the ones that are,
with the reason. ProGuard, its dependencies and the platform jar are fetched once from Maven
Central, like tools/check.py's.

    tools/deadcode.py         # print the findings; exit 1 if there are any
"""

import os
import re
import subprocess
import sys
import tempfile

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import check  # noqa: E402  (the shared Maven cache and source lists)

ROOT = check.ROOT
APP = "app/src/main"
PACKAGE = "com.zuxos.desktopplus"

PROGUARD = [("com.guardsquare", "proguard-base", "7.6.1"),
            ("com.guardsquare", "proguard-core", "9.1.7"),
            ("org.jetbrains.kotlin", "kotlin-stdlib", "2.1.0"),
            ("com.google.code.gson", "gson", "2.11.0"),
            ("org.apache.logging.log4j", "log4j-api", "2.24.2"),
            ("org.apache.logging.log4j", "log4j-core", "2.24.2")]

# Reached by name, or kept on purpose - never reported.
KEEP = {
    # tools/glass_preview.py reads each material's name out of the source.
    "LiquidGlass$Material.name",
    # Values the settings spinners store by position: "Both", and the third sort order.
    "Const.DISPLAY_BOTH",
    "Const.SORT_RECENT",
}


def code_mask(text):
    """For each character, whether it is code - not a comment, a string or a char literal."""
    mask = bytearray([1]) * len(text)
    i, n = 0, len(text)
    while i < n:
        if text.startswith("//", i):
            j = text.find("\n", i)
            j = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.index("*/", i) + 2
        elif text.startswith('"""', i):
            j = text.index('"""', i + 3) + 3
        elif text[i] in "\"'":
            j = i + 1
            while text[j] != text[i]:
                j += 2 if text[j] == "\\" else 1
            j += 1
        else:
            i += 1
            continue
        mask[i:j] = bytes(j - i)
        i = j
    return mask


def code_only(text):
    mask = code_mask(text)
    return "".join(c if mask[k] or c == "\n" else " " for k, c in enumerate(text))


def entry_points():
    """-keep rules for what LSPosed and Android start by name."""
    rules = []
    with open(os.path.join(ROOT, APP, "assets", "xposed_init")) as f:
        for line in f:
            if line.strip():
                rules.append("-keep class %s { *; }" % line.strip())
    with open(os.path.join(ROOT, APP, "AndroidManifest.xml")) as f:
        manifest = f.read()
    for kind, name in re.findall(
            r'<(activity|service|provider|receiver)\b[^>]*?android:name="([^"]+)"', manifest, re.S):
        full = PACKAGE + name if name.startswith(".") else name
        rules.append("-keep class %s { <init>(); }" % full)
    rules.append("-keepclassmembers enum * { public static **[] values(); "
                 "public static ** valueOf(java.lang.String); }")
    return rules


def unreachable(classes, library):
    """ProGuard's list of what it would remove, less what javac or Java makes look unused."""
    jars = [check.maven(*coordinates) for coordinates in PROGUARD]
    if None in jars:
        return ["(ProGuard could not be fetched - methods not checked)"]
    with tempfile.TemporaryDirectory() as work:
        usage = os.path.join(work, "usage.txt")
        config = os.path.join(work, "keep.pro")
        java_home = os.path.dirname(os.path.dirname(os.path.realpath(
            subprocess.check_output(["which", "java"], text=True).strip())))
        with open(config, "w") as f:
            f.write("\n".join([
                "-injars " + classes,
                "-outjars " + os.path.join(work, "out.jar"),
                "-libraryjars " + os.pathsep.join(library),
                "-libraryjars %s/jmods/java.base.jmod(!**.jar;!module-info.class)" % java_home,
                "-dontoptimize", "-dontobfuscate", "-dontpreverify",
                "-ignorewarnings", "-dontwarn **", "-dontnote **",
                "-printusage " + usage] + entry_points()) + "\n")
        subprocess.run(["java", "-cp", os.pathsep.join(jars), "proguard.ProGuard", "@" + config],
                       capture_output=True, text=True)
        found, owner = [], None
        for line in open(usage):
            line = line.rstrip("\n")
            if not line.startswith(" "):
                owner = line.rstrip(":").rsplit(".", 1)[-1]
                if not line.endswith(":") and not owner == "Const":
                    found.append("class " + owner)
                continue
            member = line.strip()
            if (re.match(r"(\d+:\d+:)?private [\w$]+\(\)$", member)   # utility-class guards
                    or "synthetic" in member
                    # javac copies constants into their users, so they always look unused here;
                    # the source check below covers them
                    or re.search(r"static final (int|long|float|double|boolean|byte|short|char|"
                                 r"java\.lang\.String) \w+$", member)):
                continue
            name = re.sub(r"^\d+:\d+:", "", member)
            found.append("%s: %s" % (owner, name))
        return found


def write_only(classes):
    """Fields set somewhere and read nowhere."""
    files = []
    for base, _, names in os.walk(classes):
        files += [os.path.join(base, n) for n in names if n.endswith(".class")]
    text = subprocess.run(["javap", "-c", "-p", "-constants"] + files,
                          capture_output=True, text=True).stdout
    declared, reads, owner = {}, set(), None
    for line in text.splitlines():
        if line and not line.startswith(" "):
            match = re.search(r"(?:class|interface|enum) ([\w.$]+)", line)
            if match:
                owner = match.group(1)
            continue
        match = re.match(r"^  ((?:\w+ )*)([\w.$<>\[\],? ]+) (\w+)( = .*)?;$", line)
        if match and "(" not in line and owner:
            declared[(owner, match.group(3))] = (match.group(1), match.group(4) is not None)
        for op in re.finditer(r"get(?:field|static)\s+#\d+\s+// Field (?:([\w/$]+)\.)?(\w+):",
                              line):
            reads.add((op.group(1).replace("/", ".") if op.group(1) else owner, op.group(2)))
    out = []
    for (cls, field), (mods, constant) in sorted(declared.items()):
        if not cls.startswith(PACKAGE) or (cls, field) in reads:
            continue
        if "static final" in mods and constant:
            continue        # a constant: javac copied it into its users
        out.append("%s.%s: written, never read" % (cls.rsplit(".", 1)[-1], field))
    return out


def source_checks():
    out = []
    files = check.java_files(["app/src/main/java", "logic/src/main/java"])
    for path in files:
        lines = open(path).read().split("\n")
        for k in range(len(lines) - 1):
            # A doc comment straight on top of another documents nothing: what it was written
            # for has moved or gone.
            if lines[k].strip().endswith("*/") and lines[k + 1].strip().startswith("/**"):
                out.append("%s:%d: doc comment with no code under it"
                           % (os.path.basename(path)[:-5], k + 1))
    code = {f: code_only(open(f).read()) for f in files}
    everything = "\n".join(code.values())
    for path, text in code.items():
        name = os.path.basename(path)[:-5]
        for m in re.finditer(r"^\s*((?:public |private |protected |static |final )*)static final "
                             r"[\w<>\[\]., ]+? (\w+)\s*=", text, re.M):
            scope = text if "private" in m.group(1) else everything
            if len(re.findall(r"\b%s\b" % m.group(2), scope)) <= 1:
                out.append("%s.%s: constant never used" % (name, m.group(2)))
        body = re.sub(r"^\s*(import|package) [^\n]*\n", "", text, flags=re.M)
        for m in re.finditer(r"^import (?:static )?([\w.]+)\.(\w+);", text, re.M):
            if not re.search(r"\b%s\b" % m.group(2), body):
                out.append("%s: import %s.%s never used" % (name, m.group(1), m.group(2)))
        for m in re.finditer(r"private (?:static )?(?:final )?(?:synchronized )?"
                             r"[\w<>\[\]., ?]+ (\w+)\s*\(", text):
            if len(re.findall(r"\b%s\b" % m.group(1), text)) <= 1:
                out.append("%s.%s(): private, never called" % (name, m.group(1)))
    return out


def main():
    jar = check.framework()
    if not jar:
        print("no platform jar - nothing can be checked here")
        return 2
    with tempfile.TemporaryDirectory() as work:
        xposed = os.path.join(work, "xposed")
        classes = os.path.join(work, "classes")
        os.makedirs(xposed)
        os.makedirs(classes)
        for sources, target, classpath in (
                (check.java_files(["xposed-api"]), xposed, jar),
                (check.java_files(["app/src/main/java", "logic/src/main/java"]), classes,
                 jar + os.pathsep + xposed)):
            javac = subprocess.run(["javac", "-nowarn", "-g", "-cp", classpath, "-d", target]
                                   + sources, capture_output=True, text=True)
            if javac.returncode != 0:
                print(javac.stderr.strip() + "\n\ndoes not compile - run tools/check.py first")
                return 2
        found = unreachable(classes, [jar, xposed]) + write_only(classes) + source_checks()
    found = [f for f in found if not any(f.startswith(k + ":") or f.startswith(k.replace(
        "$", ".") + ":") for k in KEEP)]
    for line in found:
        print(line)
    print("%d finding(s)" % len(found) if found else "no dead code found")
    return 1 if found else 0


if __name__ == "__main__":
    sys.exit(main())
