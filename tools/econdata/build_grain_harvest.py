"""JRC 전국 작물 수확기와 FAOSTAT 2023년 작물별 생산량으로 보완 수확 달력을 생성한다.

    python3 tools/econdata/build_grain_harvest.py [--cache-dir <원본 폴더>]
"""

import argparse
import csv
import io
import json
import zipfile
from collections import defaultdict
from fractions import Fraction
from pathlib import Path

from build_commodities import BASE_YEAR, WORLD_DIR
from build_economy import download
from un_m49 import game_codes_by_m49

ASAP = "https://agricultural-production-hotspots.ec.europa.eu/files/crop_calendar_gaul0.zip"
QCL = "https://bulks-faostat.fao.org/production/Production_Crops_Livestock_E_All_Data.zip"
# 지역·작기 한정 행은 해당 지역·작기의 생산량이 따로 있어야 전국 물량을 배정할 수 있다.
CROPS = {"Wheat": "15", "Rice": "27", "Barley": "44", "Maize": "56", "Millet": "79", "Sorghum": "83"}
ALL_CEREALS = "1717"
QCL_NAMES = {
    "Bolivia": "Bolivia (Plurinational State of)",
    "Iran": "Iran (Islamic Republic of)",
    "North Korea": "Democratic People's Republic of Korea",
    "Swaziland": "Eswatini",
    "Syria": "Syrian Arab Republic",
}


def zip_tables(url, cache_dir):
    filename = url.rsplit("/", 1)[-1]
    return zipfile.ZipFile(io.BytesIO(download(url, cache_dir / filename if cache_dir else None)))


def national_windows(cache_dir):
    windows = defaultdict(dict)
    with zip_tables(ASAP, cache_dir) as archive, archive.open("crop_calendar_gaul0.csv") as file:
        for row in csv.DictReader(io.TextIOWrapper(file, encoding="utf-8-sig", newline=""), delimiter=";"):
            crop = CROPS.get(row["crop_name"])
            if crop is None:
                continue
            name = row["name0_shr"]
            country = QCL_NAMES.get(name, name)
            start, end = int(row["eos_s"]), int(row["eos_e"])
            if crop in windows[country] or not (1 <= start <= 36 and 1 <= end <= 36):
                raise ValueError(f"중복되었거나 유효하지 않은 JRC 수확기: {row}")
            # 각 달의 세 순(旬). 36번째 순 다음에는 다음 해 1월 상순이 온다.
            windows[country][crop] = [((start - 1 + step) % 36) // 3 for step in range((end - start) % 36 + 1)]
    if not windows:
        raise ValueError("JRC 전국 수확기 표가 비었습니다.")
    return windows


def crop_production(cache_dir, windows, state_by_m49):
    tonnes = defaultdict(dict)
    country_codes = {}
    with zip_tables(QCL, cache_dir) as archive, archive.open("Production_Crops_Livestock_E_All_Data.csv") as file:
        for row in csv.DictReader(io.TextIOWrapper(file, encoding="utf-8-sig", newline="")):
            name, crop = row["Area"], row["Item Code"]
            if name not in windows or row["Element Code"] != "5510" or (crop != ALL_CEREALS and crop not in windows[name]):
                continue
            country_codes[name] = state_by_m49[row["Area Code (M49)"].lstrip("'")]
            raw = row[f"Y{BASE_YEAR}"]
            if not raw or row[f"Y{BASE_YEAR}F"] == "M":
                continue
            value = Fraction(raw)
            if row["Unit"] != "t" or value < 0 or crop in tonnes[name]:
                raise ValueError(f"잘못된 QCL 생산량: {name} {crop} {raw} {row['Unit']}")
            tonnes[name][crop] = value
    if set(windows) != set(country_codes):
        raise ValueError(f"QCL 국가명 대응 누락: {set(windows) - set(country_codes)}")
    return tonnes, country_codes


def monthly_shares(total, volumes, calendar):
    matched = sum(volumes[crop] for crop in calendar)
    if matched > total:
        raise ValueError(f"작물 생산 합 {matched}이 곡물 총량 {total}을 넘었습니다.")
    monthly = [(total - matched) / 12] * 12
    for crop, months in calendar.items():
        for month in months:
            monthly[month] += volumes[crop] / len(months)
    # 최대잔여법으로 0.01%p까지 표시하며 열두 달 합계는 정확히 100%다.
    exact = [quantity * 10_000 / total for quantity in monthly]
    units = [int(value) for value in exact]
    ordered = sorted(range(12), key=lambda m: exact[m] - units[m], reverse=True)
    for month in ordered[:10_000 - sum(units)]:
        units[month] += 1
    return [value / 100 for value in units], matched / total


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--cache-dir", type=Path)
    args = parser.parse_args()
    if args.cache_dir:
        args.cache_dir.mkdir(parents=True, exist_ok=True)

    windows = national_windows(args.cache_dir)
    tonnes, codes = crop_production(args.cache_dir, windows, game_codes_by_m49(args.cache_dir))
    manual = json.loads((WORLD_DIR / "grain_harvest.json").read_text(encoding="utf-8"))["countries"]
    commodities = json.loads((WORLD_DIR / "commodities.json").read_text(encoding="utf-8"))
    if commodities["baseYear"] != BASE_YEAR:
        raise ValueError("상품 생산과 수확 달력의 기준 연도가 다릅니다.")
    producers = {row["id"] for row in commodities["countries"] if (row["cerealTonnes"] or 0) > 0}
    rows = {}
    for name, windows_by_crop in windows.items():
        country = codes[name]
        if country in manual or country not in producers:
            continue
        volumes = tonnes[name]
        total = volumes.get(ALL_CEREALS)
        calendar = {crop: months for crop, months in windows_by_crop.items() if volumes.get(crop, 0) > 0}
        if not total or not calendar:
            continue
        percents, coverage = monthly_shares(total, volumes, calendar)
        covered_crops = ", ".join(crop for crop, code in CROPS.items() if code in calendar)
        rows[country] = {
            "percent": percents,
            "sourceNote": f"JRC ASAP 국가 단위 수확기 적용 작물: {covered_crops}. {BASE_YEAR}년 FAOSTAT 곡물 생산의 {float(coverage * 100):.2f}%.",
        }
    note = (
        f"JRC ASAP의 국가 단위 수확 시작·종료 순(eos_s/eos_e, 연 36순, 양 끝 포함)에 {BASE_YEAR}년 FAOSTAT QCL "
        "작물 생산량(요소 5510, t)을 균등 배분했다. JRC는 FAO 달력과 원격 관측 생육기가 부합한 작물을 수록한다. "
        "국가 단위 표에서 작물명에 지역·작기 한정이 없는 행을 사용해 해당 작물의 전국 생산에 적용했다. "
        "나머지 곡물은 연중 균등 배분한다. 쌀은 벼 생산량 기준이며 전체 분모는 같은 QCL 곡물 합계(1717)다. "
        f"월별 비중은 추정치다. {ASAP} {QCL}"
    )
    if not rows:
        raise ValueError("게임 생산국과 일치하는 수확 달력이 없습니다.")
    file = WORLD_DIR / "grain_harvest_asap.json"
    file.write_text(json.dumps({"modelNote": note, "countries": dict(sorted(rows.items()))},
                               ensure_ascii=False, indent=1) + "\n", encoding="utf-8")
    print(f"수확 달력: 국가별 기존 {len(manual)}개, JRC·FAO 보완 {len(rows)}개")


if __name__ == "__main__":
    main()
