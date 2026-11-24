"""WDI에 없는 산업구조 지표의 원자료. 비중의 분자와 분모는 같은 표·연도에서 가져온다."""

# ADB 74개 경제 MRIO, 2023년 당해가격, 2025년 7월 판본, 백만 달러.
# https://kidb.adb.org/download/mrio-file/430
# 첫 시트의 r64(기초가격 부가가치) 행, 열의 국가·업종은 6·7행에서 확인한다.
# 순서대로 c3(식음료·담배), c4(섬유제품), c5(가죽제품·신발), c3~c16 합(제조업).
# 원본에 실제 기재된 0도 보존한다. ADB가 국민계정과 산업연관표를 조정한 추계다.
ADB_MANUFACTURING = {
    "BRN": (31.775634906, 27.914518450, 0, 2816.557056227),  # BRU
    "BTN": (37.807348683, 10.710370633, 1.410459505, 242.916513886),  # BHU
    "KHM": (1899.918770224, 4791.571271473, 0, 11148.219815473),  # CAM
    "LAO": (499.636110495, 149.745214441, 24.545137296, 1502.187228793),
    "MDV": (67.235185444, 21.884890915, 0, 131.452283124),  # MLD
    "PAK": (13485.746475619, 8116.952528083, 364.578160517, 40718.413208847),
    "TJK": (710.076550750, 219.600439717, 6.820372838, 1748.472152283),  # TAJ
}


def structure_supplements():
    shares = {
        country: {"foodManufacturing": food / total, "textileManufacturing": (textiles + leather) / total}
        for country, (food, textiles, leather, total) in ADB_MANUFACTURING.items()
    }

    # 나이지리아 통계청, 2019년 기준 개편 국민계정, GDP_Current% 시트(2023년 F열, 백만 나이라).
    # 식음료·담배 F17, 전체 제조업 SUM(F15:F27). 섬유 부문은 가죽제품 전체 포함 여부 미확인.
    # https://microdata.nigerianstat.gov.ng/index.php/catalog/147/download/1257
    shares["NGA"] = {"foodManufacturing": 8_988_827.205591928 / 29_119_725.25062728}

    # OECD TiVA 2025판, 2022년 기초가격 부가가치(백만 달러). VALU, 세계 상대 W,
    # ISIC 4의 C10T12(식음료·담배)·C13T15(섬유·의복·가죽) / C(제조업).
    # https://sdmx.oecd.org/sti-public/rest/data/OECD.STI.PIE,DSD_TIVA_MAINLV@DF_MAINLV,1.1/VALU.AGO+CMR+COD+NGA+STP.C+C10T12+C13T15.W.USD.A?startPeriod=2022&endPeriod=2022
    shares["AGO"] = {"foodManufacturing": 1417.959 / 6711.697, "textileManufacturing": 684.184 / 6711.697}
    shares["CMR"] = {"textileManufacturing": 1362.373 / 6196.789}
    shares["COD"] = {"textileManufacturing": 747.971 / 12394.474}
    shares["NGA"]["textileManufacturing"] = 5755.174 / 63036.174
    shares["STP"] = {"foodManufacturing": 11.396 / 12.405, "textileManufacturing": 0.044 / 12.405}

    # UNIDO INDSTAT ISIC Rev.3, 변수 20(당해가격 자국통화 부가가치), 분모 D(전체 제조업).
    # 콩고공화국 2022년 15A는 16(담배)을 포함한다. 에리트레아 2023년 17~19에는 가죽도 포함된다.
    # https://stat.unido.org/portal/dataset/getDataset/INDSTAT/3
    shares["COG"] = {"foodManufacturing": 497_100_000_000 / 782_700_000_000}
    shares["ERI"] = {"textileManufacturing": (160_815_566 + 13_365_998 + 12_668_941) / 1_229_173_540}

    # UNIDO SDG 9.b.1, NV_IND_TECH: 2019~2023년 창에서 가장 최근 보고된 백분율.
    # https://stat.unido.org/unido-statistics-portal-api
    shares["KOS"] = {"techManufacturing": 3.7 / 100}  # 2019, UNIDO 412 (XKX)
    shares["MLI"] = {"techManufacturing": 4.8 / 100}  # 2022
    shares["TGO"] = {"techManufacturing": 13.3 / 100}  # 2021
    shares["TKM"] = {"techManufacturing": 8.3 / 100}  # 2020

    # WDI의 석유 지대는 원유 생산량 × (지역 가격 - 단위 생산비)다.
    # EIA 국제통계 2023년 annual, productId=57(콘덴세이트 포함 원유), activityId=1(생산),
    # unit=TBPD에서 두 나라 모두 value="0", dataFlagId=null. 누락·반올림 표시는 제외했다.
    # https://www.eia.gov/opendata/browser/international
    shares["ERI"]["oilRents"] = 0.0
    shares["PRK"] = {"oilRents": 0.0}
    return shares
