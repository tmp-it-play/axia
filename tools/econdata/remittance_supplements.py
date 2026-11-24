"""2023년 국제수지에서 WDI 개인송금 정의로 합산한 연간 수취·지급액(미 달러)."""

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
