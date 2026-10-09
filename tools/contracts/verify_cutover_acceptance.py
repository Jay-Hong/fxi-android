#!/usr/bin/env python3
"""S4 CUT-C01·C02: checks one cutover acceptance run against the approved case list.

Usage: verify_cutover_acceptance.py <cases.json> <junit-xml-dir>

Every listed case of the listed class must appear exactly once in the run's JUnit XML and pass: no failure, no error, no
skip. A case of that class that is not listed fails the check too, so a row replaced by another (same total) is caught.
Other classes in the directory are ignored. Exit 0 on success, 1 with one line per problem otherwise. Pure stdlib.
"""
import glob
import json
import os
import sys
import xml.etree.ElementTree as ET


def check(cases_path, results_dir):
    with open(cases_path, encoding="utf-8") as f:
        spec = json.load(f)
    cls = spec["class"]
    expected = spec["cases"]
    problems = []
    if len(set(expected)) != len(expected):
        problems.append("the case list itself has duplicates")
    files = sorted(glob.glob(os.path.join(results_dir, "*.xml")))
    if not files:
        return problems + [f"no JUnit XML in {results_dir}"]
    seen = {}
    for path in files:
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError as error:
            problems.append(f"unreadable XML {os.path.basename(path)}: {error}")
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            for case in suite.findall("testcase"):
                if case.get("classname") != cls:
                    continue
                name = case.get("name")
                seen.setdefault(name, []).append(case)
    for name in expected:
        runs = seen.get(name, [])
        if not runs:
            problems.append(f"missing: {name}")
            continue
        if len(runs) > 1:
            problems.append(f"ran {len(runs)} times: {name}")
        for case in runs:
            for kind in ("failure", "error", "skipped"):
                if case.find(kind) is not None:
                    problems.append(f"{kind}: {name}")
    for name in sorted(set(seen) - set(expected)):
        problems.append(f"not in the approved list: {name}")
    return problems


def main(argv):
    if len(argv) != 3:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    problems = check(argv[1], argv[2])
    for problem in problems:
        print(problem)
    if problems:
        return 1
    print("cutover acceptance: every approved case ran once and passed")
    return 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
