# Axia 개발 안내

게임 규칙은 [설계 문서](DESIGN.md), 국가별 정책의 제도 근거는 [국가별 정책 모듈](COUNTRY_MODULES.md), 시작 자료의 결측·대체값은 [데이터 결측 문서](DATA_GAPS.md)에서 확인한다. 저장소 작업에는 [프로젝트 규칙](../AGENTS.md)을 적용한다.

## 빌드와 실행

프로젝트의 Gradle Wrapper와 JDK 25를 사용한다. Kotlin·Compose·ktlint 버전은 [버전 카탈로그](../gradle/libs.versions.toml)에 있다.

| 작업 | 명령 |
|---|---|
| 전체 빌드와 ktlint 검사 | `./gradlew build` |
| 포맷 정리 | `./gradlew ktlintFormat` |
| 앱 실행 | `./gradlew :desktopApp:run` |
| 시뮬레이션 컴파일 | `./gradlew :simulation:compileKotlin` |

`vMAJOR.MINOR.PATCH` 태그를 푸시하면 설치 파일을 첨부한 릴리스 초안이 생성된다. 이를 정식 릴리스로 공개하면 설치된 앱이 다음 실행 때 새 버전을 확인한다. [릴리스 워크플로](../.github/workflows/release.yml)

Windows는 MSI를 사용한다. macOS에서는 공개 릴리스에 실행 중인 앱과 같은 아키텍처의 DMG가 있을 때 업데이트를 안내한다. 파일 이름은 Apple Silicon용 `Axia-버전-macos-arm64.dmg`, Intel용 `Axia-버전-macos-x64.dmg`다.

Mac에서 처음 설치할 때는 DMG 안의 Axia를 응용 프로그램 폴더에 복사한 뒤 실행한다. macOS용 v1.4.0 및 그 이전 설치판은 자동 업데이트를 지원하는 버전을 한 번 직접 설치해야 한다. 그다음부터는 게임에서 **업데이트**를 누르면 새 버전을 내려받고 진행 중인 캠페인을 자동 저장한 뒤 앱을 교체하고 다시 실행한다.

## 코드 위치

[simulation](../simulation/src/main/kotlin/team/themoment/axia/simulation)은 세계 상태와 게임 규칙을 관리한다. [desktopApp](../desktopApp/src/main/kotlin/team/themoment/axia)은 시뮬레이션 스냅숏으로 Compose 화면을 그린다.

| 작업 | 시작점 |
|---|---|
| 시간 진행·플레이어 입력·저장 재생 | `Simulation.kt`, `SimulationRunner.kt`, `campaign/`. 디스크 저장은 앱의 `CampaignStore.kt` |
| 지도·시작 세계·플레이 가능 국가 | `world/`. 지도 렌더링과 선택은 앱의 `map/`, `selection/`, `newgame/` |
| 세계 경제·교역·부채 | `economy/WorldEconomy.kt`에서 국가 장부, 상품 시장, 대외 결제를 호출 |
| 플레이어 예산·지역·여론·정치 | `nation/`, `society/`, `politics/`. 국가별 고유 정책은 `country/` |
| 인물·사건·국제 관계 | `people/`, `events/`, `diplomacy/`, `military/` |
| 게임 화면·공통 부품 | 앱의 `game/GameScreen.kt`, `game/panels/`, `ui/dossier/`, `theme/` |

## 날짜 진행과 저장

[Simulation.advanceDay](../simulation/src/main/kotlin/team/themoment/axia/simulation/Simulation.kt)가 날짜를 하루 넘긴다. 매월 1일에는 피란·귀환과 재건비를 반영한 뒤 직전 달의 경제를 결산한다. 이어 플레이어 국가와 정책 모듈, AI 정치·외교·군사, 장기 추세·세계 사건, 정치 심사와 제안을 처리한다. 월요일에는 전선을 갱신한다. 일일 사건, 결정 기한, 선거와 법안 심의는 그 뒤에 처리한다.

- 같은 시작 시나리오·시드·입력 순서로 같은 결과가 나와야 한다. 난수는 시스템별 시드 상수로 분리하고, 국가별 계산에는 고정된 순회 순서를 사용한다.
- 시작 통계에는 당시에 이미 시행 중인 제재나 전쟁의 영향이 들어 있다. 경제에 전달할 때는 시작 대비 변화를 계산한다.
- 새 플레이어 명령은 `CampaignAction`과 `SimulationRunner.perform`을 거쳐 날짜·입력 순서를 저장한다. UI는 `SimulationRunner`가 발행하는 스냅숏을 구독한다.

## 데이터 생성

게임 데이터는 [world 리소스](../simulation/src/main/resources/world)에 있다. 새 필드는 JSON과 이를 읽는 Kotlin 데이터 클래스에 함께 추가한다.

| 출력 | 생성기 또는 입력 |
|---|---|
| `world.json`, `map/world-map.bin` | `tools/mapdata/build_map.py --ne-dir <폴더>`에 Natural Earth의 admin 0·1 GeoJSON을 전달 |
| `geography.json` | `tools/geodata/build_geography.py`가 번들의 지도 형상과 세계 정의를 읽음 |
| `regions.json`, `region_population.json` | `tools/geodata/build_regions.py`, `build_region_population.py`. 공식 행정구역 코드 대응은 같은 폴더의 `region_population_codes.csv`, 태국·이집트·인도네시아 PDF 원표 발췌는 `region_population_pdf_extracts.csv`, 베트남 통계연감 표 21·24의 2023년 성·시별 총인구·도시인구는 `vietnam_population_2023.csv` |
| `economy.json` | `tools/econdata/build_economy.py`. 북키프로스·북한·소말릴란드의 GDP·성장률, 북키프로스의 노동력조사와 바티칸 등 인구 보완 계열은 `local_macro.py`. 연령·취업·실업 원표는 `local_population.py`, 가계·노동 조사와 교육 결산 보완값은 `national_surveys.py` |
| `structure.json` | `tools/econdata/build_structure.py`가 WDI와 유엔·대만 국민계정을 결합. 북키프로스·소말릴란드 원표 발췌는 같은 폴더의 `local_accounts.py`, 인구동태·도시화율 보완 자료는 `local_population.py`, 제조 세분류·자원 지대 원표는 `structure_supplements.py` |
| `age_activity.json` | `tools/econdata/build_age_activity.py`가 ILOSTAT·대만의 경제활동참가율, Eurostat의 연령별 연금 조사, `pension_coverage.py`의 국가별 수급 자료를 결합한다. 아시아의 행정기록과 조사 발췌는 `asian_pensions.py`에 있다. 미국 SIPP의 개인별 가중치는 2023년 12월에 한 번 적용한다. |
| `remittances.json` | `tools/econdata/build_remittances.py`가 WDI와 세계은행의 연간 국가 추계, `remittance_supplements.py`의 국가별 원표, Eurostat·중앙은행의 상대국 신고액과 KNOMAD 양자 경로를 결합한다. 에콰도르가 신고한 양자 금액은 같은 폴더의 `ecuador_remittances_2023.csv`에 있다. EU27 합계와 회원국 지급액의 중복을 확인한다. 수취·지급별 출처, 연도, 부분 집계 범위와 추계가 같은 값으로 이어진 기간을 보존한다. |
| `investment_income.json` | `tools/econdata/build_investment_income.py`가 IMF BPM6 국제수지와 Eurostat의 연간 수취·지급 총액과 세부 항목을 취합한다. Eurostat 수취 이자에는 중앙은행 부문(S121)이 보고한 준비자산 이자를 더한다. 국가별 원표와 환산은 `investment_income_supplements.py`에 있다. 방향별 총액과 공표 순액 단독 결산은 서로 배타적이다. 오래된 국제수지 원표는 통계 기준연도, 회계연도 종료월과 출처를 함께 기록한다. |
| `reserve_assets.json`, `commodities.json` | `tools/econdata/build_reserve_assets.py`, `build_commodities.py` |
| `grain_harvest.json`, `grain_harvest_asap.json` | 지정 국가의 달력은 직접 편집. JRC 국가 단위 수확기와 FAO 작물 물량은 `tools/econdata/build_grain_harvest.py`로 결합 |
| `central_banks.json` | 국가별 발표 금리·출처와 통화동맹을 직접 편집. BIS 보완 금리는 `tools/econdata/build_policy_rates.py`로 갱신 |
| `trade.json`, `regimes.json` | `tools/tradedata/build_trade.py`, `tools/politicsdata/build_regimes.py` |
| `electoral_areas.json`, `pakistan_districts.json` | 미국 주·지역구·경선과 인도네시아 주·등록 후보는 번들의 원표 출처를 대조한다. 파키스탄은 `tools/electiondata/build_pakistan.py`가 선관위 후보별 집계와 2023년 지역구 대응표를 결합한다. |
| 프랑스·호주·독일·대만 선거구 | `tools/electiondata/build_france.py`, `build_australia.py`, `build_german_districts.py`, `build_taiwan_ballots.py`가 원표의 당·후보·지역별 득표를 번들에 반영한다. 프랑스는 Natural Earth admin-1으로 지도 지역과 연결한다. |
| 명부·혼합제·의원직 | `mixed_constituencies.json`, `proportional_constituencies.json`, `parliamentary_mandates.json`. 법정 정원·득표 기준·충원 규칙과 국가별 원문을 함께 갱신한다. 한국의 2024년 비례 후보 정원·지역구 출마 여부와 정당별 투표 의향은 `korean_lists.json`에 있다. |
| 영국·캐나다·인도·이란 선거구 | `tools/electiondata/build_plurality.py`의 원표·선거구령, `iranian_constituencies.json`의 주별 선거구·종교 대표 정원. 개표 자료를 갱신할 때 당시 선거구 경계 및 유권자 자격을 함께 대조한다. |
| 상원 개선·간선 국가원수 | 미국은 `electoral_areas.json`, 나머지 상원은 `upper_renewals.json`으로 임기군별 선출·임명을 지정한다. 간선 국가원수와 과도기 절차는 `constitutions.json`을 사용한다. |
| 국가 정책·선거·인물·사건 | 해당 JSON을 직접 편집. 국가 정책은 `housing_programs.json`, `national_programs.json`과 국가 모듈 참조 |

통계 다운로드 도구는 `--cache-dir <폴더>`로 원본 응답을 재사용할 수 있다. 관측 연도와 단위, 국가·정부의 보고 범위를 확인하고, 결측과 보고된 0을 구분한다. 세입 구성은 총세입과 다섯 세목의 보고 범위를 맞추고 `budget.revenueCompositionSource`에 기록한다. 일부 세목만 보고된 경우 미보고 세목 이름을 `budget.unreportedRevenueTaxes`에 보존한다. 자료를 교체하면 출처와 남은 대체 범위를 함께 갱신한다. [데이터 결측 문서](DATA_GAPS.md)

## 변경 검증

모델을 변경하면 임시 실행기로 `Simulation.advanceDay()`를 호출해 검증한다. 장기 영향은 고정 시드로 각 플레이 국가를 30년 이상 진행해 연말 스냅숏을 비교한다. 임시 실행기·테스트 코드와 이를 위한 빌드 설정은 커밋 전에 제거한다.

| 변경 영역 | 확인할 조건 |
|---|---|
| 진행·저장 | 동일한 날짜에 여러 명령을 내린 뒤 새 프로세스에서 저장을 재생한다. 로드 직후와 추가 진행 뒤 경제·국가·정치·세계 스냅숏이 원래 플레이와 일치해야 한다. |
| 경제·재정 | 첫날의 다섯 세목과 기타 세입의 합이 총세입과 일치해야 한다. 장기 실행에서 지표의 유한성과 상한 고착을 확인한다. 채무 감면은 합의 시점에 한 번 반영하고 채권자별 잔액·손실의 합을 국채 장부와 대조한다. |
| 인구·연금 | 전체 수급자와 비취업 수급자를 구분해 연금 수요와 은퇴 직업 인원을 각각 대조한다. 65~74세만 조사한 국가에서는 75세 이상에 적용하는 추정이 분리되어야 한다. 국내 수급률이 구간인 경우 화면의 하한과 실제 수요 계산값을 대조한다. |
| 교역·외환 | 월 경상수지를 무역·송금·투자소득·원조·IMF 이자와 대조한다. 순액만 있는 국가도 경상수지에는 한 번만 기록한다. 재투자수익만 바꾸면 금융 대응액도 바뀌고 외환보유액은 같아야 한다. 세계 수출 합계와 수입 합계, 상대 미배정분을 제외한 양자 송금 수취와 지급을 각각 대조한다. 경제 원조는 공여국의 기초지출과 수원국의 세외수입에 12개월간 같은 달러액이 잡히고 만료·재약정 뒤에도 한 번씩만 결제돼야 한다. 국가별 상품은 기초 재고 + 생산 + 수입 = 기말 재고 + 소비 + 수출이다. IMF 자금 인출은 준비자산과 대출 원금을 같은 금액만큼 늘린다. |
| 지역·영토 | 지역 GDP·경제활동인구 비중은 각각 합이 1이어야 한다. 지역 실업률을 경제활동인구로 가중해 전국 실업률과 대조한다. 영토 이관 전후 양국 인구·실질 GDP의 합을 확인한다. |
| 정책·선거 | 정책의 법안 통과·집행·결산 시점, 국가별 시차와 지급액을 대조한다. 선거 후 정당별 의석 합계와 의회 정원, 선거인·대의원·유보석 합계, 개원·총리 선출·대통령 취임 전후의 재임자와 진행 중인 법안을 대조한다. 같은 나라를 플레이어와 AI로 각각 검증한다. |

화면은 앱에서 해당 패널을 열어 확인한다. 별도 렌더링이 필요하면 Compose `ImageComposeScene`과 임시 실행기로 PNG를 생성할 수 있다.

## 기능 확장

- 공통 정책 구조에 맞는 국가 사업은 `national_programs.json`과 `NationalProgramModule`을 사용한다. 독자 규칙이 필요하면 `CountryModule`을 구현해 `CountryModules.specialized`와 `create`에 등록한다. 고유 정책과 집단별 반응은 `CountryPolicyItem`에 정의한다.
- 사건은 `events.json`에 추가한다. 새 조건 변수는 `EventContext.VARIABLES`에 등록하고 `Nation.eventValues` 또는 `Simulation.sharedEventValues`에서 값을 제공한다.
- 새 정책 종류는 `PolicyItem`과 이를 처리하는 심의·성향·화면 분기에 연결한다. 화면에 값을 추가할 때는 해당 `*Snapshot`에서 `GameScreen`을 거쳐 패널로 전달한다. 지역별 지도 색은 `MapMode.regionColor`에서 결정한다.
- 모델 계수는 이름 있는 상수로 두고 단위와 근거를 기록한다. 자료가 없는 값은 게임 가정으로 표시한다. 주석·KDoc·화면 문구는 한국어로 쓰고, 화면에는 국가 이름과 `String.josa()`를 사용한다.
- 게임 규칙을 바꾸면 설계 문서의 해당 절을 갱신하고 README는 간단히 유지한다. 커밋은 사용자가 요청할 때 만들며 [저장소의 커밋 규칙](../.agents/skills/git-commit/SKILL.md)을 따른다.
