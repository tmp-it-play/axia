"""아시아 국가 행정기록과 개인 조사에서 확인되는 65세 이상 연금 수급률."""


def asian_pensions():
    # 태국 회계감사총국 FY2566 지급 인원(남·여). 60~69세 수급자 중 65~69세 인원은 별도 공표하지 않는다.
    # 2025년 통계연감 표 1.4의 2023년 등록인구에서 가능한 60~64세와 65~69세 인원을 각각 적용한다.
    # 회계연도 지급 인원과 연말 등록인구의 시점 차이가 있으므로 두 끝값은 근사치다.
    # https://new-migrate.data.go.th/en/api/3/action/datastore_search?resource_id=ccb05c60-f6ef-49b3-9080-6e9ba79af301
    # https://www.nso.go.th/public/e-book/Statistical-Yearbook/SYB-2025_webPage/File%20SYB/2025/1/T%201.4%202025.pdf
    paid_60_to_69 = 2_967_368 + 3_545_586
    paid_70_up = 1_319_959 + 1_712_142 + 467_156 + 752_483 + 62_867 + 133_528
    population_65_to_69 = 3_180_499
    population_65_up = population_65_to_69 + 2_419_901 + 1_512_811 + 954_310 + 567_917 + 241_425 + 77_257 + 41_200
    return {
        # MRDRC 연구보고서 439, 표 4.1.1·4.3.1. CHARLS·LASI 응답자 본인의 공적연금 수령 여부.
        # 65세 이상 남녀별 수급률은 중국 61.0·67.9%, 인도 40.7·39.5%. 두 성별의 가중합은
        # 두 비율 사이에 있다. 소수 첫째 자리 반올림까지 포함한다. 조사 표본오차의 신뢰구간과는 별개다.
        # https://mrdrc.isr.umich.edu/wp-content/uploads/2024/04/wp439.pdf
        "CHN": {"olderPension": {
            "rate": 0.6095, "upperRate": 0.6795, "year": 2015, "source": "중국 CHARLS · 공적연금 수급 조사",
            "rangeNote": "65세 이상 남녀별 공표율에서 구한 조사 당시 범위입니다. 수요에는 하한을 씁니다.",
        }},
        "IND": {"olderPension": {
            "rate": 0.3945, "upperRate": 0.4075, "year": 2019, "source": "인도 LASI 2017~2019년 · 공적연금 수급 조사",
            "rangeNote": "65세 이상 남녀별 공표율에서 구한 조사 당시 범위입니다. 수요에는 하한을 씁니다.",
        }},
        # 이스라엘 NII 건강보험등록부 표 22: 2022.12~2023.11의 65세 이상 노령·유족연금
        # 본인 월평균 수급자(피부양자 제외). 표 2·3의 2022.11/2023.11 동등록부 65+ 인원의
        # 평균으로 기간 분모를 근사한다. 장기 해외체류 중 자격유지자도 포함될 수 있다.
        # https://www.btl.gov.il/Publications/survey/Documents/skira2023.pdf
        "ISR": {"olderPension": {
            "rate": round((547_894 + 521_844) / ((1_207_661 + 1_243_528) / 2), 7), "year": 2023,
            "source": "이스라엘 NII · 노령·유족연금, 건강보험 등록인구 기초·기말 평균 기준",
        }},
        "THA": {"olderPension": {
            "rate": round((paid_70_up + max(0, paid_60_to_69 - 4_197_897)) / population_65_up, 7),
            "upperRate": round((paid_70_up + min(paid_60_to_69, population_65_to_69)) / population_65_up, 7),
            "year": 2023, "source": "태국 회계감사총국 · 노령수당 지급분",
            "rangeNote": "60~69세 수급자를 한 구간으로 집계합니다. 회계연도 지급과 연말 등록인구로 구한 근사 범위의 하한을 씁니다.",
        }},
        # 대만 노동보험국 12월 국민연금 통계. 노령연금과 제도 도입 당시 이미 65세였던 사람의 기본보장연금은
        # 수급 대상이 겹치지 않는다. 연령표의 65세 이상 합을 호적 소재지별 표와 대조했다.
        # 분모는 같은 12월 말 내정부 등록인구. 노동보험·공무원연금 등 다른 제도를 더하지 않는다.
        # https://events.bli.gov.tw/report/attachment_file/report/month/11212/a9070.pdf
        # https://events.bli.gov.tw/report/attachment_file/report/month/11212/a9060.pdf
        # https://ageing.nhri.edu.tw/annual_report/resources/files/2023_F.pdf (부록 2)
        "TWN": {"olderPension": {
            "rate": round((1_427_671 + 381_492) / 4_296_985, 7), "year": 2023,
            "source": "대만 노동보험국 · 국민연금 노령·기본보장급여, 호적 인구 기준",
        }},
        # ILO·베트남여성연맹 표 2.1. VSS 사회보험 퇴직연금 65세 이상 전체 수급률.
        # 비기여 노령수당과 유족연금은 별도다. 55세·60세 이상 수급률을 섞지 않는다.
        # https://www.ilo.org/sites/default/files/wcmsp5/groups/public/%40asia/%40ro-bangkok/%40ilo-hanoi/documents/publication/wcms_819801.pdf
        "VNM": {"olderPension": {
            "rate": 0.205, "year": 2019, "source": "베트남 VSS·ILO · 사회보험 퇴직연금",
        }},
    }
