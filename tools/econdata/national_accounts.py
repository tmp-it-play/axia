"""UNSD와 대만 주계총처의 당해가격 자국통화 국민계정에서 2023년 교역·산업 비중을 구한다."""

import io
import math
import xml.etree.ElementTree as ET
from zipfile import ZipFile

from build_economy import BASE_YEAR, download
from un_m49 import game_codes_by_m49

CURRENT_NCU = "https://unstats.un.org/unsd/amaapi/api/file/1"
GDP = "Gross Domestic Product (GDP)"
EXPORTS = "Exports of goods and services"
COMPONENTS = {
    "exportShare": [EXPORTS],
    "tradeOpenness": [EXPORTS, "Imports of goods and services"],
    "agricultureValueAdded": ["Agriculture, hunting, forestry, fishing (ISIC A-B)"],
    "industryValueAdded": ["Mining, Manufacturing, Utilities (ISIC C-E)", "Construction (ISIC F)"],
    "manufacturingValueAdded": ["Manufacturing (ISIC D)"],
    "servicesValueAdded": [
        "Wholesale, retail trade, restaurants and hotels (ISIC G-H)",
        "Transport, storage and communication (ISIC I)",
        "Other Activities (ISIC J-P)",
    ],
}


def unsd_accounts(cache_dir):
    codes = game_codes_by_m49(cache_dir)
    data = download(CURRENT_NCU, cache_dir / "unsd-ama-current-ncu.xlsx" if cache_dir else None)
    indicators = {GDP} | {item for items in COMPONENTS.values() for item in items}
    accounts = {}
    currencies = {}
    with ZipFile(io.BytesIO(data)) as book:
        strings = ["".join(item.itertext()) for item in ET.fromstring(book.read("xl/sharedStrings.xml"))]
        sheet = ET.fromstring(book.read("xl/worksheets/sheet1.xml"))
        rows = sheet.iterfind(".//{*}sheetData/{*}row")

        def cells(row):
            values = {}
            for cell in row:
                value = cell.findtext("{*}v")
                if value is not None:
                    column = cell.attrib["r"].rstrip("0123456789")
                    values[column] = strings[int(value)] if cell.get("t") == "s" else value
            return values

        if cells(next(rows)).get("A") != "GDP/breakdown at current prices in National currency (all countries)":
            raise ValueError("UNSD 표의 범위·가격·통화 단위를 확인하세요.")
        headers = cells(next(rows))
        if [headers.get(c) for c in "ABCD"] != ["CountryID", "Country", "Currency", "IndicatorName"]:
            raise ValueError("UNSD 국민계정 열 구조가 바뀌었습니다.")
        (year_col,) = [col for col, label in headers.items() if label == str(BASE_YEAR)]
        for row in rows:
            record = cells(row)
            item = record.get("D")
            if year_col not in record or item not in indicators:
                continue
            country = codes.get(record["A"].zfill(3))
            # 소말리아 원표에서는 게임에서 따로 계산하는 소말릴란드 지역을 분리할 수 없다.
            if not country or country == "SOM":
                continue
            value = float(record[year_col])
            bundle = accounts.setdefault(country, {})
            if item in bundle or not math.isfinite(value):
                raise ValueError(f"UNSD {country}: invalid or duplicate {item}: {value}")
            if currencies.setdefault(country, record["C"]) != record["C"]:
                raise ValueError(f"UNSD {country}: inconsistent currency")
            bundle[item] = value

    result = {}
    for country, values in accounts.items():
        if values.get(GDP, 0) <= 0:
            continue
        shares = {
            field: sum(values[item] for item in items) / values[GDP]
            for field, items in COMPONENTS.items() if all(item in values for item in items)
        }
        # 부가가치는 손실 등으로 음수가 될 수 있다. 비음수만 받는 산업 모형에는 해당 비중을 쓰지 않는다.
        result[country] = {field: share for field, share in shares.items() if share >= 0}
    return result


def taiwan_accounts(cache_dir):
    """대만 주계총처 연간 생산·지출 국민계정. 두 표 모두 당해가격 백만 신대만달러다."""
    base_url = "https://ws.dgbas.gov.tw/001/Upload/461/relfile/11525/"

    def series(path, dataset_id, prefix):
        raw = download(base_url + path, cache_dir / f"taiwan-{dataset_id}.xml" if cache_dir else None)
        values = {}
        for row in ET.fromstring(raw).iter("Obs"):
            if row.findtext("TIME_PERIOD") != str(BASE_YEAR) or row.findtext("FREQ") != "Y" or row.findtext("TYPE") != "原始值":
                continue
            key = row.findtext("Item", "")
            value = row.findtext("Item_VALUE")
            if key.startswith(prefix) and value:
                key = key.removeprefix(prefix)
                if key in values:
                    raise ValueError(f"대만 국민계정 중복: {key}")
                values[key] = int(value)
        return values

    output = series("230912/na8103a1a.xml", "44222", "國內生產毛額_當期價格(新臺幣百萬元)、")
    spending = series("230514/na8102a5a.xml", "44224", "當期價格(新臺幣百萬元)、")
    gdp = output["GDP"]
    if gdp <= 0 or gdp != spending["8.GDP"]:
        raise ValueError("대만 생산·지출 국민계정 GDP 기준을 확인하세요.")
    exports = spending["4.商品及服務輸出:4.1--4.2合計"]
    imports = spending["5.商品及服務輸入:5.1--5.2合計"]
    manufacturing = output["C.製造業"]
    # 대만의 서비스 합계에 포함된 수입관세와 부가가치세를 빼야 서비스 부가가치가 된다.
    services = output["按三級產業分:服務業"] - output["進口稅"] - output["加值型營業稅"]
    return {
        "exportShare": exports / gdp,
        "tradeOpenness": (exports + imports) / gdp,
        "agricultureValueAdded": output["A.農、林、漁、牧業"] / gdp,
        "industryValueAdded": output["按三級產業分:工業"] / gdp,
        "manufacturingValueAdded": manufacturing / gdp,
        "servicesValueAdded": services / gdp,
        "foodManufacturing": (output["CA.食品及飼品製造業"] + output["CB.飲料及菸草製造業"]) / manufacturing,
        "textileManufacturing": (output["CC.紡織業"] + output["CD.成衣及服飾品製造業"] + output["CE.皮革、毛皮及其製品製造業"]) / manufacturing,
    }
