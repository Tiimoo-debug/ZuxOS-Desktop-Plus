#!/usr/bin/env python3
"""A real compile check, and the unit tests, on a machine with no Android SDK.

The APK is built in CI, because the Android plugin cannot be downloaded everywhere. That used to
leave plain javac as the only local check - and plain javac cannot resolve ``android.*``, so it
abandons type-checking inside every class that touches a framework type. The problem was not that
the real errors were hard to find among the noise: *javac never reported them at all*. A method
deleted by mistake, with its call left behind, compiled silently here and failed in CI.

So this compiles against a real framework instead. Robolectric publishes ``android-all``, a jar of
the whole platform, on Maven Central; with that on the classpath javac type-checks everything and
says what CI would say. The jar is fetched once and cached outside the repository - it is far too
big to commit.

Then the ``logic`` module's unit tests run, as CI runs them: plain Java and JUnit, also fetched
once from Maven Central.

    tools/check.py            # compile everything and run the tests; exit 1 on any failure
    tools/check.py --quiet    # only the summaries

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
MAVEN = "https://repo.maven.apache.org/maven2/"
CACHE = os.path.join(os.path.expanduser("~"), ".cache", "zuxos-desktop-plus")

# What the logic module's tests run with - the same versions as logic/build.gradle.
TEST_JARS = [("junit", "junit", "4.13.2"), ("org.hamcrest", "hamcrest-core", "1.3"),
             ("org.json", "json", "20240303")]


def maven(group, artifact, version, min_size=1):
    """A jar from the cache, or fetched once from Maven Central; None if it cannot be had."""
    name = "%s-%s.jar" % (artifact, version)
    cached = os.path.join(CACHE, name)
    if os.path.exists(cached) and os.path.getsize(cached) >= min_size:
        return cached
    os.makedirs(CACHE, exist_ok=True)
    url = MAVEN + "%s/%s/%s/%s" % (group.replace(".", "/"), artifact, version, name)
    if min_size > 10_000_000:
        print("fetching %s once (~%dMB) from Maven Central ..." % (name, min_size // 250_000),
              flush=True)
    part = cached + ".part"
    try:
        with urllib.request.urlopen(url, timeout=900) as response, open(part, "wb") as out:
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
        print("could not fetch %s: %s" % (name, problem))
        return None


def framework():
    """The platform jar: from the environment, from the cache, or from Maven Central."""
    named = os.environ.get("ANDROID_ALL_JAR")
    if named and os.path.exists(named):
        return named
    return maven("org.robolectric", "android-all", VERSION, min_size=50_000_000)


def java_files(sources=SOURCES):
    out = []
    for source in sources:
        for base, _, names in os.walk(os.path.join(ROOT, source)):
            out += [os.path.join(base, n) for n in names if n.endswith(".java")]
    return sorted(out)


def compile_all(jar, quiet):
    files = java_files()
    with tempfile.TemporaryDirectory() as classes:
        javac = subprocess.run(
            ["javac", "-nowarn", "-Xmaxerrs", "200", "-cp", jar, "-d", classes] + files,
            capture_output=True, text=True)
    errors = [line for line in javac.stderr.splitlines() if ": error:" in line]
    if javac.returncode == 0:
        print("clean: %d files compile against android-all-%s" % (len(files), VERSION))
        return True
    if not quiet:
        print(javac.stderr.strip())
    print("\n%d error(s). This is what CI will say." % len(errors))
    return False


def run_tests(quiet):
    """The logic module's tests; True when they pass or cannot be run here at all."""
    jars = [maven(*coordinates) for coordinates in TEST_JARS]
    if None in jars:
        print("tests not run: JUnit could not be fetched - CI runs them")
        return True
    classpath = os.pathsep.join(jars)
    tests = java_files(["logic/src/test/java"])
    names = [os.path.relpath(f, os.path.join(ROOT, "logic/src/test/java"))[:-5].replace(os.sep, ".")
             for f in tests if f.endswith("Test.java")]
    with tempfile.TemporaryDirectory() as classes:
        javac = subprocess.run(
            ["javac", "-nowarn", "-cp", classpath, "-d", classes]
            + java_files(["logic/src/main/java"]) + tests, capture_output=True, text=True)
        if javac.returncode != 0:
            print(javac.stderr.strip())
            print("\nthe tests do not compile. This is what CI will say.")
            return False
        run = subprocess.run(
            ["java", "-cp", classes + os.pathsep + classpath, "org.junit.runner.JUnitCore"]
            + names, capture_output=True, text=True)
    summary = [line for line in run.stdout.splitlines()
               if line.startswith("OK (") or line.startswith("Tests run:")]
    if run.returncode == 0:
        print("tests: " + (summary[-1] if summary else "OK"))
        return True
    if not quiet:
        print(run.stdout.strip())
    print("\ntests failed. This is what CI will say.")
    return False


def main():
    quiet = "--quiet" in sys.argv
    jar = framework()
    if not jar:
        print("no platform jar, so nothing can be checked here - push and let CI build it")
        return 2
    if not compile_all(jar, quiet):
        return 1
    return 0 if run_tests(quiet) else 1


if __name__ == "__main__":
    sys.exit(main())
