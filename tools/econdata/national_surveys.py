"""IMF·WDI 결측을 보완하는 국가 단위 조사와 결산. 모든 값은 비율(0~1)."""

NATIONAL_INDICATORS = {
    # 안도라 생활실태조사 2023, 사회이전 후 균등화 가처분소득 지니계수.
    # 노동력조사의 15세 이상 취업자 중 자영업 비중(상공회의소 경제보고서).
    # https://sig.govern.ad/SIGDDE.Public/Files/Documents/Notes_premsa_noticies/A047_A163_A164_20241219.pdf
    # https://www.ccis.ad/wp-content/uploads/2024/10/INFORME-ECONOMIC_2023_CA_DEF.pdf
    "AND": {"gini": 0.400, "selfEmployment": 0.182},
    # 바하마 2013 가계지출조사 최종보고서 표 2-8, 전국 1인당 총소비지출 지니계수.
    # https://stats.gov.bs/wp-content/uploads/2020/08/Bahamas-Household-Expenditure-Survey-2013-Report_v2.pdf
    "BHS": {"gini": 0.414},
    # 북키프로스 2021~22년 가계예산조사 표 1, 전체 인구의 균등화 가처분소득.
    # https://istatistik.gov.ct.tr/Portals/39/Gelir_tablolari.pdf
    "CYN": {"gini": 0.37},
    # 캄보디아 사회경제조사 2019~20, 세계은행 빈곤평가 그림 3.7의 1인당 소비 지니계수.
    # https://documents1.worldbank.org/curated/en/099155111222239793/pdf/P1735940c0e8b508d0b80e0c7375c89d9c0.pdf
    "KHM": {"gini": 0.322},
    # 세인트키츠 네비스 통계청 2022 총조사, 두 섬의 15세 이상 전체 실업률.
    # https://nia.gov.kn/wp-content/uploads/2026/10/Population-and-Housing-Census-2022.pdf
    "KNA": {"unemployment": 0.146},
    # 리히텐슈타인 통계청. 지니계수는 2023년 균등화 가처분소득, 고용은 연말 상주
    # 취업자(국외 통근 포함)의 산업별 비율. 교육비는 국가·지자체 합계 / 명목 GDP(백만 CHF).
    # https://www.statistikportal.li/de/publikation/411-vermoegens-und-erwerbsverteilung/2023/01/v-1/p32516
    # https://www.statistikportal.li/de/publikation/282-erwerbstaetigkeit-dezember-2020/2023/01/v-1/p28900
    # https://www.statistikportal.li/de/publikation/485-oeffentliche-bildungsausgaben/2023/01/v-2/p48266
    # https://www.statistikportal.li/de/publikation/301-volkswirtschaftliche-gesamtrechnungen/2023/01/v-1/p38118
    "LIE": {
        "gini": 0.345, "agricultureEmployment": 0.010, "industryEmployment": 0.268,
        "servicesEmployment": 0.722, "education": 210.9 / 7_403.0,
    },
    # ILOSTAT 전국 조사, 남녀 합계. 산업·자영업은 천 명 / 전체 취업자(미분류 포함),
    # 실업률은 15세 이상 경제활동인구 기준. 3개 산업이 반올림 범위에서 완결된 조사만 채택.
    # https://rplumber.ilo.org/data/indicator/?id=EMP_TEMP_SEX_ECO_NB_A
    # https://rplumber.ilo.org/data/indicator/?id=EMP_TEMP_SEX_STE_NB_A
    # https://rplumber.ilo.org/data/indicator/?id=UNE_DEAP_SEX_AGE_RT_A
    # 마셜 제도 산업은 2019 HIES(BB:14101), 종사상 지위·실업은 2021 총조사(AA:1406).
    "MHL": {
        "agricultureEmployment": 1.053 / 16.389, "industryEmployment": 2.554 / 16.389,
        "servicesEmployment": 12.783 / 16.389, "selfEmployment": 3.056 / 12.485,
        "unemployment": 9.823 / 100,
    },
    # 유럽연합 집행위원회 국가 보고서, GDP 대비 공공 교육·훈련 지출.
    # 북마케도니아는 2023년, 몬테네그로는 2022년 결산 기준(2억 3,180만 유로).
    # https://enlargement.ec.europa.eu/document/download/267b368e-6b55-4a42-bb72-6395593de4da_en?filename=north-macedonia-report-2025.pdf
    # https://wapi.gov.me/download/07e773c7-ba3a-4bb2-8030-11094a16338a?version=1.0
    "MKD": {"education": 0.037},
    "MNE": {"education": 0.040},
    # 나우루 2021 총조사(AA:1513).
    "NRU": {"selfEmployment": 0.335 / 4.451, "unemployment": 5.056 / 100},
    # 팔라우 2020 총조사(AA:1541).
    "PLW": {
        "agricultureEmployment": 0.542 / 9.466, "industryEmployment": 1.578 / 9.466,
        "servicesEmployment": 7.346 / 9.466,
    },
    # 팔레스타인 통계청의 서안·가자 합산 2023 개정 실업률. 가자의 4분기는 기관 추계.
    # https://www.pcbs.gov.ps/media/fs4fjug3/book2723.pdf
    "PSX": {"unemployment": 0.306},
    # 투발루 2022 총조사(AA:1732).
    "TUV": {"selfEmployment": 0.344 / 2.951, "unemployment": 7.318 / 100},
    # 대만 교육부 VI.2 2023 결산. 중앙·지방의 중복 지원을 차감한 금액(억 대만달러)을
    # 주계총처 표 1-1의 2023년 당해가격 GDP(백만 대만달러)와 같은 단위로 환산한다.
    # https://stats.moe.gov.tw/files/ebook/indicators/52.pdf
    # https://ws.dgbas.gov.tw/001/Upload/464/relfile/10854/235533/enewstotal11411.pdf
    "TWN": {"education": 7_091.34 * 100 / 23_598_348},
}
