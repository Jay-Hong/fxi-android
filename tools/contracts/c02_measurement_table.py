#!/usr/bin/env python3
"""S4 CUT-C02 (b′): the measurement bundle's table for one run (cut_c02_bprime_agreed.r1.md, cut_c02_m1_agreed.r1.md).

Usage: c02_measurement_table.py <c02_measurement_cases.json> <junit-xml-dir> [--sha SHA] [--run RUN] [--status STATUS]

Prints a Markdown table: per listed row its JUnit result, budget verdict and excess, timeline location and required
order/timing evidence. The completeness check of verify_cutover_acceptance.py (missing, duplicate, failure, error, skip,
unlisted row, no or unreadable XML) is part of the verdict. The first line is "C02 계량 묶음: 통과" only when every listed row
ran once, passed, stayed within the budget by its full judgeRow verdict and has its required evidence; otherwise "미통과"
with what is wrong. A value read from a failure message alone is partial, and nothing else is inferred. Passing this bundle
alone never declares C02 passed. Exit 0 on 통과, 1 otherwise. Pure stdlib.
"""
import glob
import json
import os
import re
import sys
import xml.etree.ElementTree as ET

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from verify_cutover_acceptance import check  # noqa: E402  (the same completeness rule as the cutover list)

MAX_EXCESS_MILLI = 10_000
MAX_API_IN_FLIGHT = 10
VERDICT = re.compile(r"CUT-C02 (\S+) verdict: Verdict\((.*)\)")
API_EXCESS_FAILURE = re.compile(r"api excess (\d+) > 10000")
# Rows whose contract needs an order or a time to be secured, and the marker their failure carries when it is not.
ORDER_ROWS = {"C02M-L1b": "순서 미확보"}
ORDER_LINE = "C02M-ORDER"
TIMING_PREFIX = "C02M-A05t"
TIMING_FAILURE = "of its target"


def label_of(name):
    return name.split(" ", 1)[0]


def parse_verdict(fields):
    def number(key):
        match = re.search(rf"\b{key}=(-?\d+)", fields)
        return int(match.group(1)) if match else None
    refused = re.search(r"\brefused=\[([^\]]*)\]", fields)
    return {
        "api": number("apiExcessMilli"),
        "ws": number("wsExcessMilli"),
        "inFlight": number("apiInFlight"),
        "refused": None if refused is None else ([] if not refused.group(1).strip() else refused.group(1).split(", ")),
        "429": number("answered429"),
    }


def within(v):
    return (v["api"] is not None and v["api"] <= MAX_EXCESS_MILLI and v["ws"] is not None and v["ws"] <= MAX_EXCESS_MILLI
            and v["refused"] == [] and v["inFlight"] is not None and v["inFlight"] <= MAX_API_IN_FLIGHT and v["429"] == 0)


def read_cases(results_dir, cls):
    runs = {}
    for path in sorted(glob.glob(os.path.join(results_dir, "*.xml"))):
        try:
            root = ET.parse(path).getroot()
        except ET.ParseError:
            continue
        suites = [root] if root.tag == "testsuite" else root.findall("testsuite")
        for suite in suites:
            for case in suite.findall("testcase"):
                if case.get("classname") == cls:
                    runs.setdefault(case.get("name"), []).append((os.path.basename(path), case))
    return runs


def row(name, runs):
    label = label_of(name)
    if not runs:
        evidence = "미확보" if label in ORDER_ROWS or label.startswith(TIMING_PREFIX) else "해당 없음"
        return {"name": name, "junit": "missing", "budget": "미계량", "excess": "", "timeline": "누락", "evidence": evidence, "ok": False}
    file, case = runs[0]
    kinds = [kind for kind in ("failure", "error", "skipped") if case.find(kind) is not None]
    junit = "pass" if not kinds else kinds[0]
    if len(runs) > 1:
        junit = f"ran {len(runs)} times"
    message = ""
    for kind in ("failure", "error"):
        element = case.find(kind)
        if element is not None:
            message = (element.get("message") or "") + "\n" + (element.text or "")
    out = (case.findtext("system-out") or "")
    verdicts = [m for m in VERDICT.finditer(out) if m.group(1) == label]
    if verdicts:
        v = parse_verdict(verdicts[-1].group(2))
        complete = all(v[key] is not None for key in ("api", "ws", "inFlight", "refused", "429"))
        budget = ("통과" if within(v) else "초과") if complete else "미계량"
        shown = {key: "미계량" if value is None else value for key, value in v.items()}
        excess = f"api {shown['api']} / ws {shown['ws']} / in-flight {shown['inFlight']} / refused {shown['refused']} / 429 {shown['429']}"
    else:
        partial = API_EXCESS_FAILURE.search(message)
        budget = "부분 계량" if partial else "미계량"
        excess = f"api {partial.group(1)} (실패 메시지만, 나머지 미계량)" if partial else ""
    timeline = f"{file} # {name}" if f"CUT-C02 {label} timeline:" in out else "누락"
    if label in ORDER_ROWS:
        ordered = f"{ORDER_LINE} {label}:" in out
        evidence = "순서 미확보" if ORDER_ROWS[label] in message or junit != "pass" or not ordered else "순서 확보"
    elif label.startswith(TIMING_PREFIX):
        timed = f"C02M-TIMING {label}:" in out
        evidence = "시점 미확보" if TIMING_FAILURE in message or not timed else "시점 확보"
    else:
        evidence = "해당 없음"
    ok = junit == "pass" and budget == "통과" and timeline != "누락" and evidence in ("해당 없음", "순서 확보", "시점 확보")
    return {"name": name, "junit": junit, "budget": budget, "excess": excess, "timeline": timeline, "evidence": evidence, "ok": ok}


def table(cases_path, results_dir, sha="", run="", status=""):
    """[status] is the measurement step's outcome; when given, anything but "success" leaves the bundle not passed."""
    with open(cases_path, encoding="utf-8") as f:
        spec = json.load(f)
    problems = check(cases_path, results_dir)
    runs = read_cases(results_dir, spec["class"])
    rows = [row(name, runs.get(name, [])) for name in spec["cases"]]
    unreadable = [p for p in problems if p.startswith("unreadable XML") or p.startswith("no JUnit XML")]
    run_ok = not status or status == "success"
    passed = not problems and all(r["ok"] for r in rows) and run_ok
    counts = {
        "failure/error/skip": sum(r["junit"] in ("failure", "error", "skipped") for r in rows),
        "missing": sum(r["junit"] == "missing" for r in rows),
        "중복": sum(r["junit"].startswith("ran ") for r in rows),
        "미등록": sum(p.startswith("not in the approved list") for p in problems),
        "예산 초과": sum(r["budget"] == "초과" for r in rows),
        "미계량/부분": sum(r["budget"] in ("미계량", "부분 계량") for r in rows),
        "순서·시점 미확보": sum(r["evidence"] in ("순서 미확보", "시점 미확보", "미확보") for r in rows),
        "timeline 미확보": sum(r["junit"] != "missing" and r["timeline"] == "누락" for r in rows),
        "XML 없음/파싱 실패": len(unreadable),
        f"실행 상태 {status}": 0 if run_ok else 1,
    }
    head = "C02 계량 묶음: 통과" if passed else "C02 계량 묶음: 미통과 (" + ", ".join(f"{k} {v}" for k, v in counts.items() if v) + ")"
    lines = [head, "", f"- 실행 checkout SHA: {sha or '(미지정)'} / 실행: {run or '(미지정)'} / 실행 종료 상태: {status or '(미지정)'}",
             "- 이 묶음의 통과만으로 C02 통과를 선언하지 않는다.", ""]
    if problems:
        lines += ["완전성 검사 문제:"] + [f"- {p}" for p in problems] + [""]
    lines += ["| 행 | JUnit | 예산 | excess | timeline | 순서·시점 |", "|---|---|---|---|---|---|"]
    lines += [f"| {r['name']} | {r['junit']} | {r['budget']} | {r['excess']} | {r['timeline']} | {r['evidence']} |" for r in rows]
    lines += ["", "| A05t 행 | 운영 도달 가능성 |", "|---|---|"]
    lines += [
        f"| {label_of(r['name'])} | 판단 보류 — 실제 인증·UI 경로 근거 미확보 |"
        for r in rows if label_of(r["name"]).startswith(TIMING_PREFIX)
    ]
    lines += [
        "",
        "- 기존 비교 증거(S4 증거 묶음): cut_c0102_observations/A03a_burst_excess.xml — api excess 10.023.",
        "- 기존 비교 증거(S4 증거 묶음): cut_c0102_observations/RT01-A05_merged_identity_change_1600ms_after_cold_start.xml — api excess 10.206.",
        "- 위 비교 증거는 과거 별도 실행이며, 이번 실행의 미계량·시점 미확보를 대체하지 않는다.",
    ]
    return passed, "\n".join(lines)


def main(argv):
    args = argv[1:]
    options = {}
    for key in ("--sha", "--run", "--status"):
        if key in args:
            i = args.index(key)
            options[key[2:]] = args[i + 1]
            del args[i:i + 2]
    if len(args) != 2:
        print(__doc__.strip().splitlines()[2], file=sys.stderr)
        return 2
    passed, text = table(args[0], args[1], **options)
    print(text)
    return 0 if passed else 1


if __name__ == "__main__":
    sys.exit(main(sys.argv))
