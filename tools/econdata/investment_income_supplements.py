"""국가별 공표 투자소득. 2023년 자료와 과거 명목 기준액의 연도·회계기간을 구분한다."""

from remittance_supplements import CFA_PER_USD_2023


def amounts(total, dividends=None, interest=None, reinvested=None, *, year=2023, rate=1.0, source="NATIONAL_BOP"):
    # 원표 단위는 백만 통화 단위, rate는 연평균 자국통화/달러. 누락된 세분은 공란으로 남긴다.
    def dollars(value):
        return round(value * 1_000_000 / rate) if value is not None else None

    return {
        "year": year, "source": source, "total": dollars(total),
        "dividends": dollars(dividends), "interest": dollars(interest), "reinvested": dollars(reinvested),
    }


def national_accounts():
    # BEAC 각국 2023 잠정 국제수지 TYPE 시트. C/D열(수취/지급) 백만 XAF.
    # 총계 80행, 배당·준법인 인출 83 + 100 + 102 + 108행, 이자 91 + 104 + 109 + 113행, 재투자 90 + 103행.
    # https://www.beac.int/statistiques/balance-des-paiements/
    cfa_accounts = {
        # https://www.beac.int/wp-content/uploads/2016/10/BDP_RCA_2023_16092025-1.xlsx
        "CAF": ((3622, 3663, 0, 0), (-3770, 4109 + 3663, 1638, -13180)),
        # https://www.beac.int/wp-content/uploads/2016/10/BDP_CONGO_2023_15092025.xlsx
        "COG": ((243526, 0, 30 + 132 + 243359, 0), (1171088, 573413 + 340 + 45, 47397 + 635426, -85533)),
        # https://www.beac.int/wp-content/uploads/2016/10/BDP_GABON_2023_09092026.xlsx
        "GAB": ((42689, 4962 + 147 + 13, 7425 + 194 + 19, 29921), (964195, 1097887 + 1559 + 326, 599 + 13729 + 186861, -336797)),
        # https://www.beac.int/wp-content/uploads/2016/10/BDP_GUINEE_EQUATORIALE_2023_08122025.xlsx
        "GNQ": ((5586, 124, 5462, 0), (613562, 501140 + 64921, 26 + 5854 + 25816, 15760)),
        # https://www.beac.int/wp-content/uploads/2016/10/BDP_TCHAD_2023.xlsx
        "TCD": ((108640, 52 + 8, 20, 108387), (794258, 96858 + 411800, 3998 + 64281, 217323)),
        # BCEAO 토고 부록 3의 1.B.2, 백만 XOF. 세부 투자 유형별 배당·이자·재투자 합계.
        # https://www.bceao.int/sites/default/files/2025-05/Rapport_balance_des_paiements-Togo_2023.pdf
        "TGO": ((134120, 25663 + 140 + 441, 8004 + 84929 + 6439, 8504), (157861, 27843, 5027 + 78436 + 30913, 15642)),
    }
    accounts = {
        code: {"received": amounts(*credit, rate=CFA_PER_USD_2023), "paid": amounts(*debit, rate=CFA_PER_USD_2023)}
        for code, (credit, debit) in cfa_accounts.items()
    }
    # 대만 중앙은행, 연간 국제수지 표 3의 2023r, 백만 USD. 포트폴리오 주식·펀드 합계는 배당만 분리할 수 없다.
    # https://www.cbc.gov.tw/public/data/economic/statistics/bop/english/eSY.pdf
    accounts["TWN"] = {
        "received": amounts(59_349, 16_115, 95 + 8_021, 3_191),
        "paid": amounts(33_360, 10_678, 280 + 386, -96),
    }
    # 브루나이 DEPS 부록 Table1 E16·E20(백만 BND), WDI PA.NUS.FCRF 2023 연평균 환율.
    # https://www.deps.gov.bn/wp-content/uploads/2026/10/BOP_H12026_APPX.xlsx
    # https://api.worldbank.org/v2/country/BRN/indicator/PA.NUS.FCRF?date=2023&format=json
    accounts["BRN"] = {
        "received": amounts(1284.9995134006147, rate=1.34307960114933),
        "paid": amounts(1011.24578115, rate=1.34307960114933),
    }
    # 베트남 당국·IMF 2025년 Article IV 표 3(십억 USD, 소수점 한 자리).
    # Investment income의 Receipts/Payments 행. 반올림한 양쪽의 차와 원표 순액은 0.1십억 USD 차이 난다.
    # https://www.imf.org/-/media/files/publications/cr/2025/english/1vnmea2025001-source-pdf.pdf
    accounts["VNM"] = {
        "received": amounts(4600, source="IMF_ARTICLE_IV"),
        "paid": amounts(27000, source="IMF_ARTICLE_IV"),
    }
    # 북키프로스 중앙은행 BPM6 상세표 2, C.2 투자소득 총액 및 2.3 기타투자 이자, 백만 USD.
    # https://www.kktcmerkezbankasi.org/sites/default/files/2024%20-%20%C3%96demeler%20Dengesi%20Tablolar%C4%B1-Ayr%C4%B1nt%C4%B1l%C4%B1%20Sunum.pdf
    accounts["CYN"] = {"received": amounts(422.61, interest=387.22), "paid": amounts(104.23, interest=64.30)}
    # 미크로네시아 통계청·EconMAP 표 12d BOPdet의 FY2023(2023년 9월 말), 백만 USD.
    # 지급 총액은 AE43의 본원소득에서 AE45 피용자보수와 AE47 어업권료를 차감해도 같다.
    # 수취 AE32는 원표 하위 항목의 합과 차이가 있어 AE35만으로 투자소득 총액을 단정할 수 없다.
    # https://pubs.pitiviti.org/fsm-fy24-economic-statistics-preliminary
    accounts["FSM"] = {
        "paid": amounts(24.34126049692639 + 0.627289418237, 24.34126049692639, 0.627289418237, source="NATIONAL_BOP_FY_SEPTEMBER"),
    }
    # 이란 중앙은행, 통계연감 1395 표 23.20, 백만 USD. 태양력 1392년(2013/14, 2014년 3월 종료).
    # 소득계정에서 피용자보수와 투자소득을 따로 집계한다. 이후 열의 말줄임표는 미보고다.
    # https://irandataportal.syr.edu/wp-content/uploads/National-Accounts-3.pdf
    accounts["IRN"] = {
        "received": amounts(2858, year=2014, source="NATIONAL_BOP_FY_MARCH"),
        "paid": amounts(886, year=2014, source="NATIONAL_BOP_FY_MARCH"),
    }
    return accounts


def national_net_income():
    # UAE 재무부의 2023년 전국 국제수지: 공공·민간을 포함한 투자소득 순유입 141억 AED.
    # 2025년 개편표는 피용자보수 등을 포함하는 본원소득만 구분하므로 이 순액과 섞지 않는다.
    # https://mof.gov.ae/wp-content/uploads/2024/10/Project-Tolerance-Supplement-Final.pdf
    # https://api.worldbank.org/v2/country/ARE/indicator/PA.NUS.FCRF?date=2023&format=json
    # 리히텐슈타인 표 1의 2023년 잠정 C열(백만 CHF). 거주경제 전체에서 국내 상호 지급은
    # 상쇄된다. D.4 순액(C66)에서 토지·자원 임대 순액(C70+C75)을 제외한다. D.43은 C68+C73.
    # https://www.statistikportal.li/statistikportal/publications/301-volkswirtschaftliche-gesamtrechnungen/2023/01/1/301.2023.02.1_02_volkswirtschaftliche-gesamtrechnung-2023-tabellen.xlsx
    # https://api.worldbank.org/v2/country/CHE/indicator/PA.NUS.FCRF?date=2023&format=json
    # https://www.elibrary.imf.org/display/book/9789211615227/ch026.xml
    return {
        # 투르크메니스탄 중앙은행·IMF 국가보고서 99/140 표 26(116쪽). 1996년 백만 USD.
        # 순이자 64.4 + 순배당·이윤 -44.9 = 19.5. 기타소득 0.0이고 피용자보수 -2.8은 제외한다.
        # 1997~1998년에는 'Other Income'에 분류를 확인할 수 없는 금액이 있다.
        # https://www.imf.org/external/pubs/ft/scr/1999/cr99140.pdf
        "TKM": {"year": 1996, "source": "IMF_COUNTRY_REPORT", "total": 19_500_000, "interest": 64_400_000, "reinvested": None},
        "ARE": {"year": 2023, "source": "NATIONAL_BOP", "total": round(14_100_000_000 / 3.6725), "interest": None, "reinvested": None},
        "LIE": {
            "year": 2023, "source": "NATIONAL_ACCOUNTS",
            "total": round((1572.73 - (6.82 - 6.69)) * 1_000_000 / 0.89849),
            "interest": None,
            "reinvested": round((1101.67 - 132.73) * 1_000_000 / 0.89849),
        },
    }
