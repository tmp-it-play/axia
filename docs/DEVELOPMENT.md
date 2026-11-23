# Axia 개발 안내

이어서 작업하는 사람과 에이전트를 위한 문서다.
- 무엇을 왜 만드는지(기획, 모델 수식, 데이터 출처, 마일스톤, 알려진 한계): [DESIGN.md](DESIGN.md)
- 저장소 규칙: [AGENTS.md](../AGENTS.md)
- 코드를 어떻게 찾고, 고치고, 검증하는지: 이 문서

## 1. 현재 상태

- 1차 구현은 DESIGN.md의 모든 항목을 다룬다(가상역사 제외).
- 세부 구현 1회차(DESIGN.md 16장 8번)를 마쳤다.
  - ① 보정, ② AI 결정, ③ 산업 9부문·지역 분포, ④ 국가 모듈 13개
- 이어서 ⑤ 지역별 산업·실업을 구현했다. 실업률 지도와 지역 선택 카드에서 확인할 수 있다.
- ⑥ 국가부도·채무 재조정을 구현했다. 재정 탭에서 차환 압박과 교환채를 확인하고 지급 유예 전보에서 협상안을 선택한다.
- ⑦ 영국·캐나다·호주 주택 정책을 구현했다. 정책 탭에서 발의하고 정치·개요에서 착공과 준공, 사회에서 집값, 재정에서 지원 지출을 확인한다.
- ⑧ 곡물·원유 시장을 구현했다. 경제 탭과 지도 국가 선택 카드에서 국가별 생산·소비·재고, 상대별 월 선적을 본다.
- ⑨ 브라질 Conab 가격차 보조를 구현했다. 정책 탭에서 추가 경매 범위를 발의하고, 경제·재정 탭에서 월별 지급 물량과 집행액을 확인한다.
- ⑩ 10개 주요 생산국의 곡물 수확 달력을 적용했다. 경제 탭과 국가 카드가 직전 달의 수확·재고·선적을 표시한다.
- ⑪ 플레이어 국가의 고령화에 따라 은퇴층 인원과 인구 비중, 여론·법안·조직·지역의 대표 인구를 매월 갱신한다.
- ⑫ 파키스탄은 과도 내각으로 시작해 2월 8일 총선에서 국민의회를 구성한다. 정치 탭에서 선거 전 전망, 지역구·유보 의석, 새 총리와 법안별 예상 표수를 확인한다. AI 국가도 같은 방식으로 첫 총선을 치른다.
- 자료별 결측과 보완 근거는 [데이터 결측과 구현 범위](DATA_GAPS.md)에서 확인한다.
- 다음 후보는 다음과 같다. 무엇을 먼저 할지는 사용자가 정한다.
  - 남은 15개국 국가 모듈
  - DESIGN.md 18장의 알려진 한계

## 2. 빌드와 실행

| 할 일 | 명령 |
|---|---|
| 전체 빌드 + ktlint 검사 | `./gradlew build` |
| 포맷 자동 정리 | `./gradlew ktlintFormat` (한 번에 다 안 고쳐지면 두세 번 돌린다) |
| 앱 실행 | `./gradlew :desktopApp:run` |
| 시뮬레이션만 컴파일 | `./gradlew :simulation:compileKotlin` |

- 툴체인: Gradle 9, JDK 25, Kotlin 2.4, Compose Multiplatform 1.12, ktlint 1.8
- `~/.gradle/gradle.properties`에서 JDK 자동 다운로드가 꺼져 있을 수 있다. 로컬 JDK 25 이상이 필요하다.
- 테스트 소스는 두지 않는다(AGENTS.md 2번). 검증 방법은 6장을 따른다.

## 3. 모듈과 패키지

`:simulation`은 Compose에 의존하지 않는 순수 Kotlin이다. 결정론적으로 동작하며, 모든 세계 모델이 여기 있다. `:desktopApp`은 화면이다.

### 3.1 `simulation/src/main/kotlin/team/themoment/axia/simulation/`

| 패키지 | 내용 | 주요 파일 |
|---|---|---|
| (루트) | 세계 전체를 묶어 하루씩 진행. UI용 스냅숏과 명령 채널 | `Simulation.kt`, `SimulationRunner.kt` |
| `world` | 지도 세계 정의, 국가 지리(중심점·인접·해안), 구조 지표 | `World.kt`, `WorldLoader.kt`, `Geography.kt` |
| `economy` | 거시경제, 중앙은행, 교역·곡물·원유·환율, 산업 9부문, AI 재정, 채무 재조정 | `CountryEconomy.kt`, `WorldEconomy.kt`, `CentralBank.kt`, `Trade.kt`, `CommodityMarket.kt`, `CommodityFundamentals.kt`, `Sectors.kt`, `SovereignDebt.kt` |
| `nation` | 플레이어 국가: 인구 집단, 예산, 규제, 지지율, 지역별 산업·실업 | `Nation.kt`, `Budget.kt`, `Population.kt`, `Regulations.kt`, `Regions.kt`, `RegionalEconomy.kt`, `CountryPolicyItem.kt` |
| `society` | 성향 집단, 유권자 표본, 조직, 사회 지표, 인구 구조, 기후·기술 추세 | `Identity.kt`, `Society.kt`, `Organizations.kt`, `Demography.kt` |
| `politics` | 정당 의석, 법안 심의, 선거, 헌법기관, 신생 정당, AI 국가 정치, 체제·지지율 자료 | `Politics.kt`, `Elections.kt`, `Constitution.kt`, `WorldPolitics.kt` |
| `people` | 지도자 인물, 이름 생성기, 사망·승계 | `People.kt` |
| `diplomacy` | 관계, 국제기구, 동맹, 무역협정, 제재, AI 외교 결정 | `Diplomacy.kt` |
| `military` | 전력, 동원, 전선, 내전, 영토 분쟁, AI 개전 | `Military.kt` |
| `events` | 조건부 사건, 선택지, 시스템 사건(휴전·협정 제안) | `Events.kt` |
| `country` | 국가별 특화 모듈 인터페이스와 `<ISO3 소문자>/` 구현 | `CountryModule.kt` |
| `math`, `text`, `history`, `time` | 정규분포 난수, 한국어 조사(`josa`), 월간 기록, 배속 | |

### 3.2 `desktopApp/src/main/kotlin/team/themoment/axia/`

| 패키지 | 내용 |
|---|---|
| `game` | 게임 화면(`GameScreen.kt`), 상단 띠, 왼쪽 탭 |
| `game/panels` | 탭별 패널(개요·경제·재정·정책·정치·조직·사회·외교·군사·상황·통계), 사건 전보, 공통 부품(`CommonParts.kt`, `PanelParts.kt`) |
| `map` | 지도 그리기, 지도 모드(`MapMode.kt`), 지역 단위 색칠 |
| `ui/dossier`, `theme` | 서류철 UI 부품과 색·글꼴 |
| `newgame`, `selection` | 국가 선택 화면, 지도 선택 카드 |
| `update` | GitHub 릴리스 기반 자동 업데이트 |

## 4. 시뮬레이션 진행

`Simulation.advanceDay()`가 하루를 진행한다. UI는 `SimulationRunner`의 `StateFlow`(economy, nation, politics, world, clock)를 구독한다.

- 매월 1일, 순서가 중요하다.
  1. 경제(교역 → 중앙은행 → 국가 경제)
  2. 플레이어 국가, 국가 모듈, 전쟁 피로
  3. AI 정치, 외교
  4. 군사 자본, 분쟁, AI 개전, AI 외교 결정
  5. 장기 추세, 세계 사건
  6. 정치 지지 갱신, 불신임 점검
  7. 휴전·협정 제안
- 매주 월요일: 전선 이동
- 매일: 플레이어 사건 판정, 결정 기한, 선거, 법안 심의, 소식 정리
- **결정론**: 같은 시드·입력이면 같은 결과다. 시스템마다 시드를 다른 상수로 XOR해 난수 순서가 섞이지 않게 한다(`Simulation` companion의 `*_SEED_SALT`, `Nation`의 `ORGANIZATION_SEED_SALT`).
  - 새 시스템에 난수가 필요하면 새 상수를 만든다.
  - 국가 순서는 항상 id 정렬이다. `HashMap` 순회 순서에 기대지 않는다.
- **시작 상태 보존**: 2023년 데이터에는 이미 진행 중인 전쟁·제재·협정의 효과가 들어 있다. 그래서 외부 효과는 시작 대비 변화만 경제에 전한다(교역 배율, 전쟁 강도, 점령 비율).

## 5. 데이터 파일과 생성 도구

데이터는 `simulation/src/main/resources/world/`에 있다. 콘텐츠는 코드가 아니라 데이터로 정의한다(DESIGN.md 15장). 출처와 가정은 각 파일의 `sourceNote`와 DESIGN.md 14장에 남긴다.

| 파일 | 내용 | 만드는 방법 |
|---|---|---|
| `world.json`, (`desktopApp`) `map/world-map.bin` | 국가·영역·행정구역, 지도 형상 | `tools/mapdata/build_map.py` (Natural Earth) |
| `economy.json` | 2023년 거시·사회·예산 초기값 | `tools/econdata/build_economy.py` (IMF, World Bank) |
| `structure.json` | 교역 개방도, 자원 지대, 병력, 인구 동태, 부가가치 구성 | `tools/econdata/build_structure.py` (World Bank) |
| `geography.json` | 중심점, 면적, 해안, 육상 인접국 | `tools/geodata/build_geography.py` (지도 형상) |
| `regions.json` | 플레이 가능국 행정구역별 면적·도시 인구 | `tools/geodata/build_regions.py` (Natural Earth 거주지) |
| `commodities.json` | 2023년 곡물·원유 생산량과 석유 수요 가중치 | `tools/econdata/build_commodities.py` (WDI·EIA, 대만·코소보 보완) |
| `grain_harvest.json` | 국가별 12개월 곡물 수확 비중 | 국가별 출처·연간 합 100 확인 |
| `housing_programs.json` | 영국·캐나다·호주 주택 제도와 효과 계수 | 제도 근거는 국가별 `sourceNote` |
| `trade.json` | 국가별 수출 상대 비중 | `tools/tradedata/build_trade.py` (UN Comtrade 공개 API, 매우 느림, 캐시 사용 권장) |
| `regimes.json` | 모든 나라의 V-Dem 체제 분류 | `tools/politicsdata/build_regimes.py` (OWID) |
| `playable.json`, `politics.json`, `parliaments.json`, `elections.json`, `constitutions.json`, `leaders.json`, `approval.json`, `central_banks.json`, `society_identities.json`, `organizations.json`, `diplomacy.json`, `military.json`, `events.json`, `names.json` | 손으로 조사·작성한 자료 | 직접 편집. 근거는 `sourceNote`/`source`에 |

- 도구는 모두 `--cache-dir <폴더>`를 받는다. 원본 응답을 캐시해 두고 다시 돌리면 내려받지 않는다. 세션 스크래치패드를 캐시로 쓰면 된다.
- Python은 시스템 `curl`로 받는다. python.org판 Python에는 인증서가 없어 urllib HTTPS가 실패한다.
- `economy.json`을 다시 만들면 거시 초기값이 바뀔 수 있다. 원본 자료가 갱신되면 결과가 달라지므로 필요할 때만 돌린다.
- JSON 로더는 모르는 키를 허용하지 않는다(`ignoreUnknownKeys = false`). 필드를 추가하면 Kotlin 데이터 클래스도 함께 고친다.

## 6. 검증 방법

영구 테스트 코드는 두지 않는다. 대신 임시 검증을 하고 끝나면 지운다(AGENTS.md 2번).

- **장기 실행 검증**
  1. `simulation/build.gradle.kts`를 백업한다.
  2. `JavaExec` 태스크를 임시로 추가한다(`classpath = sourceSets["main"].runtimeClasspath`).
  3. `simulation/.../TempVerify.kt`에 `main`을 만들어 30~50년을 돌린다. 매년 1월 1일 지표, 선거, 전쟁, 소식, NaN 여부를 출력한다.
  4. 결정이 필요한 사건은 첫 선택지로 넘긴다.
  5. 끝나면 파일을 지우고 빌드 파일을 되돌린다.
  - 한 나라 50년은 몇 초면 끝난다.
- **화면 확인**
  - 앱 창을 직접 조작할 때는 매번 맨 앞 프로세스가 Axia(java)인지 확인한다. 사용자가 다른 앱을 쓰고 있으면 입력을 보내지 않는다.
  - 창을 건드릴 수 없을 때는 Compose `ImageComposeScene`으로 패널을 PNG로 렌더하는 임시 `main`을 쓴다. `desktopApp`에 임시 `JavaExec` 태스크를 두고 `-Djava.awt.headless=true`로 돌린 뒤, 끝나면 지운다.
- **확인할 것**
  - 32개국 장기 실행에서 NaN과 폭주(부채·물가·환율 상한 고착)가 없어야 한다.
  - 정권 교체·전쟁·사건 빈도가 그럴듯해야 한다.
  - 같은 시드면 같은 결과가 나와야 한다.
  - 지역 GDP 비중과 경제활동인구 비중은 각각 합이 1이다. 지역 실업률은 경제활동인구로, 지역 산업 비중은 각각 GDP와 취업자로 가중하면 전국 값과 일치해야 한다.
  - 주택 공급 법안은 착공 뒤 해당 국가의 공기만큼 지나서 준공에 반영되어야 한다. 지원 지출은 법안·월 예산에서 대조한다.
  - 상품별·국가별로 기초 재고 + 생산 + 수입 = 기말 재고 + 실제 소비 + 수출이다. 유입·유출 선적 합과 상대 장부를 대조하고, 첫달 제재와 제재 해제 이후 물량·가격 반응을 확인한다.
  - 브라질 가격차 보조는 지급 물량 × 톤당 가격차가 농가와 정부 양쪽에 같은 달 한 번만 계상되어야 한다. 평년 수확 합계, 비수기 이월과 전년비 가격, 은퇴층 인원과 65세 이상 인구의 일치도 확인한다.
  - 파키스탄은 2024-02-07까지 과도 내각이 법안을 심의한다. 다음 날에는 지역구 266석·유보석 70석, 무소속 유보석 0석, 과반 169표, 과도 총리 교체와 심의안의 초안 복귀를 확인한다. 다른 국가로 플레이할 때의 선거 소식, 5년 뒤 총선과 쿠데타에 따른 선거 중단도 확인한다.
  - 부도·협상 시작 때 원금이 감면되지 않아야 한다. 결산 후 부채는 직전 잔액 + 기초지출 + 이자 − 세입 − 합의 감면액이다. 연장안 합의·반려, 14일 무응답, 교환채 만기까지 확인한다.

## 7. 자주 하는 확장

- **국가 모듈 추가**: `country/<iso3>/<이름>Module.kt`에서 `CountryModule`을 구현하고 `CountryModules`의 `specialized`와 `create`에 등록한다.
  - 필요한 훅만 덮어쓴다: 고유 정책 `policies`/`policyValue`/`enact`, 거부 지점 `billChecks`, 강행 처리 `forceThrough`, 월간 효과 `advanceMonth`, 화면 지표 `indicators`.
  - 고유 정책의 성향·집단 반응 계수는 `CountryPolicyItem`에 넣는다. 정책 화면과 법안 평가에 자동으로 나온다.
  - 월간 효과의 산출 갭 충격은 갭 지속성(0.94) 때문에 약 17배로 누적된다. 매달 주는 충격은 작게 둔다.
- **사건 추가**: `events.json`에 정의를 추가한다.
  - 변수는 `EventContext.VARIABLES`에 있는 것만 쓸 수 있다. 새 변수는 `Nation.eventValues`나 `Simulation.sharedEventValues`에서 채운다.
  - 특수 처리(쿠데타, 반란처럼)가 필요하면 `Simulation`에서 `definitionId`로 분기한다.
- **정책 항목 추가**: `PolicyItem`은 sealed다. 새 종류를 넣으면 `when` 분기가 컴파일 오류로 드러난다(`Politics`, `Identity.reaction`, `PoliticsPanel.describe`).
- **스냅숏에 값 추가**: 시뮬레이션의 `*Snapshot` 데이터 클래스에 필드를 추가하고, 패널은 `GameScreen`에서 넘긴다.
- **지도 모드 추가**: `MapMode`에 항목을 추가하고 `MapData`로 값을 읽는다. 플레이어 지역 단위 색은 `MapMode.regionColor`가 정한다.

## 8. 코드와 문서 규칙

- 주석·KDoc·화면 문구는 한국어로 쓴다.
  - 조사는 `text/Korean.kt`의 `String.josa()`를 쓴다. "이(가)" 같은 병기를 직접 쓰지 않는다.
  - 화면 문구에 국가 코드를 그대로 쓰지 않고 나라 이름으로 바꾼다.
- 수치 계수는 이름 있는 상수로 두고 KDoc에 의미를 적는다. 게임 가정이면 "게임 가정"이라고 쓴다.
- 문제를 가리지 않고 근본 원인을 고친다(AGENTS.md 3번). 예를 들어 폭주를 상한으로 자르기 전에 원인 피드백 고리를 찾는다.
- **커밋**: 사용자가 요청할 때만 한다. 규칙은 `.claude/skills/git-commit`(`type(scope): 한국어 설명`, 본문 없음)이다.
- **태그**: `v*` 태그는 릴리스 워크플로(`.github/workflows/release.yml`)를 실행한다. `checkpoint-*` 태그는 작업 시점 표시용이다.
- 모델이나 데이터를 바꾸면 DESIGN.md의 해당 절, 14장(데이터 출처), 16장(마일스톤), 18장(알려진 한계)을 함께 고친다. README는 최소한으로 둔다.
