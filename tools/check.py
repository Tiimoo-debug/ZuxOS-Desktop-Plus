#!/usr/bin/env python3
"""A real compile check, on a machine with no Android SDK.

The APK is built in CI, because the Android plugin cannot be downloaded everywhere. That used to
leave plain javac as the only local check - and plain javac cannot resolve ``android.*``, so it
abandons type-checking inside every class that touches a framework type. The problem was not that
the real errors were hard to find among the noise: *javac never reported them at all*. A method
deleted by mistake, with its call left behind, compiled silently here and failed in CI.

So this compiles against a real framework instead. Robolectric publishes ``android-all``, a jar of
the whole platform, on Maven Central; with that on the classpath javac type-checks everything and
says what CI would say. The jar is fetched once and cached outside the repository - it is far too
big to commit.

    tools/check.py            # compile everything, print the errors, exit 1 if there are any
    tools/check.py --quiet    # only the summary

Set ANDROID_ALL_JAR to use a jar you already have.
"""

import os
import subprocess
import sys
import tempfile
import urllib.request

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
SOURCES = ["app/src/main/java", "logic/src/main/java", "xposed-api"]

VERSION = "16-robolectric-13921718"
URL = ("https://repo.maven.apache.org/maven2/org/robolectric/android-all/"
       "{v}/android-all-{v}.jar".format(v=VERSION))
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zuxos-desktop-plus")


def framework():
    """The platform jar: from the environment, from the cache, or from Maven Central."""
    named = os.environ.get("ANDROID_ALL_JAR")
    if named and os.path.exists(named):
        return named
    cached = os.path.join(CACHE, "android-all-%s.jar" % VERSION)
    if os.path.exists(cached) and os.path.getsize(cached) > 50_000_000:
        return cached
    os.makedirs(CACHE, exist_ok=True)
    print("fetching the platform jar once (~190MB) from Maven Central ...", flush=True)
    part = cached + ".part"
    try:
        with urllib.request.urlopen(URL, timeout=900) as response, open(part, "wb") as out:
            while True:
                chunk = response.read(1 << 20)
                if not chunk:
                    break
                out.write(chunk)
        os.replace(part, cached)
        return cached
    except Exception as problem:            # any failure here just means "no jar"
        if os.path.exists(part):
            os.remove(part)
        print("could not fetch it: %s" % problem)
        return None


def java_files():
    out = []
    for source in SOURCES:
        for base, _, names in os.walk(os.path.join(ROOT, source)):
            out += [os.path.join(base, n) for n in names if n.endswith(".java")]
    return sorted(out)


def main():
    quiet = "--quiet" in sys.argv
    jar = framework()
    if not jar:
        print("no platform jar, so nothing can be checked here - push and let CI build it")
        return 2
    files = java_files()
    with tempfile.TemporaryDirectory() as classes:
        javac = subprocess.run(
            ["javac", "-nowarn", "-Xmaxerrs", "200", "-cp", jar, "-d", classes] + files,
            capture_output=True, text=True)
    errors = [line for line in javac.stderr.splitlines() if ": error:" in line]
    if javac.returncode == 0:
        print("clean: %d files compile against android-all-%s" % (len(files), VERSION))
        return 0
    if not quiet:
        print(javac.stderr.strip())
    print("\n%d error(s). This is what CI will say." % len(errors))
    return 1


if __name__ == "__main__":
    sys.exit(main())
