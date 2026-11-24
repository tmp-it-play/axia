"""WDI 결측을 보완하는 2023년 연령·인구동태·고용 통계. 비율은 0~1, 순이동은 연간 명."""

# 북키프로스 통계연감 B2(12월 31일 추계), 대만 내정부 2023년 12월 말 주민등록.
# 소말릴란드 제3차 국가개발계획 부록 5, 230~231쪽의 2023년 전국 1세별 남녀 합계 추계.
# 65세 이상은 전체에서 0~64세 합을 뺀다. 공표 연령표의 마지막 행은 75세 표기다.
# 각 행은 0~14세, 65세 이상, 같은 기준일·범위의 전체 인구(명).
# https://istatistik.gov.ct.tr/Portals/39/ISTATISTIK_YILLIGI_2023_1.pdf
# https://www.ris.gov.tw/info-liferay/app/channel/newsEnglishDetail/24010759
# https://faolex.fao.org/docs/pdf/som229050.pdf
AGE_GROUPS = {
    "CYN": (22_135 + 21_345 + 20_654, 14_644 + 11_024 + 8_390 + 4_483 + 3_728, 476_214),
    "SOL": (732_859 + 676_072 + 616_051, 4_549_458 - 4_475_497, 4_549_458),
    "TWN": (2_793_413, 4_296_985, 23_420_442),
}

# 북키프로스 통계연감 B4의 조출생률·조사망률(인구 천 명당), 남녀 합계 출생시 기대수명.
# 2023년판 B4의 합계출산율 칸은 비어 있어 2024년판 B4의 2023년 공표값을 쓴다.
# https://istatistik.gov.ct.tr/Portals/39/ISTATIISTIK_YILLIK_2024_1.pdf
# 대만 내정부 2023년 4분기 인구통계(조출생·조사망률)와 2023년 간이생명표.
# 합계출산율은 대만 정부 인권규약 제4차 국가보고서 인구표(2023년, 여성 1명당).
# 대만 순이동은 UN WPP 2024 Demographic Indicators Medium, 2023년 NetMigrations(천 명).
# 대만·바티칸 도시 비율은 UN WUP 2025 F15, Urban 시트, 2023년 연중 국가별 도시 정의 기준(%).
# https://www.ris.gov.tw/documents/data/5/2/DemographicQuarterly_Winter2023.pdf
# https://ws.mofa.gov.tw/001/Upload/402/relfile/20/1217/88666274-860e-4eab-aa5f-8dc213cc8fc4.pdf
# https://www.moi.gov.tw/News_Content.aspx?n=2&s=320039&sms=9009
# https://population.un.org/wpp/downloads?folder=Standard%20Projections&group=Most%20used
# https://population.un.org/wup/assets/Download/Countries%20and%20Aggregates/WUP2025-F15-National-Definitions_percPop_by_category.xlsx
DEMOGRAPHY = {
    "CYN": {"fertility": 1.53, "birthRate": 7.7 / 1000, "deathRate": 3.61 / 1000, "lifeExpectancy": 81.8},
    "TWN": {
        "fertility": 0.87, "birthRate": 5.81 / 1000, "deathRate": 8.80 / 1000,
        "lifeExpectancy": 80.23, "netMigration": -32_777, "urbanShare": 78.9018184954659 / 100,
    },
    "VAT": {"urbanShare": 1.0},
}

# 북키프로스 10월 가구노동력조사 표 2(명). 산업에는 건설을 포함한다.
# 대만 주계총처 12월 인력조사 월보 표 7·9의 2023년 연평균 비중(%). 조사 범위는 대만·펑후.
# 두 통계의 자영업에는 고용주·자기계정 노동자·무급 가족종사자를 포함한다.
# https://istatistik.gov.ct.tr/Portals/39/HIA_BULTEN_2023_merged_1.pdf
# https://ws.dgbas.gov.tw/001/Upload/463/ebook/ebook_272139/pdf/full.pdf
#
# 그 밖의 행은 ILOSTAT 국가별 조사(천 명). 전체 성별, 2023년 연간치.
# GRD BA:1193, KIR BB:15723, KOS BA:700, ATG BA:16714, PLW BB:14046, SYC BA:2524.
# 세 산업 합계가 공표 전체와 반올림 오차 내에서 일치하는 표만 세 부문 모형에 사용한다.
# 자영업 분모에는 종사상 지위 미분류자도 포함한다(공표 SLF / TOTAL).
# https://rplumber.ilo.org/data/indicator/?id=EMP_TEMP_SEX_ECO_NB_A
# https://rplumber.ilo.org/data/indicator/?id=EMP_TEMP_SEX_STE_NB_A
EMPLOYMENT_SECTORS = {
    # 농업, 산업, 서비스, 전체
    "CYN": (4_413, 14_057 + 16_973, 112_768, 148_211),
    "TWN": (4.42, 35.08, 60.50, 100),
    "GRD": (3.836, 7.133, 41.345, 52.315),
    "KIR": (9.982, 6.141, 25.161, 41.283),
    "KOS": (17.975, 106.426, 313.676, 438.077),
}

SELF_EMPLOYMENT = {
    "CYN": (21_802 + 1_162, 148_211),
    "TWN": (3.92 + 11.23 + 4.12, 100),
    "ATG": (10.421, 56.003), "GRD": (11.996, 52.315), "KIR": (18.099, 41.283),
    "KOS": (103.039, 438.077), "PLW": (0.770, 9.570), "SYC": (9.222, 50.718),
}

# ILOSTAT, 15세 이상 남녀 전체 경제활동인구 대비 실업률(%), 위와 같은 소스 코드.
# ATG의 T5:1429는 미취업 및 구직 중이라는 두 조건으로 집계한 국가 조사 정의다.
# https://rplumber.ilo.org/data/indicator/?id=UNE_DEAP_SEX_AGE_RT_A
ILO_UNEMPLOYMENT_PERCENT = {"ATG": 5.37, "GRD": 6.34, "KIR": 2.786, "PLW": 2.091}


def society_indicators(country):
    values = {}
    if country in AGE_GROUPS:
        children, elderly, total = AGE_GROUPS[country]
        values.update(childShare=children / total, elderlyShare=elderly / total)
    if country in EMPLOYMENT_SECTORS:
        agriculture, industry, services, total = EMPLOYMENT_SECTORS[country]
        values.update(agricultureEmployment=agriculture / total, industryEmployment=industry / total,
                      servicesEmployment=services / total)
    if country in SELF_EMPLOYMENT:
        workers, total = SELF_EMPLOYMENT[country]
        values["selfEmployment"] = workers / total
    if country in ILO_UNEMPLOYMENT_PERCENT:
        values["unemployment"] = ILO_UNEMPLOYMENT_PERCENT[country] / 100
    return values
