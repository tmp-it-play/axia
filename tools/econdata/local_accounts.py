"""북키프로스와 소말릴란드 공식 발표를 이용한 2023년 경제구조 비중."""

from local_macro import GDP_USD_2023


def load_local_accounts() -> dict[str, dict[str, float]]:
    # 북키프로스 통계청 2024년 국민계정 표 1. 2023년 당해가격 리라 기준.
    # 4–9행은 서비스업이고 10행의 수입세는 부가가치 합계에서 제외한다.
    # https://istatistik.gov.ct.tr/Portals/39/Tablo1_GSMH_2024_1.pdf
    cy_gdp_lira = 132_350_516_376.8
    cy_services_lira = sum((
        34_122_387_476.9,  # 상업·숙박·음식
        13_739_428_163.5,  # 운수·통신
        12_035_343_369.3,  # 금융
        7_563_270_281.3,   # 주택소유
        13_438_544_422.4,  # 전문서비스·고등교육
        17_123_591_349.3,  # 공공서비스
    ))

    cy_gdp_usd_millions = GDP_USD_2023["CYN"] / 1_000_000
    # 북키프로스 중앙은행 2024년 국제수지(BPM6) 분석표 1, 2023년 열.
    # 상품 수출입에 서비스 수입(受入)·지급을 각각 더한다. 단위는 백만 달러.
    # https://www.kktcmerkezbankasi.org/sites/default/files/2024%20-%C3%96demeler%20Dengesi%20Tablolar%C4%B1-Analitik%20Sunum.pdf
    cy_exports = 183.09 + 2_837.81
    cy_imports = 2_849.65 + 400.45

    # 소말릴란드 통계청 2024년 GDP 보고서 표 1. 상품·서비스 수출입과 구매자가격
    # GDP 모두 2023년 당해가격 백만 달러. 기초 자료 제약에 따른 공식 실험적 추계다.
    # https://www.govsomaliland.org/uploads/files/2025/09/2025-09-06-05-36-34-2988-1757136994.pdf
    sl_gdp_usd_millions = GDP_USD_2023["SOL"] / 1_000_000
    sl_exports = 586
    sl_imports = 1_480

    return {
        "CYN": {
            "tradeOpenness": (cy_exports + cy_imports) / cy_gdp_usd_millions,
            "exportShare": cy_exports / cy_gdp_usd_millions,
            "agricultureValueAdded": 7_020_269_391.4 / cy_gdp_lira,
            "industryValueAdded": (7_426_399_815.9 + 7_953_616_188.2) / cy_gdp_lira,
            "manufacturingValueAdded": 2_918_335_930.1 / cy_gdp_lira,
            "servicesValueAdded": cy_services_lira / cy_gdp_lira,
        },
        "SOL": {
            "tradeOpenness": (sl_exports + sl_imports) / sl_gdp_usd_millions,
            "exportShare": sl_exports / sl_gdp_usd_millions,
        },
    }
