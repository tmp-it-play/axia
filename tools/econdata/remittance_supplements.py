"""개인송금 연간 수취·지급액의 보완 자료. 단위는 미 달러다."""

import csv
from decimal import Decimal
from pathlib import Path

# WDI는 경상이전 중 가계 간 개인이전과 피용자보수 총액을 같은 방향으로 합산한다.
# https://databank.worldbank.org/metadataglossary/world-development-indicators/series/BM.TRF.PWKR.CD.DT
# WDI의 2023 공식 연평균 환율. 아래의 XAF 5개국과 토고 XOF의 환율이 같다.
# https://api.worldbank.org/v2/country/CAF;COG;GAB;GNQ;TCD;TGO/indicator/PA.NUS.FCRF?date=2023&format=json
CFA_PER_USD_2023 = 606.569750165917


def _from_million_cfa(received, paid):
    return {
        "received": round(received * 1_000_000 / CFA_PER_USD_2023),
        "paid": round(paid * 1_000_000 / CFA_PER_USD_2023),
    }


BOP_REMITTANCES_USD_2023 = {
    # 중앙아프리카은행 각국 2023 잠정 국제수지 TYPE 시트. 126행 개인이전 + 79행 피용자보수.
    # 각 인수는 15행의 Crédit(수취), Débit(지급) 순서이며 13행 단위는 백만 XAF이다.
    # https://www.beac.int/wp-content/uploads/2016/10/BDP_RCA_2023_16092025-1.xlsx
    "CAF": _from_million_cfa(14_495 + 30_413, 3_420 + 3_925),
    # https://www.beac.int/wp-content/uploads/2016/10/BDP_CONGO_2023_15092025.xlsx
    "COG": _from_million_cfa(11_342 + 13_616, 25_953 + 37_490),
    # https://www.beac.int/wp-content/uploads/2016/10/BDP_GABON_2023_09092026.xlsx
    "GAB": _from_million_cfa(13_547 + 11_236, 75_617 + 46_069),
    # https://www.beac.int/wp-content/uploads/2016/10/BDP_GUINEE_EQUATORIALE_2023_08122025.xlsx
    "GNQ": _from_million_cfa(6_315 + 4_723, 26_191 + 45_727),
    # https://www.beac.int/wp-content/uploads/2016/10/BDP_TCHAD_2023.xlsx
    "TCD": _from_million_cfa(4_617 + 11_923, 18_361 + 8_688),
    # 서아프리카중앙은행의 토고 국제수지 부록 3, 1.C.2.1 개인이전 + 1.B.1 피용자보수.
    # 원표 단위는 백만 XOF.
    # https://www.bceao.int/sites/default/files/2025-05/Rapport_balance_des_paiements-Togo_2023.pdf
    "TGO": _from_million_cfa(454_250 + 29_992, 60_761 + 8_937),
    # 대만 중앙은행 국제수지 연간 표 3, 7쪽 2023r 열(백만 USD). 개인이전 + 피용자보수.
    # https://www.cbc.gov.tw/public/data/economic/statistics/bop/english/eSY.pdf
    "TWN": {"received": (4_202 + 1_215) * 1_000_000, "paid": (10_539 + 885) * 1_000_000},
    # 리비아 중앙은행 2023년 국제수지, 피용자보수 및 비정부 경상이전 수취 각 0.0백만 USD.
    # IMF BPM6 D1·D752_S1W CD_T의 보고값도 각 0이다. 지급은 19.4 + 4,800.0으로 WDI와 일치한다.
    # 미보고와 구분되는 리비아 측 보고액이다. 네덜란드·이탈리아 지급국의 거울통계와 차이가 있다.
    # https://cbl.gov.ly/balance-of-payment/
    # https://api.imf.org/external/sdmx/2.1/data/IMF.STA,BOP/LBY.CD_T.D1+D752_S1W.USD.A?startPeriod=2023&endPeriod=2023
    "LBY": {"received": 0},
}


# 세계은행 Migration and Development Brief 2025, 2025년 7월 13일자 연간 송금표.
# WDI·중앙은행 2023년 총액이 없었던 국가의 수취 행, Y열(2023년, 백만 USD).
# 은행이 과거 값을 연장한 BRB 계열도 추계다. 미보고 기간에 반복되는 0은 채택하지 않았다.
# https://datacatalogfiles.worldbank.org/ddh-published/0066877/DR0095407/Inward_remittance_flows_%28July_13_2025%29.xlsx
# 이란은 2023년 표에 신규 추계가 없어 Brief 37 그림 A.16의 2022년 추계(6억 달러)를 쓴다.
# https://documents1.worldbank.org/curated/en/099818008142419054/pdf/IDU12966350716a711437b1850019065d7f1eda1.pdf
WORLD_BANK_REMITTANCE_ESTIMATES = {
    "BRB": (2023, 85_175_893),
    "BRN": (2023, 1_000_000),
    "IRN": (2022, 600_000_000),
    "NZL": (2023, 906_000_000),
    "SOM": (2023, 2_181_300_000),
    "VUT": (2023, 144_967_341),
}

# 2023년 추계가 같은 금액을 반복하는 계열의 시작 연도(통계표 원값 기준).
UNCHANGED_ESTIMATE_SINCE = {"BRB": 2017}


# 미크로네시아 통계청·EconMAP 국제수지 BOPdet, 2023년 9월 말 회계연도 AE열(백만 USD).
# 지급은 60행 가계송금과 45행 피용자보수. 수취에는 42행 외국 대사관 근로자 보수만 별도 표기했다.
# https://pubs.pitiviti.org/fsm-fy24-economic-statistics-preliminary
FISCAL_BOP_REMITTANCES = {
    "FSM": {"paid": round((8.880763788515965 + 3.3855037300000013) * 1_000_000)},
}

# WDI 2023년 수취가 없는 국가의 해당 연도 이주민 송금 추계, 국제수지 구성액 또는 수취 하한.
RECEIPT_COMPONENTS_2023 = {
    # 세계은행 Afghanistan Development Update(2024년 12월), 26쪽. 해외 거주 아프간인의
    # 2023년 송금 추정치로 UN 현금 수송과 별도다. 이듬해 3월까지 포함한 FY2023과 기간이 다르다.
    # https://thedocs.worldbank.org/en/doc/126f9684f0c7ff20248c0c7bf45ccccd-0310012024/original/Afghanistan-Development-Update-December-2024-Final.pdf
    "AFG": (2_000_000_000, "MIGRANT_TRANSFER_ESTIMATE", "세계은행 · 해외 거주 아프간인의 연간 송금"),
    # 같은 미크로네시아 BOPdet의 AE55 가계송금 수취와 AE42 외국 공관 근로자 보수.
    "FSM": (round((43.38349643642729 + 2.01055) * 1_000_000), "NATIONAL_BOP_COMPONENTS",
            "9월 말 회계연도 · 가계송금과 외국 공관 근로자 보수"),
    # 미얀마 중앙은행 Quarterly Financial Statistics Bulletin 2024 Vol. IV, 9쪽.
    # 2023 Q1~Q4 경상이전 수취 중 Workers’ Remittance 행(백만 USD). 피용자보수는 별도다.
    # https://www.cbm.gov.mm/sites/default/files/upload2022/QFS_Bulletin_2024_Vol-IV.pdf
    "MMR": (round((884.8 + 732.5 + 178.9 + 214.9) * 1_000_000), "NATIONAL_BOP_COMPONENTS",
            "중앙은행 · 1~4분기 근로자송금 수취"),
    # 베트남 정부가 인용한 중앙은행의 2023년 전국 해외 송금 수취 추계. 호찌민시 액수는 포함된다.
    # https://en.baochinhphu.vn/remittances-to-ho-chi-minh-city-near-us74-bln-in-9-months-111241016221130023.htm
    "VNM": (16_000_000_000, "MIGRANT_TRANSFER_ESTIMATE", "베트남 중앙은행 · 해외 베트남인의 연간 송금"),
    # 예멘 중앙은행 2025 연보 52쪽 국제수지 개정표의 2023년 열(백만 USD). 순이전
    # 6,657.12 = 근로자송금 3,240.46 + 공여자 자금 3,416.66. 양방향이 분리되지 않아 수취 하한이다.
    # https://cby-ye.com/files/6a54b8310ee04.pdf
    "YEM": (3_240_460_000, "NATIONAL_BOP_COMPONENTS", "아덴 중앙은행 이전수지 중 근로자송금 · 수취 하한"),
}


# 정합적인 최근 총액도 양자 추계도 없는 방향에만 적용할 대용치.
# 북키프로스 2024년 개정 국제수지 상세표의 2023년 열, 백만 USD.
# 개인이전에서 공표된 금액은 순수취 100.15백만 달러다. 양방향 최소액인 수취 100.15, 지급 0에 각각
# 피용자보수 총액 38.56, 20.90을 더하면 양방향 하한과 공표 순액이 일치한다.
# https://www.kktcmerkezbankasi.org/sites/default/files/2024%20Y%C4%B1l%C4%B1%20%C3%96demeler%20Dengesi%20Ortak%20Bas%C4%B1n%20Duyurusu%2015%20Aral%C4%B1k%202025.pdf
PARTIAL_REMITTANCES = {
    "CYN": {
        "received": (2023, (100_150_000 + 38_560_000), "BOP_MINIMUM"),
        "paid": (2023, 20_900_000, "BOP_MINIMUM"),
    },
    # UAE WDI 국제수지 수취 개인이전. 전국 개인이전만 집계하며 피용자보수는 미보고다.
    # 환전소 경유금이나 가계 외 민간이전을 포함한 재무부 수치와 합산하지 않는다.
    # https://api.worldbank.org/v2/country/ARE/indicator/BX.TRF.PWKR.CD?date=2023&format=json
    "ARE": {"received": (2023, round(1_878_829_135.4663), "WDI_PERSONAL_TRANSFERS")},
    # ESAAMLG 에리트레아 상호평가 §69. 허가 환전·송금업자 경유 국경 간 개인 대 개인
    # 수취이며, 주로 해외 거주자의 가족 부양 목적이다. 다른 경로와 피용자보수는 별도다.
    # https://www.fatf-gafi.org/content/dam/fatf-gafi/fsrb-mer/Eritrea-MER-2025.pdf.coredownload.inline.pdf
    "ERI": {"received": (2023, 93_920_000, "LICENSED_REMITTANCES")},
    # UNCTAD가 유엔 사무총장 보고서 A/80/83 132쪽에서 제시한 쿠바 송금 수취 추계.
    # 송금 경로에 관한 조사 기반 추계이며 피용자보수를 별도로 포괄하지 않는다.
    # https://documents.un.org/api/symbol/access?l=es&s=A%2F80%2F83&t=pdf
    "CUB": {"received": (2023, 2_600_000_000, "UNCTAD_REMITTANCES")},
    # 세계은행 2024년 시리아 빈곤·형평성 진단 Box 4. 2022년 HNAP 전국 가구조사에
    # 근거한 국제송금 수취액(10.5억 USD)으로, 국내 가계 간 이전과 피용자보수는 제외한다.
    # https://documents1.worldbank.org/curated/en/099051624114042163/pdf/P1787061921ca30fc187161b7d84a5e4532.pdf
    "SYR": {"received": (2022, 1_050_000_000, "HOUSEHOLD_SURVEY")},
}


# 유로스타트 bop_rem6, 2023년 경상가격 백만 EUR. DEB는 보고국에서 상대국으로 지급,
# CRE는 보고국이 상대국으로부터 수취한 금액이다. 아래는 WDI 정의에 맞는 D1(피용자보수)과
# D752(가계 간 개인이전)의 양수 관측만이다. 항목 결측은 합계 밖에 남긴다.
# R1은 자본이전·사회부담금 등을 조정하는 별도 정의여서 사용하지 않는다.
# https://ec.europa.eu/eurostat/databrowser/view/bop_rem6/default/table?lang=en
# https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/bop_rem6?time=2023&currency=MIO_EUR
# ECB 연평균 환율(USD/EUR). 환산 뒤 한 경로를 한 번만 반올림한다.
# https://data.ecb.europa.eu/data/datasets/EXR/EXR.A.USD.EUR.SP00.A
USD_PER_EUR_2023 = 1.0813

# 스페인은 WDI의 양방향 금액이 피용자보수 항목 수준이다. bop_rem6의 ES, WRL_REST는
# D1과 D752를 모두 공표한다. D752의 일부인 근로자송금(D752W)은 총액에 다시 더하지 않는다.
# https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/bop_rem6?time=2023&currency=MIO_EUR&geo=ES&partner=WRL_REST
SPAIN_BOP_MILLION_EUR = {"received": 5_077 + 3_000, "paid": 550 + 11_099}

# 소말리아 중앙은행 2024년 6월 통계연보 표 3.1, 은행·송금업자의 2023년 개인송금
# 지급(Remittance) 12개월. 수취 합은 2,181.29백만 USD로 세계은행 추계와 반올림 단위에서 일치한다.
# 기업 지급·원조·기타 SWIFT 송금과 피용자보수는 포함하지 않는다. 소말릴란드 몫은 구분되지 않는다.
# https://centralbank.gov.so/wp-content/uploads/2024/12/Statistical-Bulletin_June-2024.pdf
SOMALIA_PERSONAL_PAYMENTS = round(sum([
    180.92, 156.19, 159.66, 132.02, 148.07, 151.45, 136.45, 152.30, 170.23, 170.07, 150.37, 145.32,
]) * 1_000_000)

# 에콰도르 중앙은행 RRpais_a(수취)·REpais_a(지급) 2023년 열, 단위 USD.
# 자국 표의 총액은 속령 등 게임에 별도 경제가 없는 상대를 포함한다. 원표의 ISO 숫자 코드를
# UN M49와 대조했다. 689는 Kosovo, 736은 Sudán으로 같은 표의 Sudán del Sur(728)와 구별된다.
# https://contenido.bce.fin.ec/documentos/Estadisticas/SectorExterno/BalanzaPagos/Remesas/Flujo_de_remesas_de_trabajadores.xlsx
ECUADOR_TOTALS = {"received": 5_447_489_436, "paid": 700_864_485}

# 수취국별로 실제 보고한 송금국의 관측액. EU27과 그 회원국을 함께 더하지 않는다.
# 이탈리아은행 trimestrali_paese의 이탈리아→BHR·MCO·PRK는 인가업체를 통한 개인 간
# 송금(D752W)이다. 다른 수취국에서 쓰는 유로스타트 이탈리아 D752에는 이를 다시 더하지 않는다.
# https://www.bancaditalia.it/statistiche/tematiche/rapporti-estero/rimesse-immigrati/rimesse.xlsx
# 유로스타트 VA는 교황청(바티칸 시국)이며 IT와 다르다. 프랑스 BOP와 통합되는 모나코는
# 이탈리아은행이 외국 수취지를 Principato di Monaco(MC)로 구분한 행만 쓴다.
# https://ec.europa.eu/eurostat/documents/39118/18471074/vademecum-balance-payments.pdf
INCOMING_PARTNERS_MILLION_EUR = {
    "BHR": {"NLD": 0.8 + 0.1, "ITA": 0.081 + 0.081 + 0.094 + 0.109},
    "LIE": {"AUT": 6.0, "DNK": 0.1, "NLD": 0.8, "SWE": 2.4, "BIH": 0.1,
            "CYP": 0.2, "HRV": 0.2, "ITA": 1.0},
    "MCO": {"ITA": 0.001 + 0.001},
    "PRK": {"NLD": 0.3, "ITA": 0.001},
    "SGP": {"ALB": 0.1, "BEL": 16.0, "BIH": 0.2, "CZE": 0.2, "DNK": 13.4, "EST": 0.1,
            "GRC": 0.9 + 1.0, "FIN": 2.0 + 1.0, "FRA": 26.0, "ITA": 8.8 + 0.8,
            "MLT": 0.8, "NLD": 16.9 + 0.2, "SWE": 0.6, "CYP": 9.6,
            "DEU": 1.0, "HRV": 0.4, "SRB": 1.0},
    "VAT": {"NLD": 0.3},
}
PARTNER_COVERAGE = {
    "BHR": "네덜란드 피용자보수·개인이전, 이탈리아 인가업체 개인송금, 에콰도르 근로자송금",
    "LIE": "EU 27개국과 보스니아 헤르체고비나에서 지급한 피용자보수·개인이전",
    "MCO": "이탈리아 인가업체의 대모나코 개인송금",
    "PRK": "네덜란드 피용자보수와 이탈리아 인가업체 개인송금",
    "SGP": "EU 27개국과 알바니아·보스니아 헤르체고비나·세르비아에서 지급한 피용자보수·개인이전",
    "VAT": "네덜란드가 바티칸 시국에 지급한 피용자보수",
}

# 리히텐슈타인→상대국을 수취국이 신고한 CRE. 고용주 거주지는 리히텐슈타인이다.
LIE_OUTGOING_MILLION_EUR = {
    "ALB": 0.7 + 0.1, "AUT": 573.0 + 2.0, "GRC": 0.3, "NLD": 2.3, "KOS": 0.1 + 0.2,
    "BIH": 2.1, "CZE": 0.2, "HRV": 0.2, "HUN": 0.1, "ITA": 0.3, "LVA": 4.0, "SRB": 2.0,
}

# 수취국의 CRE(피용자보수 D1과 가계 간 개인이전 D752). 스페인 지급은 자국이
# 상대까지 명시한 근로자송금 D752W만 사용한다. 그 밖의 나라와 EU27 합계는 별도다.
OTHER_OUTGOING_MILLION_EUR = {
    "BHR": {"NLD": 1.5 + 0.1},
    "LBY": {"NLD": 1.1},
    "SGP": {"ALB": 1.0 + 0.1, "BEL": 6.0, "BIH": 4.0, "CYP": 0.4, "DEU": 5.0,
            "DNK": 14.1, "EST": 3.9, "FIN": 2.0, "FRA": 13.0, "GRC": 6.0 + 0.1,
            "HRV": 10.8 + 9.9, "ITA": 2.2, "KOS": 0.7 + 0.1, "LTU": 0.1,
            "LVA": 65.0 + 31.0, "MNE": 24.0 + 1.0, "NLD": 43.0, "SRB": 3.0 + 3.0, "SWE": 0.9},
    "ESP": {"MAR": 1_405.0, "ROU": 313.0},
}


# 도미니카공화국 중앙은행 2023년 공식 채널 가계송금. 연간 국가별 거래 건수(TR의 O열)에
# 같은 나라의 건당 평균 달러(PT의 O열, 셀 원값)를 곱해 달러로 반올림했다. 공식 채널 전체
# 금액에 송금국 비중(PE의 O열)을 곱해도 각 금액이 일치한다. PRI는 미국과 별도 보고된다.
# https://cdn.bancentral.gov.do/documents/estadisticas/sector-externo/documents/Remesas_TR.xlsx
# https://cdn.bancentral.gov.do/documents/estadisticas/sector-externo/documents/Remesas_PT.xlsx
# https://cdn.bancentral.gov.do/documents/estadisticas/sector-externo/documents/Remesas_PE.xlsx
DOMINICAN_FORMAL_RECEIPTS = {
    "USA": 7_586_636_996, "ESP": 532_546_128, "ITA": 72_484_986, "HTI": 92_413_340,
    "CHE": 62_376_984, "PRI": 61_139_879, "PAN": 21_429_888, "DEU": 32_802_451,
    "FRA": 40_297_580, "CAN": 62_214_220,
}


def partner_benchmarks():
    """확인된 상대국별 달러 금액과, 상대 미배정 EU 합계를 포함한 부분 기준액을 만든다."""
    def usd(million_eur):
        return round(million_eur * 1_000_000 * USD_PER_EUR_2023)

    routes = {}
    benchmarks = {}
    for sender, amount in DOMINICAN_FORMAL_RECEIPTS.items():
        if sender != "PRI":
            routes[sender, "DOM"] = amount
    with Path(__file__).with_name("ecuador_remittances_2023.csv").open(encoding="utf-8") as table:
        for row in csv.DictReader(table):
            partner = row["partner"]
            for payer, payee, column in [(partner, "ECU", "received_by_ecuador_usd"), ("ECU", partner, "paid_by_ecuador_usd")]:
                if row[column]:
                    value = round(Decimal(row[column]))
                    if value <= 0 or (payer, payee) in routes or payer == payee:
                        raise ValueError(f"에콰도르 중앙은행 경로 오류: {payer}, {payee}")
                    routes[payer, payee] = value
    for payee, senders in INCOMING_PARTNERS_MILLION_EUR.items():
        for payer, euros in senders.items():
            routes[payer, payee] = usd(euros)
        total = sum(routes[payer, payee] for payer in senders)
        if payee == "BHR":
            total += routes["ECU", payee]
        if payee == "SGP":
            # EU27 합계 중 비공개·미보고 회원국의 상대를 특정할 수 없는 금액도 포함된다.
            # EU27(DEB D1 93.8 + D752 80.0)에 EU 밖의 ALB·BIH·SRB만 추가한다.
            total = usd(93.8 + 80.0) + sum(routes[p, payee] for p in ("ALB", "BIH", "SRB"))
        benchmarks[payee, "received"] = {
            "usd": total, "year": 2023, "source": "PARTNER_REPORTS", "scope": PARTNER_COVERAGE[payee],
        }
    for payee, euros in LIE_OUTGOING_MILLION_EUR.items():
        routes["LIE", payee] = usd(euros)
    for payer, recipients in OTHER_OUTGOING_MILLION_EUR.items():
        for payee, euros in recipients.items():
            routes[payer, payee] = usd(euros)
    # VGR 2023, 표 1 C34 국내 사업체 피용자보수 4,531.74m CHF, C54 국내 거주자
    # 피용자보수 2,459.94m CHF. 같은 국내 근로 대가를 상쇄하면 국외 순지급 2,071.80m CHF.
    # 이 순지급만으로 확인되는 2023년 지급 하한이 2021년 KNOMAD 지급 추계보다 크다.
    # 여기에 EU27에서 받은 피용자보수 9.3m EUR와 수취국이 확인한 가계 간 지급도 더한다.
    # D752 CRE는 EU27 9.7m + 비회원국 ALB 0.1, BIH 2.1, SRB 2.0, KOS 0.2m EUR다.
    # https://www.statistikportal.li/statistikportal/publications/301-volkswirtschaftliche-gesamtrechnungen/2023/01/1/301.2023.02.1_02_volkswirtschaftliche-gesamtrechnung-2023-tabellen.xlsx
    # https://api.worldbank.org/v2/country/CHE/indicator/PA.NUS.FCRF?date=2023&format=json
    benchmarks["LIE", "paid"] = {
        "usd": (round((4531.74 - 2459.94) * 1_000_000 / 0.89849) +
                usd(9.3 + 9.7 + 0.1 + 2.1 + 2.0 + 0.2) + routes["LIE", "ECU"]),
        "year": 2023,
        "source": "WAGE_PAYMENT_FLOOR",
    }
    # 에콰도르 중앙은행 RRpais_a M218, ISO 336 Santa Sede (Vaticano). 2016년 수취 350 USD.
    # 이 수취국의 이후 연도는 공란이다. 필리핀은 2023년 자국 은행 경유 금액을 천 USD 단위 0으로
    # 공표했다. 서로 다른 연도를 합산하거나 이 두 경로로 바티칸의 전체 지급액을 정하지 않는다.
    # https://contenido.bce.fin.ec/documentos/Estadisticas/SectorExterno/BalanzaPagos/Remesas/Flujo_de_remesas_de_trabajadores.xlsx
    # https://www.bsp.gov.ph/Statistics/External/ofw2.aspx
    # 2016년 자료는 바티칸의 대용치로만 남긴다. 같은 경로가 공란인 에콰도르 2023년 수취에는 배정하지 않는다.
    benchmarks["VAT", "paid"] = {
        "usd": 350, "year": 2016, "source": "PARTNER_REPORTS",
        "scope": "에콰도르 수취 신고 경로만 · 2023년 에콰도르 미보고, 필리핀 은행 수취액은 천 달러 단위 0",
    }
    return benchmarks, routes
