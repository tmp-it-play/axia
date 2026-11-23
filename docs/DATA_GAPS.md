# 시작 데이터의 결측과 추정

2024년 1월 1일에 시작하는 **199개 경제와 32개 플레이 국가**를 기준으로 한다. 경제 통계는 주로 2023년을 다루며 이후 발간된 소급 통계도 포함한다. 국가 코드는 지도와 같다. 코소보는 KOS, 팔레스타인은 PSX, 남수단은 SDS다. 국가별 결측 목록은 부록에, 계산 규칙은 [게임 설계](DESIGN.md)에 있다.

## 국가별 시작 통계

| 항목 | 남은 결측과 현재 대체값 | 보완에 필요한 자료 |
|---|---|---|
| 거시·재정·사회 | 91개국에 중앙값·수동값 등으로 채운 항목이 있다. GDP는 CYN·PRK·SOL·VAT, 인구 및 인구증가율은 CYN·SOL·VAT가 해당한다. 부채가 낮아 역산이 불안정한 실효이율도 추정에 포함한다. | 같은 통계 범위의 국민계정, 결산, 인구와 원자료의 기준연도. [경제 생성기](../tools/econdata/build_economy.py) |
| 세입 구성 | 일반정부의 다섯 세목을 같은 자료로 확보하지 못한 나라는 112개국이다. 26개국은 중앙정부, 23개국은 보고 범위가 명시되지 않은 WoRLD, 63개국은 WDI와 소득군별 배분을 쓴다. 사회보험료 비중의 결측 표시는 55개국이다. | 같은 연도·범위의 총세입과 개인소득세·법인세·상품서비스세·무역세·사회보험료. IMF의 사회보장기여금(G121) 밖의 사회기여금(G122)은 기타로 분류하며 WDI 사회기여금의 범위는 더 넓다. [IMF GFS](https://data.imf.org/en/Datasets/GFS_SOO) · [IMF WoRLD](https://www.imf.org/en/topics/fiscal-policies/world-revenue-longitudinal-database) |
| 금 제외 준비자산 | 25개국은 수입 대금 3개월분, 미얀마는 관측 월이 확인되지 않는 WDI 연간 값을 쓴다. | 국가별 2023년 말 달러 환산 잔액. 준비자산의 통화별 구성과 중앙은행 간 스와프도 미수록이다. 국제투자대조표는 같은 보고 기간의 금을 빼거나 금 외 구성 항목을 합산한다. [IMF 국제유동성](https://data.imf.org/en/Datasets/IL) · [IMF 국제투자대조표](https://data.imf.org/en/Datasets/IIP) · [WDI](https://data.worldbank.org/indicator/FI.RES.XGLD.CD) |
| 통화정책 | 34개국은 기준 통화 금리, 102개국은 국가별 추정치나 물가 기반 수식을 쓴다. 통화 체제가 미수록된 119개국 중 19개국은 시작 금리만 관측됐다. | 같은 정책수단의 2023년 말 금리, 물가 목표, 환율제도. 총재의 임기·성향, 정책 반응과 고물가국의 추정 금리 상한은 모형 가정이다. [국가별 정책수단](../simulation/src/main/resources/world/central_banks.json) · [BIS 정책금리](https://data.bis.org/topics/CBPOL) |
| 산업·고용·인구 구조 | 95개국에서 구조 지표를 하나 이상 추정한다. 섬유·식품·기술 제조 비중의 결측은 각각 88·83·49개국이다. 대만은 여러 구조값이 수동 근사치다. 병력·순이동은 결측 시 번들에 0을 저장한다. | 제조업 세분류, 산업연관표, 직업별 고용·소득, 인구이동. 건설·광업·서비스업 세분 및 직업별 조세 부담에도 모형 배분을 쓴다. [구조 자료 생성기](../tools/econdata/build_structure.py) |
| 지역 인구·경제 | 1,497개 구역 중 1,192곳의 인구 지분을 도시 인구·면적으로 추정한다. 그중 506곳에는 지도상의 도시 인구 표본도 없다. 미국 23곳과 프랑스 20곳은 해당 국가의 공식 지역 인구표 범위 밖이다. 지역 산업 생산·고용·실업·도시화율·소득도 모형 배분이다. | 지도 구역과 일치하는 지역 인구·연령·고용, 통근권과 범죄 신고 통계. 한국은 외국인을 제외한 2023년 말 주민등록인구, 미국은 2023년 7월 추계, 캐나다·유럽 자료는 2024년 1월 1일 기준이다. [국가별 원표·기준일](../simulation/src/main/resources/world/region_population.json) · [구역 코드 대응표](../tools/geodata/region_population_codes.csv) |

## 상품과 교역

| 자료 | 남은 범위와 대체 방식 |
|---|---|
| 곡물 생산과 수확기 | 연간 생산 미보고 20개국은 AND BHR CYN GNQ KIR KNA LCA LIE MCO MHL NRU PLW SGP SMR SOL SYC TON TUV VAT WSM이다. 월별 생산은 169개국에서 균등 배분하고, 10개국은 작물 달력으로 수확 비중을 추정한다. 작물별·지역별 월 수확량과 창고 재고가 필요하다. [수확 달력과 출처](../simulation/src/main/resources/world/grain_harvest.json) |
| 곡물 소비·재고 | 일본·북한 등 27개국은 수록국의 인구 가중 1인당 공급량으로 수요를 추정한다. 172개국의 가중치는 식량수급표의 국내 공급 추계(품목 2905, 요소 5301)다. 가공·사료를 포함하며 쌀의 중량 기준이 생산 통계와 달라 세계 수요 합계를 게임 생산량에 맞춘다. 용도별 소비·개시 재고·저장 한도와 가격 탄력성은 추정이다. [FAOSTAT 식량수급표](https://www.fao.org/faostat/en/#data/FBS) |
| 원유 | 연간 생산 미보고 12개국은 AND CYN FSM KOS LIE MCO MHL PLW SMR SOL TUV VAT다. 수요는 EIA 석유·기타 액체연료 소비로 대체하고, 이마저 없는 AND CYN LIE MCO MHL PLW SMR SOL TUV VAT는 GDP로 추정한다. 곡물과 원유 모두 미보고(null)와 보고값 0을 구분하며 미보고국에는 생산을 배정하지 않는다. [품목별 수치와 출처](../simulation/src/main/resources/world/commodities.json) |
| 양자 교역 | 전체 상품 수출 비중을 쓰며 CUB·NPL은 2022년, 40개국은 상대국의 2023년 수입 신고가 기준이다. CYN KOS LIE MCO SOL은 상대국 자료가 없다. 미보고·잔여 경로는 거리와 경제 규모로 배분한다. 품목별 양자 물량, 기준 월 현물가격·계약 운임, 서비스 교역은 별도 자료가 필요하다. [국가별 신고 범위](../simulation/src/main/resources/world/trade.json) |

## 정치와 사회

| 항목 | 자료가 더 필요한 부분 |
|---|---|
| 정부 지지율·정치 체제 | CHN PRK IRN SAU EGY VNM RWA는 공개 조사 부재·신뢰성 문제, LVA PAK는 신정부·과도 내각으로 시작 지지율이 추정이다. 조사 문항도 국가별로 다르다. 비플레이 167개국은 상세 여론·정당 자료가 없으며, 전체 199개국 중 25개국은 세계 체제 분류가 없다. [시작 지지율](../simulation/src/main/resources/world/approval.json) · [체제 분류](../simulation/src/main/resources/world/regimes.json) |
| 의회·선거 | 22개국은 2018~2023년 총선 의석을 사용한다. FRA·UKR은 2023년 12월 본회의, TWN은 2023년 4월 현원이 기준이다. 출처 기준일 이후 당적 이동·보궐·공석, 선거구 경계와 후보별 개표, 주별 경선 일정, 인도네시아 지역별 득표 요건을 보완할 수 있다. 혼합제의 잔여 의석은 전국 단위로 단순화한다. [의석 자료](../simulation/src/main/resources/world/parliaments.json) · [선거 자료](../simulation/src/main/resources/world/elections.json) |
| 파키스탄 | 2018년 270개 지역구 잠정 개표에서 14개 항목 51,687,498표를 기준으로 쓴다. 제외된 71개 정당의 1,436,235표, 연기된 2개 지역구, 재검표, 2024년 소속 변경은 빠져 있다. 여성 유보석 60석은 실제로 주별 배분이나 게임에서는 비무슬림 유보석 10석과 함께 전국 배분한다. 실제 개원·총리 선출 시차 및 정당별 지지층 직업 분포도 생략한다. [당시 헌법 제51·52·91조](https://na.gov.pk/uploads/documents/63ea176f52421_610.pdf) · [후보별 득표 보존본](https://github.com/colincookman/pakistan_election_results_2018/blob/81761f218106a8b054e4b70651d002f4050a1696/data/pk_candidate_data_consolidated_090418.csv) |
| 유권자·조직·인물 | 노조 조직률은 PRK IRN SAU, 종교 중요도는 CHN GBR PRK AUS FIN IRN TWN에서 추정한다. 종교 조사는 2009년, 노조 통계는 연도가 서로 다르다. 정당별 지지층·경선 및 결선 지지 이전, 조직 회원 비율·정부 태도, 지도자별 취임일, 상원 정당별 의석·표결, 실제 연정 협약·내각 인선 자료가 필요하다. 비예산 규제 다수는 소득군 추정, 쿠데타 이력은 THA PAK EGY TUR만 수록한다. [성향 자료](../simulation/src/main/resources/world/society_identities.json) |
| 은퇴와 직업 | 65세 이상을 은퇴층으로 간주한다. 실제 퇴직 연령·고령 취업·조기 은퇴·연금 수급자와 지역별 직업 이동 자료가 필요하다. 세대별 성향과 직업 전환은 모형 계수다. |

## 정책·사건의 모형 가정

- **재정·기업.** 채권자별 초기 국채 비중, 민간 자본 유출입, IMF 대출 조건, 기업 진입·퇴출은 추정이다. 채권자·통화·만기별 잔액, 국부펀드와 월별 국제수지가 필요하다. 동티모르처럼 석유기금 인출로 충당한 적자는 게임에서 부채로 잡힐 수 있다.
- **국가·지역 정책.** 영국·캐나다·호주의 주택 정책에는 실제 지방별 인허가·착공·준공과 집행액, 브라질 곡물 보조에는 지역·품종별 최저가격·산지가격과 경매 낙찰 물량이 필요하다. 지역 사업 단가, AI 정당의 예산 성향·발의 주기, 국가별 정책 효과는 게임 가정이다. [주택 제도별 출처](../simulation/src/main/resources/world/housing_programs.json) · [Conab 2023년 집행보고서](https://arquivosportal.conab.gov.br/api/public/dl/mCCRPnym/arquivos/auditoria/Relatorio-de-Gestao-2023.pdf) · [국가별 정책 근거와 가정](COUNTRY_MODULES.md)
- **지도·외교·피란.** 단순화한 형상에서 작은 섬·해협·인접 관계가 달라질 수 있다. 외교 관계와 제재 강도, 점령 비율·분쟁 긴장은 추정이며 가자와 서안 C구역을 구분하지 않는다. 운송 충격은 공통 계수를 쓰며 항구별 처리량·환적 자료가 없다. 기존 피란민과 전쟁 피해 자산은 시작 재고가 없어 게임 중 발생분부터 집계한다. [외교 자료](../simulation/src/main/resources/world/diplomacy.json) · [분쟁 자료](../simulation/src/main/resources/world/military.json)
- **사건·장기 추세.** 32개국에 국가별 재난 노출 추정치가 있고 나머지는 공통 위험을 쓴다. 사건 확률·손실과 선택지 효과, 기후·배출·기술 경로의 보정에는 국가별 발생률과 장기 시계열이 필요하다. [사건 자료](../simulation/src/main/resources/world/events.json)

## 부록 · 국가별 결측 조회

거시·재정·사회 및 구조 표는 번들의 `estimated` 필드를 집계한다. 같은 국가 집합은 한 행으로 묶었다. 세입 자료의 보고 범위는 `budget.revenueCompositionSource`로 구분한다. 수동 대체와 중앙값 추정은 같은 표시를 쓰며 상한·대체 연도만 적용한 값에는 표시가 없을 수 있다.

<details>
<summary>거시·재정·사회 초기값 · 199개국 중 91개국에 추정 항목</summary>

| 항목과 원본 키 | 국가 수 | 해당 국가 코드 |
|---|---:|---|
| 사회보험료 (`socialContributionShare`) | 55 | AFG, BDI, BEN, BGD, BHR, BLZ, BOL, BRB, BRN, BWA, COD, COG, CUB, CYN, DJI, DZA, EGY, ERI, FSM, GMB, GNQ, HTI, JAM, KNA, KWT, LAO, LBR, LBY, LCA, MCO, MDV, MHL, MLI, MRT, NER, NGA, OMN, PAK, PLW, PRK, PSX, QAT, SDN, SOL, STP, SYR, TKM, TLS, TUN, TWN, VAT, VEN, VNM, YEM, ZWE |
| 국방비 (`defense`) | 43 | AND, ARE, ATG, BHS, BRB, BTN, COM, CUB, CYN, DJI, DMA, ERI, FSM, GRD, KIR, KNA, LAO, LCA, LIE, MCO, MDV, MHL, NRU, PLW, PRK, PSX, SLB, SMR, SOL, STP, SUR, SYR, TKM, TON, TUV, TWN, UZB, VAT, VCT, VEN, VUT, WSM, YEM |
| 무역세 (`tradeTaxShare`) · 소득·이익세 (`incomeProfitTaxShare`) · 소비세 (`consumptionTaxShare`) | 39 | AFG, BEN, BLZ, BOL, BRB, BRN, CUB, CYN, DJI, DZA, EGY, ERI, GMB, HTI, KWT, LBR, LBY, LCA, MCO, MRT, NER, NGA, OMN, PAK, PRK, PSX, QAT, SDN, SOL, STP, SYR, TKM, TUN, TWN, VAT, VEN, VNM, YEM, ZWE |
| 지니계수 (`gini`) | 34 | AFG, AND, ATG, AZE, BHR, BHS, BRN, CUB, CYN, DMA, ERI, GUY, KHM, KNA, KWT, LBY, LIE, MCO, NZL, OMN, PLW, PNG, PRK, SAU, SGP, SMR, SOL, SOM, TKM, TTO, TWN, VAT, VCT, VEN |
| 교육비 (`education`) | 24 | AFG, BRN, CYN, DJI, EGY, ERI, GNB, GNQ, GUY, IRQ, KOS, LBY, LIE, MKD, MNE, PRK, SDN, SDS, SOL, SYR, TWN, VAT, VEN, YEM |
| 농업 고용 (`agricultureEmployment`) · 산업 고용 (`industryEmployment`) · 자영업 (`selfEmployment`) · 서비스 고용 (`servicesEmployment`) | 20 | AND, ATG, CYN, DMA, FSM, GRD, KIR, KNA, KOS, LIE, MCO, MHL, NRU, PLW, SMR, SOL, SYC, TUV, TWN, VAT |
| 이자 (`interest`) | 15 | AFG, BRN, CUB, CYN, KWT, LIE, MCO, PRK, PSX, SOL, SYR, TKM, TUV, TWN, VAT |
| 실업률 (`unemployment`) | 15 | ATG, CYN, DMA, FSM, GRD, KIR, KNA, MCO, MHL, NRU, PLW, PSX, SOL, TUV, VAT |
| 부채 (`debt`) | 9 | CUB, CYN, MCO, PRK, SOL, SYR, VAT, VEN, YEM |
| 지출 (`expense`) · 세입 (`revenue`) | 7 | CUB, CYN, MCO, PRK, SOL, SYR, VAT |
| 성장률 (`growth`) | 6 | CYN, ERI, PRK, SOL, SYR, VAT |
| 보건비 (`health`) | 6 | CYN, KOS, PRK, SOL, TWN, VAT |
| 물가 (`inflation`) | 5 | CYN, ERI, PRK, SOL, VAT |
| 아동 비중 (`childShare`) · 고령 비중 (`elderlyShare`) | 4 | CYN, SOL, TWN, VAT |
| GDP (`gdp`) · 성장 변동성 (`growthVolatility`) · 잠재성장 (`potentialGrowth`) | 4 | CYN, PRK, SOL, VAT |
| 인구 (`population`) · 인구증가율 (`populationGrowth`) | 3 | CYN, SOL, VAT |

</details>

<details>
<summary>일반정부 세목별 구성을 대체한 112개국</summary>

| 보고 범위 또는 추정 방식 | 국가 수 | 국가 코드 |
|---|---:|---|
| 사회보장기금을 포함한 중앙정부 (`GFS_CENTRAL_INCL_SOCIAL_SECURITY`) | 1 | URY |
| 사회보장기금을 제외한 중앙정부 (`GFS_CENTRAL_EXCL_SOCIAL_SECURITY`) | 2 | PAN, SYC |
| 중앙정부 예산회계 (`GFS_BUDGETARY_CENTRAL`) | 23 | ARG, BHS, COM, ETH, FJI, GHA, GIN, GNB, LSO, MAR, MOZ, MWI, MYS, NPL, PNG, SDS, SLB, TGO, TON, TUV, TZA, VUT, ZMB |
| 정부 범위 미상 (`WORLD_UNSPECIFIED`) | 23 | ATG, AUS, CAF, CAN, CMR, CPV, DMA, ECU, GRD, GUY, IND, IRN, LIE, MNE, NIC, PER, SLE, SRB, SUR, SWZ, TCD, TTO, VCT |
| WDI·소득군 모형 배분 (`WDI_MODEL`) | 63 | AFG, BDI, BEN, BGD, BHR, BLZ, BOL, BRB, BRN, BTN, BWA, COD, COG, CUB, CYN, DJI, DZA, EGY, ERI, FSM, GAB, GMB, GNQ, HND, HTI, IRQ, JAM, KNA, KWT, LAO, LBN, LBR, LBY, LCA, LKA, MCO, MDG, MDV, MHL, MLI, MMR, MRT, NER, NGA, OMN, PAK, PLW, PRK, PSX, QAT, SDN, SOL, STP, SYR, TKM, TLS, TUN, TWN, VAT, VEN, VNM, YEM, ZWE |

</details>

<details>
<summary>곡물 수요·금 제외 준비자산·시작 기준금리의 대체 범위</summary>

| 처리 방식 | 국가 수 | 국가 코드 |
|---|---:|---|
| 곡물 기본 수요를 인구로 추정 | 27 | AND, BDI, BEN, BRN, CAF, CUB, CYN, DMA, ERI, GNQ, JPN, KOS, LIE, MCO, MLI, PLW, PRK, PSX, SDN, SDS, SGP, SMR, SOL, SOM, TCD, TGO, VAT |
| 준비자산을 수입 3개월분으로 추정 | 25 | AFG, BEN, CUB, CYN, ERI, FSM, IRN, LIE, MCO, MHL, MLI, MRT, NRU, PLW, PRK, SDN, SEN, SOL, SOM, SYR, TGO, TKM, VAT, VEN, YEM |
| 준비자산을 WDI 연간 값으로 대체 | 1 | MMR |
| 기준금리에 고정환율 기준 통화 금리를 적용 | 34 | AND, ARE, BEN, BFA, BGR, BHR, BIH, CAF, CIV, CMR, COG, ECU, FSM, GAB, GNB, GNQ, JOR, KOS, MCO, MHL, MLI, MNE, NER, OMN, PAN, PLW, QAT, SEN, SLV, SMR, TCD, TGO, TLS, VAT |
| 기준금리에 국가별 지정 추정치 또는 물가 기반 추정식을 적용 | 102 | AFG, AGO, ALB, ARM, ATG, AZE, BDI, BGD, BHS, BLR, BLZ, BOL, BRB, BRN, BTN, BWA, COD, COM, CPV, CRI, CUB, CYN, DJI, DMA, DOM, DZA, ERI, ETH, FJI, GEO, GHA, GIN, GMB, GRD, GTM, GUY, HND, HTI, IRN, IRQ, JAM, KAZ, KEN, KGZ, KHM, KIR, KNA, LAO, LBN, LBR, LBY, LCA, LIE, LKA, LSO, MDA, MDG, MDV, MMR, MNG, MOZ, MRT, MUS, MWI, NAM, NGA, NIC, NPL, NRU, PNG, PRK, PRY, PSX, SDN, SDS, SGP, SLB, SLE, SOL, SOM, STP, SUR, SWZ, SYC, SYR, TJK, TKM, TON, TTO, TUN, TUV, TZA, UGA, URY, UZB, VCT, VEN, VUT, WSM, YEM, ZMB, ZWE |

</details>

<details>
<summary>구조 지표 · 199개국 중 95개국에 추정 항목</summary>

| 항목과 원본 키 | 국가 수 | 해당 국가 코드 |
|---|---:|---|
| 섬유 제조 (`textileManufacturing`) | 88 | AFG, AGO, AND, ATG, BDI, BEN, BFA, BHR, BHS, BLZ, BRB, BRN, BTN, CAF, CMR, COD, COG, COM, CUB, CYN, DJI, DMA, DOM, DZA, ERI, ETH, FSM, GAB, GHA, GIN, GMB, GNB, GNQ, GRD, GTM, GUY, HND, HTI, JAM, KHM, KIR, KNA, LAO, LBR, LBY, LCA, LIE, MCO, MDV, MHL, MOZ, MRT, NER, NGA, NRU, PAK, PLW, PNG, PRK, SDN, SDS, SLB, SLE, SLV, SMR, SOL, SOM, STP, SUR, SWZ, SYC, SYR, TCD, TJK, TKM, TLS, TON, TTO, TUV, TWN, UGA, VAT, VCT, VEN, VUT, WSM, YEM, ZWE |
| 식품 제조 (`foodManufacturing`) | 83 | AFG, AGO, AND, ATG, BDI, BEN, BFA, BHR, BHS, BLZ, BRB, BRN, BTN, CAF, COG, COM, CUB, CYN, DMA, DOM, DZA, ERI, ETH, FSM, GAB, GHA, GIN, GMB, GNQ, GRD, GTM, GUY, HND, HTI, JAM, KHM, KIR, KNA, LAO, LBR, LBY, LCA, LIE, MCO, MDV, MOZ, MRT, NER, NGA, NRU, PAK, PLW, PNG, PRK, SDN, SDS, SLB, SLE, SLV, SMR, SOL, SOM, STP, SUR, SWZ, SYC, SYR, TCD, TJK, TKM, TLS, TON, TTO, TUV, TWN, UGA, VAT, VCT, VEN, VUT, WSM, YEM, ZWE |
| 기술 제조 (`techManufacturing`) | 49 | AND, ATG, BEN, BFA, BTN, COD, COM, CYN, DJI, DMA, DOM, FSM, GIN, GNB, GNQ, GRD, GUY, KIR, KNA, KOS, LBR, LIE, LSO, MCO, MHL, MLI, MRT, NRU, PLW, PRK, SDN, SDS, SLB, SLE, SMR, SOL, SOM, STP, SYC, TCD, TGO, TKM, TLS, TUV, TWN, VAT, VCT, VUT, WSM |
| 병력 (`armedForces`) | 30 | AND, BTN, COM, CYN, DMA, FSM, GRD, KIR, KNA, KOS, LCA, LIE, MCO, MDV, MHL, NRU, PLW, PRK, SLB, SMR, SOL, STP, SWZ, TON, TUV, TWN, VAT, VCT, VUT, WSM |
| 수출 비중 (`exportShare`) · 교역 개방도 (`tradeOpenness`) | 29 | AND, BDI, BRB, CYN, DMA, ERI, GRD, GUY, JAM, JOR, KNA, LAO, LBR, LCA, LIE, MCO, MMR, NGA, PNG, PRK, SDS, SOL, SUR, TTO, TUV, TWN, VAT, VCT, YEM |
| 제조업 부가가치 (`manufacturingValueAdded`) | 19 | BGR, CYN, ERI, LBR, LBY, MCO, NRU, PRK, SDN, SDS, SOL, SOM, SYR, TKM, TUV, TWN, VAT, VEN, YEM |
| 석유 지대 (`oilRents`) | 17 | AND, CYN, ERI, FSM, LIE, MCO, MHL, PLW, PRK, SDS, SMR, SOL, TUV, TWN, VAT, VEN, YEM |
| 농업 부가가치 (`agricultureValueAdded`) | 13 | CYN, ERI, MCO, NRU, PRK, SDS, SOL, SOM, TUV, TWN, VAT, VEN, YEM |
| 산업 부가가치 (`industryValueAdded`) · 서비스 부가가치 (`servicesValueAdded`) | 12 | CYN, ERI, NRU, PRK, SDS, SOL, SOM, TUV, TWN, VAT, VEN, YEM |
| 자원 지대 (`resourceRents`) | 9 | CYN, ERI, PRK, SDS, SOL, TWN, VAT, VEN, YEM |
| 출생률 (`birthRate`) · 사망률 (`deathRate`) · 출산율 (`fertility`) · 기대수명 (`lifeExpectancy`) · 순이동 (`netMigration`) · 도시 비중 (`urbanShare`) | 4 | CYN, SOL, TWN, VAT |

</details>

<details>
<summary>체제·의석·무역 자료의 대체 범위</summary>

| 자료 | 국가 수 | 해당 국가 코드 |
|---|---:|---|
| 세계 체제 분류 미수록 | 25 | AND, ATG, BHS, BLZ, BRN, CYN, DMA, FSM, GRD, KIR, KNA, LCA, LIE, MCO, MHL, NRU, PLW, PSX, SMR, SOL, TON, TUV, VAT, VCT, WSM |
| 시작 의석의 출처가 이전 총선 | 22 | AUS, CAN, DEU, EGY, EST, FIN, IDN, IND, IRN, ISR, ITA, JPN, LTU, LVA, POL, PRK, RUS, RWA, THA, TUR, VNM, ZAF |
| 교역 상대의 수입 신고로 역산 | 40 | AFG, BGD, BLR, DEU, DZA, ERI, FSM, GBR, GIN, GNB, GNQ, HTI, KIR, KNA, LBY, LCA, MHL, NRU, NZL, PAK, PLW, PRK, RUS, SDN, SDS, SLB, SLE, SMR, SOM, SYR, TCD, TKM, TUV, TWN, USA, VAT, VEN, VNM, VUT, YEM |

</details>
