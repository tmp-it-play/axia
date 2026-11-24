"""WEO·WDI 결측을 보완하는 기관별 2023년 거시경제 통계와 시계열."""

# 명목 국내총생산(당해가격 달러). 북키프로스 국가계획기구 2025–27년 중기계획 부표 1, 5,556.7백만 달러.
# https://www.devplan.org/arsiv/programlar/OVP-2025-27.pdf
# 소말릴란드 통계청 2024년 GDP 보고서 표 1, 구매자가격 GDP 3,977백만 달러. 지출 기준 실험적 추계.
# https://www.govsomaliland.org/uploads/files/2025/09/2025-09-06-05-36-34-2988-1757136994.pdf
# UNSD 국민계정 주요 집계표, 당해가격 달러, 북한(M49 408). 시장환율이 확인되지 않아
# UN이 기관 회계환율 110 북한원/달러로 환산한 금액이다.
# https://unstats.un.org/unsd/amaapi/api/file/2
# https://unstats.un.org/unsd/snaama/CountryProfile?ccode=408
GDP_USD_2023 = {"CYN": 5_556_700_000, "SOL": 3_977_000_000, "PRK": 16_446_632_290.336363}

# 연도별 실질 GDP 전년 대비 증감률(%).
# 북키프로스 국가계획기구 2024년 거시·부문별 경제 보고서 그림 1.1.1, 1977년 불변가격.
# https://www.devplan.org/arsiv/Yayinlar/makro-eko-2024-1771243666188.pdf
# 소말릴란드 통계청 2024년 GDP 보고서(위) 표 4, 2017년 불변가격.
# 북한은 한국은행이 한국 가격·부가가치율로 추계한 성장률(2020년 기준년)을 쓴다.
# UNSD 명목 GDP 시계열과 작성 기관·가격 기준이 다르다.
# https://www.bok.or.kr/portal/bbs/P0002240/view.do?menuNo=500766&nttId=10085023
# https://www.bok.or.kr/portal/bbs/B0000501/view.do?menuNo=201264&nttId=10086115
REAL_GROWTH_PERCENT = {
    "CYN": {
        2010: 3.69, 2011: 3.87, 2012: 1.82, 2013: 1.06, 2014: 4.75, 2015: 4.00, 2016: 3.64,
        2017: 5.45, 2018: 1.34, 2019: 0.27, 2020: -16.23, 2021: 3.91, 2022: 13.29, 2023: 7.30,
    },
    "SOL": {
        2013: 2.3, 2014: 4.9, 2015: 1.5, 2016: 1.7, 2017: -1.1, 2018: 2.5,
        2019: 6.3, 2020: -3.2, 2021: 8.2, 2022: -1.6, 2023: 3.3,
    },
    "PRK": {
        2010: -0.5, 2011: 0.8, 2012: 1.3, 2013: 1.1, 2014: 1.0, 2015: -1.1, 2016: 3.9,
        2017: -3.5, 2018: -4.1, 2019: 0.4, 2020: -4.5, 2021: -0.1, 2022: -0.2, 2023: 3.1,
    },
}

# 북키프로스 통계연감 2023년판 표 B4. 개정된 7월 1일 전국 인구추계.
# https://istatistik.gov.ct.tr/Portals/39/ISTATISTIK_YILLIGI_2023_1.pdf
# 소말릴란드 제3차 국가개발계획 부록의 전국 연간 인구추계. 2014년 인구표본조사(PESS)에
# 근거하며 연중 특정 기준일은 명시되지 않았다. GDP 보고서의 반올림된 1인당 GDP와는
# 분모가 다르므로 그 수치로 인구를 역산하지 않는다.
# https://www.govsomaliland.org/uploads/files/2023/02/2023-02-28-11-30-41-5620-1677583841.pdf
# 바티칸 시국 정부의 2023년 6월 26일 영내 거주자 수(국적자·비국적자 포함).
# 동일한 범위의 전년도 값이 없어 이 표로 연간 증가율을 구할 수 없다.
# https://web.archive.org/web/20240114093520/https://www.vaticanstate.va/it/stato-governo/note-generali/popolazione.html
POPULATION = {
    "CYN": {2022: 455_508, 2023: 469_481},
    "SOL": {2022: 4_419_953, 2023: 4_549_458},
    "VAT": {2023: 764},
}

# 북키프로스 통계청 2023년 연례 가구노동력조사(10월 조사). 전국 15세 이상 비시설
# 민간 경제활동인구 156,199명 중 실업자 7,988명. 성별·연령 전체 공표 실업률(%).
# https://istatistik.gov.ct.tr/Portals/39/HIA_BULTEN_2023_merged_1.pdf
UNEMPLOYMENT_PERCENT_2023 = {"CYN": 5.1}

# 북키프로스 통계연감 2023 P5(2015=100 기준 12월 지수), P7(전년 12월 대비 매월 상승률, %).
# 두 해 각각 12개월의 소비자물가 평균을 복원하여 연평균 상승률을 구한다.
# https://istatistik.gov.ct.tr/Portals/39/ISTATISTIK_YILLIGI_2023_1.pdf
CYN_CPI_DECEMBER_BASE = {
    2022: (316.93, [5.48, 12.98, 27.73, 34.20, 43.10, 56.76, 64.93, 70.38, 75.64, 81.64, 86.14, 94.51]),
    2023: (616.46, [2.62, 7.39, 9.65, 15.46, 19.64, 33.32, 46.53, 58.46, 63.64, 66.78, 74.16, 83.63]),
}
CYN_CPI_MEAN = {year: base * sum(1 + rate / 100 for rate in months) / 12 for year, (base, months) in CYN_CPI_DECEMBER_BASE.items()}

# 지니계수. 대만은 2023년 전체 가구의 1인당 가처분소득, 싱가포르는 취업자 없는 가구를 포함한
# 거주 가구의 1인당 시장소득에 정부이전과 조세를 반영한 수치(Key Household Income Trends 2025, 그림 10).
# 뉴질랜드는 OECD IDD의 2022년 균등화 가처분소득(전 연령; 2023년 관측 없음).
# https://www.fia.gov.tw/eng/singlehtml/96?cntId=a9d4a6ac784e40dca92d419ce20b5ea9
# https://assets.app.optical.gov.sg/singstat/production/published/assets/b0eafa31-0ac6-4684-b2cd-4a264ce8d820.pdf
# https://sdmx.oecd.org/public/rest/v1/data/OECD.WISE.INE,DSD_WISE_IDD@DF_IDD,1.0/NZL.A.INC_DISP_GINI..._T.METH2012.D_CUR.?startPeriod=2022&endPeriod=2023
# 코소보 교육지출은 IMF GFS COFOG의 2023년 일반정부(S13) 현금 기준 실적, GDP 대비 %.
# https://api.imf.org/external/sdmx/2.1/data/IMF.STA,GFS_COFOG/KOS.S13.G2MF.GF09_T.POGDP_PT.A?startPeriod=2023&endPeriod=2023
SUPPLEMENTAL_MACRO = {
    "CYN": {"inflation": round(CYN_CPI_MEAN[2023] / CYN_CPI_MEAN[2022] - 1, 4)},
    "KOS": {"education": 6.096462646873514 / 100},
    "TWN": {"gini": 0.272}, "SGP": {"gini": 0.389}, "NZL": {"gini": 0.326},
}
