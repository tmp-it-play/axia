"""개인송금 연간 수취·지급액의 보완 자료. 단위는 미 달러다."""

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
}


# 세계은행 Migration and Development Brief 2025, 2025년 7월 13일자 연간 송금표.
# WDI·중앙은행 2023년 총액이 없었던 국가의 수취 행, Y열(2023년, 백만 USD).
# 은행이 과거 값을 연장한 BRB·FSM·YEM 계열도 추계다. 미보고 기간에 반복되는 0은 채택하지 않았다.
# https://datacatalogfiles.worldbank.org/ddh-published/0066877/DR0095407/Inward_remittance_flows_%28July_13_2025%29.xlsx
# 이란은 2023년 표에 신규 추계가 없어 Brief 37 그림 A.16의 2022년 추계(6억 달러)를 쓴다.
# https://documents1.worldbank.org/curated/en/099818008142419054/pdf/IDU12966350716a711437b1850019065d7f1eda1.pdf
WORLD_BANK_REMITTANCE_ESTIMATES = {
    "AFG": (2023, 320_000_000),
    "BRB": (2023, 85_175_893),
    "BRN": (2023, 1_000_000),
    "FSM": (2023, 23_338_700),
    "IRN": (2022, 600_000_000),
    "MMR": (2023, 1_100_000_000),
    "NZL": (2023, 906_000_000),
    "SOM": (2023, 2_181_300_000),
    "VNM": (2023, 14_000_000_000),
    "VUT": (2023, 144_967_341),
    "YEM": (2023, 3_770_584_000),
}

# 2023년 추계가 같은 금액을 반복하는 계열의 시작 연도(통계표 원값 기준).
UNCHANGED_ESTIMATE_SINCE = {"AFG": 2021, "BRB": 2017, "FSM": 2014, "YEM": 2016}


# 미크로네시아 통계청·EconMAP 국제수지 BOPdet, 2023년 9월 말 회계연도 AE열(백만 USD).
# 지급은 60행 가계송금과 45행 피용자보수. 수취에는 42행 외국 대사관 근로자 보수만 별도 표기했다.
# https://pubs.pitiviti.org/fsm-fy24-economic-statistics-preliminary
FISCAL_BOP_REMITTANCES = {
    "FSM": {"paid": round((8.880763788515965 + 3.3855037300000013) * 1_000_000)},
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
    # UAE 중앙은행 2023 금융안정보고서, 환전소가 처리한 개인송금 수취 120억 AED.
    # 은행 채널, 무역·투자 등 다른 용도 및 별도 피용자보수는 포함하지 않는다.
    # https://centralbank.ae/media/3hvhkdfs/cbuae-financial-stability-report-2023_a4_e.pdf
    # https://api.worldbank.org/v2/country/ARE/indicator/PA.NUS.FCRF?date=2023&format=json
    "ARE": {"received": (2023, round(12_000_000_000 / 3.6725), "EXCHANGE_HOUSES")},
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
