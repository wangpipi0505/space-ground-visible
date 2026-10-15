"""比较 STK AER 数据与软件同刻状态计算的视线几何。"""

from __future__ import annotations

import csv
import json
import math
from pathlib import Path

ROOT = Path(r"G:\5-YG\space-ground-visible")
STATE_FILE = ROOT / "verification/runtime/A09/inputs/full-states.csv"
STK_DIR = ROOT / "verification/runtime/A09/stk-results-rerun"
OUTPUT = ROOT / "verification/runtime/A09/stk-web-geometry-comparison.json"


def rows(path: Path) -> list[dict[str, str]]:
    with path.open("r", encoding="utf-8-sig", newline="") as source:
        return list(csv.DictReader(source))


def vector(row: dict[str, str], prefix: str) -> tuple[float, float, float]:
    return tuple(float(row[f"{prefix}_{axis}"]) for axis in ("x", "y", "z"))


def dot(a: tuple[float, ...], b: tuple[float, ...]) -> float:
    return sum(x * y for x, y in zip(a, b))


def cross(a: tuple[float, float, float], b: tuple[float, float, float]) -> tuple[float, float, float]:
    return (
        a[1] * b[2] - a[2] * b[1],
        a[2] * b[0] - a[0] * b[2],
        a[0] * b[1] - a[1] * b[0],
    )


def norm(a: tuple[float, ...]) -> float:
    return math.sqrt(dot(a, a))


def scale(a: tuple[float, ...], factor: float) -> tuple[float, ...]:
    return tuple(value * factor for value in a)


def sub(a: tuple[float, ...], b: tuple[float, ...]) -> tuple[float, ...]:
    return tuple(x - y for x, y in zip(a, b))


def lvlh_aer(state: dict[str, str], satellite: str) -> tuple[float, float, float, tuple[float, float, float]]:
    position = vector(state, satellite)
    velocity = tuple(float(state[f"{satellite}_v{axis}"]) for axis in ("x", "y", "z"))
    target = vector(state, "TARGET")
    radial = scale(position, 1.0 / norm(position))
    along_flat = sub(velocity, scale(radial, dot(velocity, radial)))
    along = scale(along_flat, 1.0 / norm(along_flat))
    normal = cross(radial, along)
    los = sub(target, position)
    los_unit = scale(los, 1.0 / norm(los))
    elevation = math.degrees(math.asin(max(-1.0, min(1.0, dot(los_unit, radial)))))
    azimuth = math.degrees(math.atan2(dot(los_unit, normal), dot(los_unit, along))) % 360.0
    return azimuth, elevation, norm(los), los


def angle_difference_deg(a: float, b: float) -> float:
    return abs((a - b + 180.0) % 360.0 - 180.0)


def statistics(values: list[float]) -> dict[str, float]:
    return {
        "count": len(values),
        "maxAbs": max((abs(value) for value in values), default=0.0),
        "meanAbs": sum(abs(value) for value in values) / len(values) if values else 0.0,
    }


def main() -> None:
    states = rows(STATE_FILE)
    sample_rows = rows(ROOT / "verification/runtime/A09/software/visibility-samples.csv")
    state_by_time = {round(float(row["tSec"]), 6): row for row in states}
    # 按软件实际状态样本的相邻历元计算 Web 采用的有限差分角速度，并用真实时间间隔归一。
    web_rate_by_satellite: dict[str, dict[float, float]] = {"SAT_A": {}, "SAT_B": {}}
    web_sample_times = sorted({float(row["tSec"]) for row in sample_rows})
    for previous_time, current_time in zip(web_sample_times, web_sample_times[1:]):
        previous = state_by_time[round(previous_time, 6)]
        current = state_by_time[round(current_time, 6)]
        elapsed = current_time - previous_time
        if elapsed <= 0.0:
            continue
        for satellite in ("SAT_A", "SAT_B"):
            current_los = sub(vector(current, "TARGET"), vector(current, satellite))
            previous_los = sub(vector(previous, "TARGET"), vector(previous, satellite))
            cosine = max(-1.0, min(1.0, dot(current_los, previous_los) / (norm(current_los) * norm(previous_los))))
            web_rate_by_satellite[satellite][round(current_time, 12)] = math.degrees(math.acos(cosine)) / elapsed
    results = []
    for satellite in ("SAT_A", "SAT_B"):
        aer_rows = rows(STK_DIR / f"{satellite}-aer.csv")
        range_errors: list[float] = []
        azimuth_errors: list[float] = []
        elevation_errors: list[float] = []
        web_rate_by_time = web_rate_by_satellite[satellite]
        for aer in aer_rows:
            sample_time = float(aer["Time"])
            state = state_by_time.get(round(sample_time, 6))
            if state is None:
                continue
            web_azimuth, web_elevation, web_range, los = lvlh_aer(state, satellite)
            # STK VVLH 的方位正向与 Web 的 N = R × T 定义相反。
            expected_stk_azimuth = (-web_azimuth) % 360.0
            azimuth_errors.append(angle_difference_deg(float(aer["Azimuth"]), expected_stk_azimuth))
            elevation_errors.append(float(aer["Elevation"]) - web_elevation)
            range_errors.append(float(aer["Range"]) - web_range)

        rate_audits = []
        for condition in ("C3", "C4"):
            # 角速度约束结果必须直接取 STK Constraint Data，不能由旋转 VVLH AER 导数合成。
            stk_rate_rows = rows(STK_DIR / f"{condition}-{satellite}-angular-rate.csv")
            stk_rate_entries = [
                (float(row["tSec"]), float(row["stkFromAngularRateDegPerSec"]))
                for row in stk_rate_rows
            ]
            visible_times = [
                float(row["tSec"])
                for row in sample_rows
                if row["condition"] == condition
                and row["satellite"] == satellite.replace("_", "-")
                and row["visible"].lower() == "true"
            ]
            web_visible_rates = [
                web_rate_by_time[round(t, 12)] for t in visible_times if round(t, 12) in web_rate_by_time
            ]
            matched = []
            matched_time_offsets: list[float] = []
            for time_sec in visible_times:
                web_rate_key = round(time_sec, 12)
                if web_rate_key not in web_rate_by_time or not stk_rate_entries:
                    continue
                stk_time, stk_rate = min(stk_rate_entries, key=lambda entry: abs(entry[0] - time_sec))
                time_offset = abs(stk_time - time_sec)
                if time_offset <= 0.00002:
                    matched.append((web_rate_by_time[web_rate_key], stk_rate))
                    matched_time_offsets.append(time_offset)
            stk_visible_rates = [stk for _, stk in matched]
            rate_errors = [stk - web for web, stk in matched]
            rate_audits.append(
                {
                    "condition": condition,
                    "webVisibleSampleCount": len(visible_times),
                    "stkConstraintDataSampleCount": len(stk_rate_rows),
                    "matchedRateSampleCount": len(matched),
                    "maxStkToWebSampleTimeOffsetSec": max(matched_time_offsets, default=0.0),
                    "webFiniteRateMaxDegPerSec": max(web_visible_rates, default=0.0),
                    "stkFromAngularRateMaxDegPerSec": max(stk_visible_rates, default=0.0),
                    "stkMinusWebFiniteRateDegPerSec": statistics(rate_errors),
                    "webFiniteSamplesAbove0_3DegPerSec": sum(rate > 0.3 for rate in web_visible_rates),
                    "stkConstraintSamplesAbove0_3DegPerSec": sum(rate > 0.3 for rate in stk_visible_rates),
                }
            )
        results.append(
            {
                "satellite": satellite,
                "aerRows": len(aer_rows),
                "matchedWebSampleRows": len(range_errors),
                "rangeErrorM": statistics(range_errors),
                "azimuthErrorDegAfterSignMapping": statistics(azimuth_errors),
                "elevationErrorDeg": statistics(elevation_errors),
                "stkAngularRateSource": "Constraint Data.FromAngularRate",
                "rateAuditsOnWebVisibleSamples": rate_audits,
            }
        )

    document = {
        "purpose": "Compare STK AER and Constraint Data providers with LVLH geometry and finite-difference rates derived from exact software states at common sample epochs.",
        "stkWebRateMatchToleranceSec": 0.00002,
        "stateSource": str(STATE_FILE),
        "stkSource": str(STK_DIR),
        "results": results,
    }
    OUTPUT.write_text(json.dumps(document, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(document, ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()
