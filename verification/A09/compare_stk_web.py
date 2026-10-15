"""比较真实 STK 连续 Access 与 Web 的离散可见样本。"""

from __future__ import annotations

import csv
import json
import sys
from pathlib import Path


ROOT = Path(r"G:\5-YG\space-ground-visible")
WEB_FILE = ROOT / "verification/runtime/A09/software/visibility-samples.csv"
STK_DIR = ROOT / "verification/runtime/A09/stk-results-rerun"
OUTPUT = ROOT / "verification/runtime/A09/stk-web-comparison.json"
CONDITIONS = ("C0", "C1", "C2", "C3", "C4", "C5")
SATELLITES = ("SAT_A", "SAT_B")
TIME_EPSILON_SEC = 0.00002


def read_csv(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        return list(csv.DictReader(source))


def web_arcs(rows: list[dict[str, str]]) -> list[dict[str, object]]:
    """按原始样本时间分组；不从 STK 窗口反造 Web 样本。"""
    visible = sorted(
        (float(row["tSec"]) for row in rows if row["visible"].lower() == "true")
    )
    arcs: list[list[float]] = []
    for sample_time in visible:
        if not arcs or sample_time - arcs[-1][-1] > 1.000001:
            arcs.append([sample_time])
        else:
            arcs[-1].append(sample_time)
    return [
        {
            "startSec": arc[0],
            "endSec": arc[-1],
            "pointCount": len(arc),
            "durationSec": arc[-1] - arc[0],
        }
        for arc in arcs
    ]


def main() -> int:
    web_rows = read_csv(WEB_FILE)
    all_results: list[dict[str, object]] = []
    all_match = True
    for condition in CONDITIONS:
        for satellite in SATELLITES:
            web_satellite = satellite.replace("_", "-")
            rows = [
                row
                for row in web_rows
                if row["condition"] == condition and row["satellite"] == web_satellite
            ]
            discrete_arcs = web_arcs(rows)
            stk_rows = read_csv(STK_DIR / f"{condition}-{satellite}-access.csv")
            continuous_arcs = [
                {
                    "startSec": float(row["Start Time"]),
                    "endSec": float(row["Stop Time"]),
                    "durationSec": float(row["Duration"]),
                    "startConstraint": row["Start Constraint"],
                    "stopConstraint": row["Stop Constraint"],
                }
                for row in stk_rows
                if row.get("Access Number")
            ]
            mismatches: list[dict[str, object]] = []
            for row in rows:
                sample_time = float(row["tSec"])
                web_visible = row["visible"].lower() == "true"
                stk_visible = any(
                    arc["startSec"] - TIME_EPSILON_SEC <= sample_time
                    <= arc["endSec"] + TIME_EPSILON_SEC
                    for arc in continuous_arcs
                )
                if web_visible != stk_visible:
                    mismatches.append(
                        {
                            "tSec": sample_time,
                            "utc": row["utc"],
                            "webVisible": web_visible,
                            "stkContinuousAccessAtSample": stk_visible,
                        }
                    )

            paired: list[dict[str, object]] = []
            remaining = list(continuous_arcs)
            for web_arc in discrete_arcs:
                if not remaining:
                    paired.append({"web": web_arc, "stk": None})
                    continue
                stk_arc = max(
                    remaining,
                    key=lambda arc: max(
                        0.0,
                        min(float(web_arc["endSec"]), float(arc["endSec"]))
                        - max(float(web_arc["startSec"]), float(arc["startSec"])),
                    ),
                )
                remaining.remove(stk_arc)
                paired.append(
                    {
                        "web": web_arc,
                        "stk": stk_arc,
                        "webFirstSampleMinusStkStartSec": float(web_arc["startSec"])
                        - float(stk_arc["startSec"]),
                        "stkStopMinusWebLastSampleSec": float(stk_arc["endSec"])
                        - float(web_arc["endSec"]),
                    }
                )
            result = {
                "condition": condition,
                "satellite": satellite,
                "sampleCount": len(rows),
                "webVisiblePointCount": sum(int(arc["pointCount"]) for arc in discrete_arcs),
                "stkContinuousArcCount": len(continuous_arcs),
                "webArcCount": len(discrete_arcs),
                "samplewiseEqual": not mismatches,
                "sampleMismatchCount": len(mismatches),
                "sampleMismatches": mismatches[:20],
                "pairedArcs": paired,
                "unpairedStkArcs": remaining,
            }
            all_match = all_match and not mismatches and not remaining and len(discrete_arcs) == len(continuous_arcs)
            all_results.append(result)

    document = {
        "test": "same imported target/satellite ephemerides; STK continuous access sampled at the exact Web request times",
        "webSamples": str(WEB_FILE),
        "stkAccessDirectory": str(STK_DIR),
        "stkEventTimeConvergenceSec": TIME_EPSILON_SEC / 2,
        "overallSamplewiseMatch": all_match,
        "caseCount": len(all_results),
        "cases": all_results,
    }
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    for result in all_results:
        counts = "/".join(
            str(result[key])
            for key in ("stkContinuousArcCount", "webArcCount", "webVisiblePointCount", "sampleMismatchCount")
        )
        print(f"{result['condition']} {result['satellite']} arcs(stk/web)/web_points/mismatches={counts}")
    print(f"overallSamplewiseMatch={all_match}; report={OUTPUT}")
    return 0 if all_match else 1


if __name__ == "__main__":
    sys.exit(main())
