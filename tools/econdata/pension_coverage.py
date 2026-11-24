"""연령이 확인된 연금 수급 관측. 미국은 Census SIPP 2024의 2023년 12월 개인별 관측을 가중한다."""

import io
import zipfile

from build_economy import download

SIPP_URL = "https://www2.census.gov/programs-surveys/sipp/data/datasets/2024/pu2024_csv.zip"


def early_pension(recipients, not_employed, age_group, working_age, source):
    if not 0 <= not_employed <= recipients <= age_group <= working_age or age_group <= 0:
        raise ValueError(f"50~64세 연금 수급 집계 오류: {source}")
    return {
        "recipientsOfWorkingAge": round(recipients / working_age, 7),
        "nonEmployedOfWorkingAge": round(not_employed / working_age, 7),
        "nonEmployedOfAgeBand": round(not_employed / age_group, 7),
        "year": 2023,
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
    return {
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
        # 후생노동성 2023 국민생활기초조사 가구표 9, (再掲)65歳以上의 공적연금·은급 수급자 공표율.
        # 조사 가구의 65세 이상 개인 전체가 분모다. 은급을 별도로 더하지 않는다.
        # https://www.e-stat.go.jp/dbview?sid=0004021158
        "JPN": {"olderPension": {"rate": 0.952, "year": 2023, "source": "후생노동성 국민생활기초조사 · 공적연금·은급"}},
        # 2023년 연금통계 개인 편. 65세 이상 949.7만 명 중 863.6만 명, 공표 수급률 90.9%.
        # 11종 중복 수급은 한 번만 계산한다. 기초·장애·국민·직역·퇴직·개인·주택·농지연금 등을 포함한다.
        # https://kostat.go.kr/boardDownload.es?bid=11816&list_no=438202&seq=1
        "KOR": {"olderPension": {"rate": 0.909, "year": 2023, "source": "통계청 11종 연금 · 유족·장애·주택연금 등 포함"}},
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
