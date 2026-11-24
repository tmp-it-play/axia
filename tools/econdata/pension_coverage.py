"""연령이 확인된 연금 수급 관측. 미국은 Census SIPP 2024의 2023년 12월 개인별 관측을 가중한다."""

import io
import zipfile

from asian_pensions import asian_pensions
from build_economy import download

SIPP_URL = "https://www2.census.gov/programs-surveys/sipp/data/datasets/2024/pu2024_csv.zip"


def early_pension(recipients, not_employed, age_group, working_age, source, *, year=2023):
    if not 0 <= not_employed <= recipients <= age_group <= working_age or age_group <= 0:
        raise ValueError(f"50~64세 연금 수급 집계 오류: {source}")
    return {
        "recipientsOfWorkingAge": round(recipients / working_age, 7),
        "nonEmployedOfWorkingAge": round(not_employed / working_age, 7),
        "nonEmployedOfAgeBand": round(not_employed / age_group, 7),
        "year": year,
        "source": source,
    }


def us_pensions(cache):
    # 2024 SIPP 각 행은 민간 비시설 거주자 한 명의 한 달이다. 조사 가중치 WPFINWGT를 한 번 적용한다.
    # https://www.census.gov/programs-surveys/sipp/data/datasets/2024-data/2024.html
    # https://api.census.gov/data/2024/sipp/variables.html
    # 본인의 은퇴 사유 Social Security와 민간·연방·주·지방·군·철도 등 직역연금을 개인 단위로 합집합한다.
    # 유족·장애 급여만 받은 사람, IRA 인출액, 일시금만 받은 사람은 은퇴 수급자로 세지 않는다.
    data = download(SIPP_URL, cache / "sipp2024-pu-csv.zip" if cache else None)
    working = age_group = retired = not_employed = older = older_retired = 0.0
    with zipfile.ZipFile(io.BytesIO(data)) as archive, archive.open("pu2024.csv") as rows:
        columns = next(rows).rstrip().decode("ascii").split("|")
        month, universe, weight, age, labor, social, reason = [
            columns.index(field) for field in ["MONTHCODE", "RIN_UNIV", "WPFINWGT", "TAGE", "RMESR", "ESSSMNYN", "ESSRSN1YN"]
        ]
        pensions = [columns.index(f"ERET{i}MNYN") for i in range(1, 8)]
        for raw in rows:
            row = raw.split(b"|")
            if row[month] != b"12" or row[universe] != b"1":
                continue
            years, w = int(row[age]), float(row[weight])
            if not (0 <= years <= 90 and w >= 0):
                raise ValueError("SIPP 대상자 연령 또는 가중치 누락")
            if years < 15:
                continue
            receives = (row[social] == b"1" and row[reason] == b"1") or any(row[i] == b"1" for i in pensions)
            if years >= 65:
                older += w
                if receives:
                    older_retired += w
            else:
                working += w
                if years >= 50:
                    age_group += w
                    if receives:
                        retired += w
                        employment = int(row[labor])
                        if not 1 <= employment <= 8:
                            raise ValueError("SIPP 연금 수급자의 월 취업 상태 누락")
                        if employment >= 6:  # 6~8: 그 달 어느 주에도 일자리가 없었다.
                            not_employed += w
    if not 0 < older_retired <= older:
        raise ValueError("SIPP 65세 이상 수급자 집계 오류")
    return {
        "olderPension": {
            "rate": round(older_retired / older, 7), "year": 2023,
            "source": "미국 Census SIPP · 12월 본인 은퇴·직역연금",
        },
        "earlyPension": early_pension(retired, not_employed, age_group, working, "미국 Census SIPP · 12월"),
    }


def national_pensions(cache):
    # 호주 DSS 2023년 6월 Payment by Rate: 전액·부분 지급, 지급률 미확정(제로·정지와 별도).
    # State 표의 Unknown 81,405명은 해외·주소 미확인을 합친다. 확인된 지급에서 전원 빼면 국내 수급 하한,
    # 미확정 2,140명까지 모두 국내 양의 지급으로 세면 상한이다. DVA 지급분과 사적 연금은 범위 밖이다.
    # ABS 표 59의 6월 30일 전국 65~99세 및 100세 이상 거주자 합. DSS 원표 인원은 5명 단위 반올림.
    # https://data.gov.au/data/dataset/cff2ae8a-55e4-47db-a66d-e177fe0ac6a0/resource/7a6457a8-44a3-406c-b552-62eb0fef9d66/download/dss-demographics-june-2023-final.xlsx
    # https://www.abs.gov.au/statistics/people/population/national-state-and-territory-population/jun-2023/3101059.xlsx
    australian_paid = 1_738_095 + 845_790
    australian_population = 4_556_903
    # 튀르키예 SGK 2023 연감 2권 표 2.7·2.21·2.33. 근로자·자영업자(농업 포함)·공무원별
    # 만 65세 이상 본인 노령연금 수급자. 표의 80세 이상 소계와 단일 연령은 중복 집계하지 않는다.
    # 공무원 노령연금 열에 합산된 참전 등 공훈연금 본인 수급자는 표 2.39의 같은 연령에서 뺀다.
    # 표 2.5·2.19·2.32의 해외 노령연금 수급자 전 연령 합이 국내 수급 하한의 차감액이다.
    # https://sgd.sgk.gov.tr/Istatistik/Yillik/fcd5e59b-6af9-4d90-a451-ee7500eb1cb4/
    # https://veriportali.tuik.gov.tr/tr/press/53710/metadata (2023년 말 등록인구)
    turkish_old_age = 2_402_534 + 1_015_738 + 929_762 - 24_686
    turkish_overseas = 3_638 + 9 + 1_013
    turkish_population = 8_722_806
    return {
        **asian_pensions(),
        # ELMPS 개인 단위 응답. 65세 이상 본인 노령연금 수급률은 57%이며, 수급자가 있는
        # 가구에 사는 비율 75%와 구분한다. 기여형과 비기여형을 따로 식별할 수 없다.
        # https://www.wider.unu.edu/sites/default/files/Publications/Working-paper/PDF/wp2026-27-social-protection-egypt.pdf (§6.3 그림 13)
        "EGY": {"olderPension": {
            "rate": 0.57, "year": 2023, "source": "이집트 ELMPS · 개인 노령연금 수급 조사",
        }},
        "TUR": {"olderPension": {
            "rate": round((turkish_old_age - turkish_overseas) / turkish_population, 7),
            "upperRate": round(turkish_old_age / turkish_population, 7),
            "year": 2023, "source": "튀르키예 SGK · 기여형 본인 노령연금, 비기여 노령수당 제외",
            "rangeNote": "해외 지급분의 연령을 구분할 수 없어 국내 65세 이상 수급자의 하한을 적용합니다.",
        }},
        "USA": us_pensions(cache),
        "AUS": {"olderPension": {
            "rate": round((australian_paid - 81_405) / australian_population, 7),
            "upperRate": round((australian_paid + 2_140) / australian_population, 7),
            "year": 2023, "source": "호주 DSS · Services Australia 노령연금",
            "rangeNote": "해외·주소 미확인 수급자와 지급률 미확정자가 있어 국내 수급 하한을 씁니다.",
        }},
        # Statistics Canada T1 Family File. OAS·연방 보충급여 수급자 / 무소득자 포함 65세 이상 개인.
        # CPP/QPP와 사적연금은 합산하지 않는다. 두 통계표의 모집단은 같은 조세 파일이다.
        # https://www150.statcan.gc.ca/t1/tbl1/en/tv.action?pid=1110003901 (v28536649)
        # https://www150.statcan.gc.ca/t1/tbl1/en/tv.action?pid=3910004301 (v28515329)
        "CAN": {"olderPension": {
            "rate": round(6_894_780 / 7_457_930, 7), "year": 2023, "source": "캐나다 통계청 · OAS·연방 보충급여",
        }},
        # IBGE 2023 PNAD Contínua 연간 1차 방문. V2009가 65 이상인 사람의 보정가중치(V1032) 합.
        # 그중 V5004A=1인 공적 퇴직·유족연금 수급자의 가중치 합. 비기여 노인·장애부조 BPC(V5001A) 제외.
        # 유효 표본 47,827명, 수급 표본 39,299명. 비취업 유족 수급자를 조기 은퇴로 분류하지 않는다.
        # https://ftp.ibge.gov.br/Trabalho_e_Rendimento/Pesquisa_Nacional_por_Amostra_de_Domicilios_continua/Anual/Microdados/Visita/Visita_1/Dados/PNADC_2023_visita1_20250822.zip
        "BRA": {"olderPension": {
            "rate": round(18_345_588.28016036 / 22_714_929.00002111, 7), "year": 2023,
            "source": "IBGE PNAD · 공적 퇴직·유족연금",
        }},
        # 독일 연방정부 Alterssicherungsbericht 2024, 표 B.4.3 (Teil 1 Deutschland), ASID 2023.
        # 거주 65+ 중 6개 제도 본인 권리의 정기급여가 하나도 없는 비율 8%. 유족·일시금 제외.
        # 85세 이상은 2019 조사 응답을 2023년으로 갱신했다. 여러 제도에서 수급해도 개인당 한 번 센다.
        # https://dserver.bundestag.de/btd/20/140/2014086.pdf
        # https://www.bmas.de/DE/Service/Publikationen/Forschungsberichte/fb-658-m-alterssicherung-in-deutschland-2023-methodenbericht.html
        "DEU": {"olderPension": {
            "rate": 0.92, "year": 2023,
            "source": "독일 ASID · 6개 제도 본인 노후급여, 85세 이상 조사 갱신 추계",
        }},
        # 2024-01-01 에스토니아 SK111(노령연금, 양성) 65~69/70~74/75~79/80+ 합은 287,170명.
        # SK13의 15개 국내 군 외 국제협정 등 미배분 17,651명은 연령을 구분할 수 없다.
        # 국내 군 합에는 하르유주 안의 탈린을 또 더하지 않는다. 65+ 거주자는 같은 날 281,461명.
        # https://andmed.stat.ee/en/stat/SK111
        # https://andmed.stat.ee/en/stat/SK13
        # https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/demo_pjanbroad?geo=EE&age=Y_GE65&sex=T&time=2024
        "EST": {"olderPension": {
            "rate": round((287_170 - 17_651) / 281_461, 7), "upperRate": 1.0, "year": 2023,
            "source": "에스토니아 통계청 · 연말 국가 노령연금",
            "rangeNote": "국제협정에 따라 지역 미배분된 수급자의 연령을 몰라 해당 인원을 모두 뺀 하한을 씁니다.",
        }},
        # 핀란드 연금센터 ETK의 2023년 말 거주자(001), 노령연금(e40, 부분연금 포함), 양성(Mol).
        # 65~69세부터 100세 이상까지 상호 배타적 5세 구간(112~119) 합. 1월 1일 기준 거주 인구를 분모로 쓴다.
        # https://tilastot.etk.fi/api/v1/en/ETK/110kaikki_elakkeensaajat/10elakkeensaajien_lkm/elsa_k02_laji.px
        # https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/demo_pjanbroad?geo=FI&age=Y_GE65&sex=T&time=2024
        "FIN": {"olderPension": {
            "rate": round(1_295_839 / 1_309_376, 7), "year": 2023, "source": "핀란드 연금센터 · 거주자 노령·부분노령연금",
        }},
        # DREES EACR의 C-Droits_directs, 2023년 말 ANCETRE 추계. CC=0000(모든 제도), 거주지 France,
        # Champ=ddir(본인 권리), 중복 없는 전 연령 합계에서 21~64세 수급자 합을 뺀다.
        # https://data.drees.solidarites-sante.gouv.fr/api/explore/v2.1/catalog/datasets/donnes_eacr/attachments/62_eacr_diffusee_part_1_version_du_26_mai_2026_xlsx
        # https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/demo_pjanbroad?geo=FR&age=Y_GE65&sex=T&time=2024
        "FRA": {"olderPension": {
            "rate": round((16_296_183 - 2_257_840) / 14_777_175, 7), "year": 2023,
            "source": "프랑스 DREES ANCETRE · 거주자 본인권리 연금 추계",
        }},
        # 영국 DWP Stat-Xplore State Pension, SP 2 - Region, May-23, Grouped Amount of Benefit.
        # GB 11개 지역에서 양의 지급 확인 11,571,068명. 무액·미상 셀은 비공개, 합계는 교란됨.
        # 북아일랜드 DfC: 주 £20 이상(Table 5) 294,970 + 국내주소 295,050 − 전체 316,600(Table 6)
        # = 국내 양의 지급 최소 273,420. 상한은 GB 총 지역합·거주 미상과 DfC 전체를 합한다.
        # ONS MYE2 - Persons의 2023년 6월 30일 영국 거주 65세 이상. 5월 State Pension 최저 수급연령은 66세.
        # https://stat-xplore.dwp.gov.uk/webapi/jsf/tableView/tableView.xhtml?table=SP+2+-+Region
        # https://www.communities-ni.gov.uk/system/files/publications/communities/bss-state-pension-may-2023.xlsx
        # https://www.ons.gov.uk/file?uri=/peoplepopulationandcommunity/populationandmigration/populationestimates/datasets/populationestimatesforukenglandandwalesscotlandandnorthernireland/mid2023/mye23tablesuk.xlsx
        "GBR": {"olderPension": {
            "rate": round((11_571_068 + 273_420) / 12_924_166, 7),
            "upperRate": round((11_571_056 + 3_304 + 316_600) / 12_924_166, 7),
            "year": 2023, "source": "영국 DWP·북아일랜드 DfC · 5월 국가연금, 6월 인구",
            "rangeNote": "북아일랜드 지급액·주소는 별도 표입니다. 수요에는 하한을 쓰며 셀 교란과 인구 기준월 차이로 범위는 근사치입니다.",
        }},
        # ISTAT 연금 수급자 통계의 국내(IT, 해외 포함 ITTOT와 구분) 개인수 P_RSNU.
        # 2022년 12월 노령·근속연금 OLSEN1에서 성별 합계, 65~69세부터 95+까지 7개 연령 합.
        # 연령 미상(NDIV) 국내 108명은 상한에만 포함한다. 급여 건수를 더하지 않는다.
        # https://esploradati.istat.it/SDMXWS/rest/data/IT1,46_812_DF_DCAR_PENSIONATI2_3,1.0/A.IT.P_RSNU.OLSEN1.TOTAL.9.?startPeriod=2022&endPeriod=2022
        # https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/demo_pjanbroad?geo=IT&age=Y_GE65&sex=T&time=2023
        "ITA": {"olderPension": {
            "rate": round(10_036_448 / 14_181_297, 7), "upperRate": round((10_036_448 + 108) / 14_181_297, 7),
            "year": 2022, "source": "이탈리아 ISTAT · 국내 노령·근속연금",
            "rangeNote": "국내 수급자 중 108명의 연령이 미상입니다. 수요에는 65세 이상이 확인된 인원을 씁니다.",
        }},
        # 후생노동성 2023 국민생활기초조사 가구표 9, (再掲)65歳以上의 공적연금·은급 수급자 공표율.
        # 조사 가구의 65세 이상 개인 전체가 분모다. 은급을 별도로 더하지 않는다.
        # https://www.e-stat.go.jp/dbview?sid=0004021158
        # 2022년 11월 1일 노령연금 수급자 실태조사 표 16, 65세 미만 수급자(천 명).
        # 총무성 같은 날 확정 인구: 당시 개시 연령에 해당하는 60~64세 7,458천, 15~64세 74,214천.
        # 수급자 중 취업 여부 미상 49.4천 명은 총수급에만 포함한다.
        # https://www.e-stat.go.jp/stat-search/file-download?fileKind=0&statInfId=000040120759
        # https://www.e-stat.go.jp/stat-search/file-download?fileKind=0&statInfId=000040047220
        # https://www.nenkin.go.jp/service/jukyu/seido/roureinenkin/jukyu-yoken/20140421-02.html
        "JPN": {
            "olderPension": {"rate": 0.952, "year": 2023, "source": "후생노동성 국민생활기초조사 · 공적연금·은급"},
            "earlyPension": {
                **early_pension(2_077.1, 646.9, 7_458, 74_214, "후생노동성 11월 노령연금 · 비취업 64.69만 명, 취업 미상 제외", year=2022),
                "fromAge": 60,
            },
        },
        # 2023년 연금통계 개인 편. 65세 이상 949.7만 명 중 863.6만 명, 공표 수급률 90.9%.
        # 11종 중복 수급은 한 번만 계산한다. 기초·장애·국민·직역·퇴직·개인·주택·농지연금 등을 포함한다.
        # https://kostat.go.kr/boardDownload.es?bid=11816&list_no=438202&seq=1
        "KOR": {"olderPension": {"rate": 0.909, "year": 2023, "source": "통계청 11종 연금 · 유족·장애·주택연금 등 포함"}},
        # 라트비아 VSAA 2023년 12월 노령연금, 65~69세부터 90+까지의 양성 수급 인원.
        # 각 연령에서 해외(Ārvalstis)를 제외한다. 국내 6개 구간 합계는 398,103명.
        # https://data.gov.lv/dati/dataset/a6221ba7-b490-424f-bd7e-02794c6954a4/resource/88c267c7-dd47-4be3-9ca4-c09028774e43/download/vecuma_pensijas_12-2023.xlsx
        # https://ec.europa.eu/eurostat/api/dissemination/statistics/1.0/data/demo_pjanbroad?geo=LV&age=Y_GE65&sex=T&time=2024
        "LVA": {"olderPension": {
            "rate": round(398_103 / 402_089, 7), "year": 2023, "source": "라트비아 VSAA · 국내 노령연금",
        }},
        # 러시아 Rosstat ВНДН 2024 개인 원자료: 2023년 말 연령 I01_02(16+) 또는 H02_02(아동).
        # 일반 가구 83개 지역. 크림·세바스토폴(H00_02=035,067), 12개월 초과 부재자(H01_07=3) 제외.
        # 2024년 1~2월 면접 당시 I02_01=1, 일차·이차 연금 I02_02_01/02 중 하나가 1~6(노령·조기·근속),
        # 2023년 연간 연금 수급액 R_I_PENS>0. 취업은 조사 분류와 연간 소득을 함께 보며 월별 상태는 공표하지 않는다.
        # KVZV 개인가중. 65+ 표본 27,209명 중 26,825명, 50~64세는 25,430명 중 수급자 12,141명.
        # 조기 비취업은 R_10_1=2이면서 2023년 R_DEN/R_I_SAM/R_I_DOP/R_I_DR/I03_44 근로·부업 소득이
        # 없는 7,646명이다. 산발적 부업을 한 비취업 응답자 210명은 조기 비취업에서 제외했다.
        # https://rosstat.gov.ru/free_doc/new_site/VNDN-2024/index.html
        # https://tochno.st/datasets/surveys (ВНДН/2024, Росстат 설문·개인 CSV·코드북)
        "RUS": {
            "olderPension": {
                "rate": round(21_432_559.0294 / 21_689_382.1849, 7), "year": 2023,
                "source": "러시아 통계청 · 이듬해 조사한 노령·근속연금, 연간 수급액 확인",
            },
            "earlyPension": early_pension(
                12_633_092.2127, 5_627_139.4574, 29_207_081.5787, 96_821_468.3901,
                "러시아 통계청 가구조사 · 연간 근로·부업 소득 없는 비취업 수급자",
            ),
        },
        # SASSA 2023/24 회계연도 1분기말(6월 30일) Old Age Grant 지급 3,934,291건. 국내 거주 60세 이상만
        # 1인 1건 수급한다. Stats SA 중간 인구 추계(7월 1일)의 60~64세 전원을 빼면 65+ 하한,
        # 지급 전부가 65+이면 상한이다. 이 제도 외의 연금이나 비취업 여부를 추론하지 않는다.
        # https://www.kzntreasury.gov.za/ResourceCenter/Public%20Finance/Estimates%20of%20Provincial%20Revenue%20and%20Expenditure/2024%20-25%20Estimates%20of%20Provincial%20Revenue%20and%20Expenditure/OPRE%2024-25.pdf (Table 1.3)
        # https://www.statssa.gov.za/?PPN=P0302&SCH=73952&page_id=1854 (P0302, Country projection by population group, sex and age)
        # https://access.gesis.org/dbk/80536 (5세 구간 중간 인구 재수록)
        "ZAF": {"olderPension": {
            "rate": round((3_934_291 - 1_957_488) / 4_003_910, 7),
            "upperRate": round(3_934_291 / 4_003_910, 7), "year": 2023,
            "source": "남아공 SASSA · 노령보조금만",
            "rangeNote": "60~64세가 포함된 지급 총계만 있어 그 연령 인구를 모두 뺀 하한을 씁니다.",
        }},
    }
