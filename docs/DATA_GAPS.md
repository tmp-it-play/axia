# 시작 데이터의 결측과 추정

2024년 1월 1일에 시작하는 **199개 경제와 32개 플레이 국가**를 기준으로 한다. 경제 통계는 주로 2023년을 다루며 이후 발간된 소급 통계도 포함한다. 국가 코드는 지도와 같다. 코소보는 KOS, 팔레스타인은 PSX, 남수단은 SDS다. 국가별 결측 목록은 부록에, 계산 규칙은 [게임 설계](DESIGN.md)에 있다.

## 국가별 시작 통계

| 항목 | 남은 결측과 현재 대체값 | 보완에 필요한 자료 |
|---|---|---|
| 거시·재정·사회 | 86개국의 293개 국가·지표 조합에 중앙값·수동값 등을 쓴다. GDP와 인구증가율에 게임 대체값을 쓰는 곳은 바티칸이다. 시작 인구에는 199개 경제 모두 공표된 인구 통계를 쓴다. 부채가 낮아 역산이 불안정한 실효이율도 추정에 포함한다. | 같은 통계 범위의 국민계정, 결산, 인구와 원자료의 기준연도. [경제 생성기](../tools/econdata/build_economy.py) |
| 세입 구성 | 일반정부의 다섯 세목을 같은 자료로 확보하지 못한 나라는 112개국이다. 26개국은 중앙정부, 63개국은 정부 범위가 명시되지 않은 IMF WoRLD를 쓴다. 이 중 40개국은 공표된 세목을 보존하고 미보고 세목에만 잔액을 배분한다. 23개국은 WDI·소득군별 배분을 쓴다. 사회보험료 비중에 결측 표시가 있는 나라는 50개국이다. | 같은 연도·범위의 총세입과 개인소득세·법인세·상품서비스세·무역세·사회보험료. IMF의 사회보장기여금(G121) 밖의 사회기여금(G122)은 기타로 분류하며 WDI 사회기여금의 범위는 더 넓다. [IMF GFS](https://data.imf.org/en/Datasets/GFS_SOO) · [IMF WoRLD](https://www.imf.org/en/topics/fiscal-policies/world-revenue-longitudinal-database) |
| 금 제외 준비자산 | 25개국은 수입 대금 3개월분, 미얀마는 관측 월이 확인되지 않는 WDI 연간 값을 쓴다. | 국가별 2023년 말 달러 환산 잔액. 준비자산의 통화별 구성과 중앙은행 간 스와프도 미수록이다. 국제투자대조표는 같은 보고 기간의 금을 빼거나 금 외 구성 항목을 합산한다. [IMF 국제유동성](https://data.imf.org/en/Datasets/IL) · [IMF 국제투자대조표](https://data.imf.org/en/Datasets/IIP) · [WDI](https://data.worldbank.org/indicator/FI.RES.XGLD.CD) |
| 통화정책 | 122개국은 자국 중앙은행이나 통화동맹 발권기관의 금리로 시작한다. 15개국은 기준 통화 금리를, 62개국은 모형 추정치를 쓴다. | 같은 정책수단의 2023년 말 금리·물가 목표·환율제도. 113개국은 통화 체제를 모형으로 지정하며, 그중 53개국에는 공표 금리가 있다. [국가별 공표 자료](../simulation/src/main/resources/world/central_banks.json) · [BIS 정책금리](https://data.bis.org/topics/CBPOL) |
| 산업·고용·인구 구조 | 82개국의 272개 국가·지표 조합에 추정값을 쓴다. 섬유·식품·기술 제조 비중의 결측은 각각 74·71·45개국이다. 대만에는 병력·자원 지대·기술 제조 비중 추정이 남아 있다. 병력·순이동은 결측 시 번들에 0을 저장한다. | 제조업 세분류, 산업연관표, 직업별 고용·소득, 인구이동. 건설·광업·서비스업 세분 및 직업별 조세 부담에도 모형 배분을 쓴다. [구조 자료 생성기](../tools/econdata/build_structure.py) |
| 개인송금 | 수취는 186개국에 연간 총액(추계 포함), 5개국에 일부 경로·조사·하한이 있고 8개국이 결측이다. 지급은 총액 196개국, 하한 1개국, 결측 2개국이다. 2023년 WDI 수취 167개국·지급 171개국을 기준으로 국가 국제수지와 세계은행 자료 등을 보완했다. | 미보고 국가·경로의 금액, 월별 계절성과 양자 실측. [세계은행 수취](https://data.worldbank.org/indicator/BX.TRF.PWKR.CD.DT) · [세계은행 지급](https://data.worldbank.org/indicator/BM.TRF.PWKR.CD.DT) · [KNOMAD 양자 추계](https://data360files.worldbank.org/data360-data/data/WB_KNOMAD/WB_KNOMAD_BRE.csv) |
| 대외 투자소득 | 연간 투자소득 총액은 수취 182개국·지급 183개국에 있다. 이 중 수취 178개국·지급 180개국이 2023년 자료다. 배당·이자·재투자수익은 원표에서 식별할 수 있는 금액만 분리한다. | 수취 17개국·지급 16개국의 총액, 미분류 소득의 구성, 자산·상대국·월별 내역. [IMF 국제수지](https://data.imf.org/en/datasets/IMF.STA:BOP) · [국가별 보완 원표](../tools/econdata/investment_income_supplements.py) |
| 교역·부가가치 | 199개 경제 중 바티칸만 상품·서비스 수출 비중과 교역 개방도를 추정한다. 소말리아·소말릴란드·바티칸은 농업·산업·제조업·서비스 부가가치가, 모나코는 농업 부가가치가 비어 있다. | 소말리아 생산계정은 소말릴란드를 분리할 수 있어야 한다. 유엔 자료에는 유엔 자체 추계가 포함된다. 소말릴란드의 실험적 지출계정은 교역 비중에 적용했다. [유엔 국민계정](https://unstats.un.org/unsd/snaama/downloads) · [소말릴란드 통계청](https://www.govsomaliland.org/uploads/files/2025/09/2025-09-06-05-36-34-2988-1757136994.pdf) |
| 지역 인구·경제 | 32개 플레이 국가의 1,497개 구역 중 918곳의 인구 지분을 도시 인구·면적으로 추정한다. 그중 487곳에는 지도상의 도시 인구 표본도 없다. 미국 23곳과 프랑스 20곳은 해당 국가의 공식 지역 인구표 범위 밖이다. 지역 도시화율은 베트남 63개 성·시에만 공표 도시 인구를 적용하고, 나머지 1,434곳은 모형으로 배분한다. 지역별 생산·고용·실업·소득도 추정한다. | 지도 구역과 일치하는 지역 인구·연령·고용, 통근권과 범죄 신고 통계. 한국은 외국인을 제외한 2023년 말 주민등록인구, 미국은 2023년 7월 추계, 캐나다·유럽은 2024년 1월 1일 기준이다. 인도네시아는 2023년 6월 38개 주 추계를 지도 33구역으로 합산한다. 베트남은 2025년 통합 전 경계의 2023년 연평균, 일본·태국은 외국인을 포함한 등록인구, 브라질은 2023년 7월 1일 인구추계, 이집트는 2024년 1월 1일 인구추계다. [국가별 원표·기준일](../simulation/src/main/resources/world/region_population.json) · [구역 코드 대응표](../tools/geodata/region_population_codes.csv) |

대만의 인구동태에는 내정부 통계와 유엔의 2023년 순이동·도시 인구 추계를 적용한다. 북키프로스는 통계연감의 연령별 인구·조출생률·조사망률·기대수명, 노동력조사의 고용 구성을 쓴다. 합계출산율은 이듬해 연감의 2023년 값인 1.53명이다. 소말릴란드의 연령 비중은 국가개발계획의 2023년 연령별 인구 전망에서 계산한다. 국가별 고용 조사는 조사 월·대상 지역이 다르다. [대만·북키프로스·ILOSTAT 원표와 단위](../tools/econdata/local_population.py)

바티칸의 시작 인구는 2023년 6월 26일 기준 영내 거주자 764명이다. 전년의 같은 범위 집계가 없어 증가율은 추정한다. [바티칸 정부 인구표 보존본](https://web.archive.org/web/20240114093520/https://www.vaticanstate.va/it/stato-governo/note-generali/popolazione.html)

북한의 2023년 명목 GDP는 유엔 회계환율(달러당 110북한원)로 환산한 유엔 추계다. 실질성장률은 한국의 가격과 부가가치율로 추계한 한국은행 계열을 쓴다. 북키프로스 인구는 7월 1일 추계다. 소말릴란드 인구는 2014년 조사에서 연 2.93% 증가를 가정한 정부 전망이며, GDP는 통계청의 실험적 국민계정이다. 세 나라의 잠재성장률과 변동성은 각 연도 실질성장률로 계산한다. [유엔 북한 국민계정](https://unstats.un.org/unsd/snaama/CountryProfile?ccode=408) · [한국은행 추계](https://www.bok.or.kr/portal/bbs/B0000501/view.do?menuNo=201264&nttId=10086115) · [국가별 거시 원표와 시계열](../tools/econdata/local_macro.py)

코소보 교육비는 IMF 일반정부 현금기준 결산을 쓴다. 불평등 지표는 대만·싱가포르의 2023년 가처분소득과 뉴질랜드의 2022년 OECD 자료를 반영하며 조사 단위가 다르다. 콩고공화국 식품 제조업 비중은 2022년, 에리트레아 섬유·의류·가죽 비중은 2023년 UNIDO 조사에서 계산한다. 바하마의 2013년 가계 소비 지니계수와 각국 소득 지니계수는 조사 연도와 측정 대상이 다르다. ADB와 OECD의 국제산업연관표는 국가 간 분류를 맞춘 기관 추계를 포함한다. [국가별 원표와 통계 정의](../tools/econdata/local_macro.py) · [가계·노동 조사와 교육비](../tools/econdata/national_surveys.py) · [제조업 분류와 분모](../tools/econdata/structure_supplements.py)

## 상품과 교역

| 자료 | 남은 범위와 대체 방식 |
|---|---|
| 곡물 생산과 수확기 | 연간 생산 미보고 20개국은 AND BHR CYN GNQ KIR KNA LCA LIE MCO MHL NRU PLW SGP SMR SOL SYC TON TUV VAT WSM이다. 생산량이 양수인 178개국 중 115개국은 월별로 균등 배분한다. 몰타의 보고 생산량은 0이다. 수확 달력을 쓰는 63개국 중 JRC로 보완한 53개국은 국가 단위 표에서 지역·작기 한정이 없는 작물에만 계절성을 적용한다. 작물별·지역별 월 수확량과 창고 재고가 필요하다. [국가별 수확 달력](../simulation/src/main/resources/world/grain_harvest.json) · [JRC 달력과 국가별 적용 작물](../simulation/src/main/resources/world/grain_harvest_asap.json) |
| 곡물 소비·재고 | 북한 등 20개국은 수록국의 인구 가중 1인당 공급량으로 수요를 추정한다. 177개국은 FAO 현행판의 2023년 국내 공급 추계(품목 2905, 요소 5301)를 쓴다. 니카라과·세이셸은 같은 계열의 공개된 2010~2023년판을 사용한다. 사료·가공용을 포함하며 쌀의 중량 기준이 생산 통계와 달라 세계 수요를 게임 생산량에 맞춘다. 용도별 소비·개시 재고·가격 탄력성은 추정이다. [FAOSTAT 식량수급표](https://www.fao.org/faostat/en/#data/FBS) · [FAO 구판](https://data.apps.fao.org/catalog/en/dataset/2f264bb6-1238-459a-bf8b-0e2d0a16804a) |
| 원유 | 연간 생산 미보고 12개국은 AND CYN FSM KOS LIE MCO MHL PLW SMR SOL TUV VAT다. 수요는 EIA 석유·기타 액체연료 소비로 대체하고, 이마저 없는 AND CYN LIE MCO MHL PLW SMR SOL TUV VAT는 GDP로 추정한다. 곡물과 원유 모두 미보고(null)와 보고값 0을 구분하며 미보고국에는 생산을 배정하지 않는다. [품목별 수치와 출처](../simulation/src/main/resources/world/commodities.json) |
| 양자 교역 | 전체 상품 수출 비중을 쓰며 CUB·NPL은 2022년, 40개국은 상대국의 2023년 수입 신고가 기준이다. CYN KOS LIE MCO SOL은 상대국 자료가 없다. 미보고·잔여 경로는 거리와 경제 규모로 배분한다. 품목별 양자 물량, 기준 월 현물가격·계약 운임, 서비스 교역은 별도 자료가 필요하다. [국가별 신고 범위](../simulation/src/main/resources/world/trade.json) |

## 정치와 사회

| 항목 | 자료가 더 필요한 부분 |
|---|---|
| 정부 지지율·정치 체제 | CHN PRK IRN SAU EGY VNM RWA는 공개 조사 부재·신뢰성 문제, LVA PAK는 신정부·과도 내각으로 시작 지지율이 추정이다. 조사 문항도 국가별로 다르다. 비플레이 167개국은 상세 여론·정당 자료가 없으며, 전체 199개국 중 25개국은 세계 체제 분류가 없다. [시작 지지율](../simulation/src/main/resources/world/approval.json) · [체제 분류](../simulation/src/main/resources/world/regimes.json) |
| 의회·선거 | 시작 의석 자료 22개국은 2018~2023년 총선이 기준이고 FRA·UKR은 2023년 12월 본회의, TWN은 2023년 4월 현원이다. 당적 이동·공석의 실제 발생일, 투표소 경계·당시 후보·투표율과 지역별 지지 이전은 추가 자료가 필요하다. [의석 자료](../simulation/src/main/resources/world/parliaments.json) · [선거 제도](../simulation/src/main/resources/world/elections.json) |
| 미국·인도네시아 대선 | 미국은 2020년 주별 유효표와 2022년 하원 지역구, 양당의 기본 대의원 배분과 2024년 일정을 쓴다. 주별 당규·민주당 보너스 대의원과 자동 대의원, 후보 사퇴·제3당 지지, 이후 시기의 경계와 경선 달력은 추정 대상이다. 인도네시아는 등록 유권자로 지역별 투표의 가중치를 정한다. 2024년 후보와 추천 정당은 확정 명부를 사용하고 주·재외 투표의 실제 투표율과 후보별 득표는 모의한다. [미국·인도네시아 선거구 자료](../simulation/src/main/resources/world/electoral_areas.json) |
| 지역구·명부 | 프랑스의 공동후보 선택·결선 표 이전, 호주 경계 변경 지역과 신설 선거구의 지지 이동은 추정한다. 혼합제·비례제에서는 선거구별 정원과 과거 후보·정당의 실표를 구분한다. 개인별 공천·당선 순위, 개표 자료가 없는 지역에서의 정당 득표, 정당 간 선거연합의 향후 합의에는 모형값이 들어간다. 한국은 2024년 실제 비례 명부·후보 정원을 쓴다. 조사에서 구분하지 않은 소수정당의 지지, 시민사회 추천 후보의 향후 소속, 다음 총선의 위성정당 참여는 추정한다. 이탈리아 선거연합의 지역구별 후보 추천 몫과 리투아니아 공동명부의 후보 순번·선호표도 모의한다. 리투아니아의 2024년 첫 회의는 11월 14일이며 이후 회의는 최종 투표 2주 뒤로 잡는다. 지역구 투표가 성립하지 않아 재선거가 필요하면 법정 3개월 안에서 8주 뒤로 잡는다. [선거 규칙과 출처](../simulation/src/main/resources/world/elections.json) |
| 의회 인선·승계 | 중국·베트남·북한·사우디의 인선 회기, 이란 각료별 출석·신임·문책 표수, 미국·인도네시아 대통령·부통령 유고에 따른 인선일과 개별 의원의 투표는 모의한다. 의회 내 의원별 임기를 모두 확보하지 못해 공석·당적 이동의 발생 시점도 추정한다. [헌정 제도](../simulation/src/main/resources/world/constitutions.json) · [공석 보충 규칙](../simulation/src/main/resources/world/parliamentary_mandates.json) |
| 상원·간선 국가원수 | 선출 방식·정원·임기군이 확인된 곳에서도, 개별 상원의원의 소속과 임기 만료일을 모두 알 수 있는 것은 아니다. 시작 시점 정당·교섭단체별 의석 합계를 확보한 8개국 이외에서는 진영별 합계 또는 주정부 대표를 사용한다. 간선 국가원수의 후보별 득표·주의회 정당별 표·불참·교차투표, 미래 임명·해산·선거구 재획정은 모의한다. [상원별 출처와 추정 항목](../simulation/src/main/resources/world/upper_renewals.json) · [헌정 프로필](../simulation/src/main/resources/world/constitutions.json) |
| 불신임 후 정부 구성 | 러시아의 석 달 이내 재표결과 총리 승인, 르완다의 발의·숙려·내각 교체를 포함한다. 의원 개인의 출석·서명·이탈, 연정 합의와 국가원수 재량의 보정에는 표결·조각 기록이 필요하다. [국가별 절차와 법적 근거](../simulation/src/main/resources/world/constitutions.json) |
| 파키스탄 | 2018년 7월 270개 지역구의 후보별 잠정 개표에 10월 2개 지역구 선관위 개표서를 더한 53,420,071표를 사용한다. 2023년 획정된 266개 지역구에도 당시 선거구와 경계가 다른 곳은 전신 지역의 수치를 배분한다. 우편투표·재검표, 관보 공고일과 무소속의 입당 선택, 선거 전 개별 후보의 제휴·직업별 지지율은 남은 자료 차이다. [선거구 대응표](../tools/electiondata/pakistan_constituencies.csv) · [후보별 원자료와 집계](../tools/electiondata/build_pakistan.py) |
| 유권자·조직·인물 | 노조 조직률은 PRK IRN SAU, 종교 중요도는 CHN GBR PRK AUS FIN IRN TWN에서 추정한다. 종교 조사는 2009년, 노조 통계는 연도가 서로 다르다. 정당별 지지층·경선 및 결선 지지 이전, 조직 회원 비율·정부 태도, 지도자별 취임일, 실제 연정 협약·내각 인선 자료가 필요하다. 비예산 규제 다수는 소득군 추정, 쿠데타 이력은 THA PAK EGY TUR만 수록한다. [성향 자료](../simulation/src/main/resources/world/society_identities.json) |
| 은퇴와 직업 | 32개 플레이 국가의 연령별 경제활동참가율은 28개국의 2023년 조사, 3개국의 2023년 ILO 추정, 우크라이나의 2021년 관측을 쓴다. 연금 수급 자료는 65세 이상 전 연령 10개국, 65~74세 한정 6개국에 있다. 50~64세 수급 여부와 비취업을 함께 반영하는 국가는 9개국이다. 고령자의 직업 분포와 지역별 직업 이동은 추정한다. [연령대별 원자료 생성기](../tools/econdata/build_age_activity.py) |

## 정책·사건의 모형 가정

- **재정·기업.** 채권자별 초기 국채 비중, 민간 자본 유출입, IMF 대출 조건, 기업 진입·퇴출은 추정이다. 채권자·통화·만기별 잔액, 국부펀드와 월별 국제수지, 양자·자산별 대외 투자 잔액과 만기·금리 재설정 일정을 확보해야 한다. 개인송금의 가구별 배분과 월별 계절성도 추정 범위 밖이다. 동티모르처럼 석유기금 인출로 충당한 적자는 게임에서 부채로 잡힐 수 있다.
- **국가·지역 정책.** 영국·캐나다·호주의 주택 정책에는 실제 지방별 인허가·착공·준공과 집행액, 브라질 곡물 보조에는 지역·품종별 최저가격·산지가격과 경매 낙찰 물량이 필요하다. 지역 사업 단가, AI 정당의 예산 성향·발의 주기, 국가별 정책 효과는 게임 가정이다. [주택 제도별 출처](../simulation/src/main/resources/world/housing_programs.json) · [Conab 2023년 집행보고서](https://arquivosportal.conab.gov.br/api/public/dl/mCCRPnym/arquivos/auditoria/Relatorio-de-Gestao-2023.pdf) · [국가별 정책 근거와 가정](COUNTRY_MODULES.md)
- **지도·외교·피란.** 단순화한 형상에서 작은 섬·해협·인접 관계가 달라질 수 있다. 외교 관계와 제재 강도, 점령 비율·분쟁 긴장은 추정이며 가자와 서안 C구역을 구분하지 않는다. 운송 충격은 공통 계수를 쓰며 항구별 처리량·환적 자료가 없다. 기존 피란민과 전쟁 피해 자산은 시작 재고가 없어 게임 중 발생분부터 집계한다. [외교 자료](../simulation/src/main/resources/world/diplomacy.json) · [분쟁 자료](../simulation/src/main/resources/world/military.json)
- **사건·장기 추세.** 32개국에 국가별 재난 노출 추정치가 있고 나머지는 공통 위험을 쓴다. 사건 확률·손실과 선택지 효과, 기후·배출·기술 경로의 보정에는 국가별 발생률과 장기 시계열이 필요하다. [사건 자료](../simulation/src/main/resources/world/events.json)

## 부록 · 국가별 결측 조회

거시·재정·사회 및 구조 표는 번들의 `estimated` 필드를 집계한다. 같은 국가 집합은 한 행으로 묶었다. 기관이 공표한 추계나 시계열로 계산한 값에도 통계상 불확실성이 있다. 세입 자료의 정부 범위는 `budget.revenueCompositionSource`, 부분 보고에서 추정한 세목은 `budget.unreportedRevenueTaxes`로 조회한다. 수동 대체와 중앙값 추정은 같은 표시를 쓰며 상한·대체 연도만 적용한 값에는 표시가 없을 수 있다.

<details>
<summary>거시·재정·사회 초기값 · 199개국 중 86개국에 추정 항목</summary>

| 항목과 원본 키 | 국가 수 | 해당 국가 코드 |
|---|---:|---|
| 사회보험료 (`socialContributionShare`) | 50 | AFG, BDI, BEN, BGD, BHR, BLZ, BOL, BRB, BRN, BWA, COD, COG, CUB, CYN, DZA, EGY, ERI, FSM, GMB, GNQ, JAM, KNA, LAO, LBR, LCA, MCO, MDV, MHL, MLI, MRT, NER, NGA, PAK, PLW, PRK, PSX, QAT, SDN, SOL, STP, SYR, TKM, TLS, TUN, TWN, VAT, VEN, VNM, YEM, ZWE |
| 국방비 (`defense`) | 43 | AND, ARE, ATG, BHS, BRB, BTN, COM, CUB, CYN, DJI, DMA, ERI, FSM, GRD, KIR, KNA, LAO, LCA, LIE, MCO, MDV, MHL, NRU, PLW, PRK, PSX, SLB, SMR, SOL, STP, SUR, SYR, TKM, TON, TUV, TWN, UZB, VAT, VCT, VEN, VUT, WSM, YEM |
| 지니계수 (`gini`) | 26 | AFG, ATG, AZE, BHR, BRN, CUB, DMA, ERI, GUY, KNA, KWT, LBY, MCO, OMN, PLW, PNG, PRK, SAU, SMR, SOL, SOM, TKM, TTO, VAT, VCT, VEN |
| 소득·이익세 (`incomeProfitTaxShare`) | 22 | AFG, BLZ, BOL, BRN, CUB, CYN, ERI, HTI, KWT, LBR, LBY, MCO, NER, NGA, OMN, PRK, PSX, SOL, SYR, TWN, VAT, VEN |
| 교육비 (`education`) | 19 | AFG, BRN, CYN, DJI, EGY, ERI, GNB, GNQ, GUY, IRQ, LBY, PRK, SDN, SDS, SOL, SYR, VAT, VEN, YEM |
| 이자 (`interest`) | 15 | AFG, BRN, CUB, CYN, KWT, LIE, MCO, PRK, PSX, SOL, SYR, TKM, TUV, TWN, VAT |
| 소비세 (`consumptionTaxShare`) | 14 | CUB, CYN, ERI, KWT, LBY, MCO, PRK, QAT, SOL, SYR, TWN, VAT, VEN, YEM |
| 무역세 (`tradeTaxShare`) | 13 | CUB, CYN, DJI, ERI, KWT, MCO, PRK, SOL, SYR, TKM, TWN, VAT, VEN |
| 농업 고용 (`agricultureEmployment`) · 산업 고용 (`industryEmployment`) · 서비스 고용 (`servicesEmployment`) | 12 | AND, ATG, DMA, FSM, KNA, MCO, NRU, SMR, SOL, SYC, TUV, VAT |
| 부채 (`debt`) | 9 | CUB, CYN, MCO, PRK, SOL, SYR, VAT, VEN, YEM |
| 자영업 (`selfEmployment`) | 8 | DMA, FSM, KNA, LIE, MCO, SMR, SOL, VAT |
| 지출 (`expense`) · 세입 (`revenue`) | 7 | CUB, CYN, MCO, PRK, SOL, SYR, VAT |
| 보건비 (`health`) | 6 | CYN, KOS, PRK, SOL, TWN, VAT |
| 실업률 (`unemployment`) | 5 | DMA, FSM, MCO, SOL, VAT |
| 물가 (`inflation`) | 4 | ERI, PRK, SOL, VAT |
| 성장률 (`growth`) | 3 | ERI, SYR, VAT |
| 아동 비중 (`childShare`) · 고령 비중 (`elderlyShare`) · GDP (`gdp`) · 성장 변동성 (`growthVolatility`) · 잠재성장 (`potentialGrowth`) · 인구증가율 (`populationGrowth`) | 1 | VAT |

</details>

<details>
<summary>일반정부 세목별 구성을 대체한 112개국</summary>

| 보고 범위 또는 추정 방식 | 국가 수 | 국가 코드 |
|---|---:|---|
| 사회보장기금을 포함한 중앙정부 (`GFS_CENTRAL_INCL_SOCIAL_SECURITY`) | 1 | URY |
| 사회보장기금을 제외한 중앙정부 (`GFS_CENTRAL_EXCL_SOCIAL_SECURITY`) | 2 | PAN, SYC |
| 중앙정부 예산회계 (`GFS_BUDGETARY_CENTRAL`) | 23 | ARG, BHS, COM, ETH, FJI, GHA, GIN, GNB, LSO, MAR, MOZ, MWI, MYS, NPL, PNG, SDS, SLB, TGO, TON, TUV, TZA, VUT, ZMB |
| 정부 범위 미상 · 5개 세목 보고 (`WORLD_UNSPECIFIED`) | 23 | ATG, AUS, CAF, CAN, CMR, CPV, DMA, ECU, GRD, GUY, IND, IRN, LIE, MNE, NIC, PER, SLE, SRB, SUR, SWZ, TCD, TTO, VCT |
| 정부 범위 미상 · 일부 세목 보고 (`WORLD_UNSPECIFIED`) | 40 | AFG, BDI, BEN, BGD, BLZ, BOL, BRB, BRN, COD, DJI, DZA, EGY, FSM, GMB, GNQ, HTI, JAM, KNA, KWT, LAO, LBR, LBY, LCA, MDV, MRT, NER, NGA, OMN, PAK, PLW, PSX, QAT, SDN, STP, TKM, TLS, TUN, VNM, YEM, ZWE |
| WDI·소득군 모형 배분 (`WDI_MODEL`) | 23 | BHR, BTN, BWA, COG, CUB, CYN, ERI, GAB, HND, IRQ, LBN, LKA, MCO, MDG, MHL, MLI, MMR, PRK, SOL, SYR, TWN, VAT, VEN |

</details>

<details>
<summary>플레이 국가의 연금 수급 자료와 관측 연령</summary>

| 자료 범위 | 국가 수 | 국가 코드 |
|---|---:|---|
| 65세 이상 전 연령 · 해당 제도·조사 수급률 | 10 | AUS, BRA, CAN, FIN, FRA, GBR, JPN, KOR, USA, ZAF |
| 65~74세만 관측 · 75세 이상 추정 | 6 | DEU, EST, ITA, LTU, LVA, POL |
| 65세 이상 수급 자료 없음 | 16 | CHN, EGY, IDN, IND, IRN, ISR, PAK, PRK, RUS, RWA, SAU, THA, TUR, TWN, UKR, VNM |
| 50~64세 수급 및 비취업 수급 자료 있음 | 9 | DEU, EST, FIN, FRA, ITA, LTU, LVA, POL, USA |
| 50~64세 수급 및 비취업 수급 자료 없음 | 23 | AUS, BRA, CAN, CHN, EGY, GBR, IDN, IND, IRN, ISR, JPN, KOR, PAK, PRK, RUS, RWA, SAU, THA, TUR, TWN, UKR, VNM, ZAF |

한국은 11개 제도 중 하나 이상 받은 사람, 일본은 공적연금·은급, 캐나다는 OAS·연방 보충급여, 브라질은 공적 퇴직·유족연금 수급자를 집계한다. 핀란드는 거주자 노령·부분노령연금, 프랑스는 거주자 본인 권리 연금(DREES ANCETRE 추계), 미국은 본인의 은퇴 사유 사회보장급여와 직역연금을 포함한다. [국가별 자료와 산식](../tools/econdata/pension_coverage.py)

| 65세 이상 수급률이 범위로만 확인되는 제도 | 2023년 추정 범위 | 범위를 정한 근거 |
|---|---:|---|
| 호주 Services Australia 노령연금 | 54.92~56.75% | 6월 지급 기록 중 해외·주소 미확인자와 지급률 미확정자가 있다. [호주 사회서비스부 원표](https://data.gov.au/data/dataset/cff2ae8a-55e4-47db-a66d-e177fe0ac6a0/resource/7a6457a8-44a3-406c-b552-62eb0fef9d66/download/dss-demographics-june-2023-final.xlsx) |
| 영국 국가연금 | 91.65~92.01% | 5월 양의 지급과 국내 거주를 확인할 수 있는 인원을 6월 말 인구와 비교한다. 북아일랜드는 지급액과 주소를 별도 표로 공개한다. 공표 셀의 통계적 교란·반올림, 인구 기준월 차이로 범위도 근사치다. [영국 노동연금부 지역표](https://stat-xplore.dwp.gov.uk/webapi/jsf/tableView/tableView.xhtml?table=SP+2+-+Region) · [북아일랜드 5월 통계](https://www.communities-ni.gov.uk/publications/benefits-statistics-summary-publication-national-statistics-may-2023) |
| 남아공 SASSA 노령보조금 | 49.37~98.26% | 6월 말 60세 이상 수급자 3,934,291명 가운데 60~64세가 차지할 수 있는 인원은 최대 1,957,488명이다. 이 연령대를 제외한 수급자 범위를 65세 이상 인구 4,003,910명으로 나눈다. [지방재무부 전국 지급표 1.3](https://www.kzntreasury.gov.za/ResourceCenter/Public%20Finance/Estimates%20of%20Provincial%20Revenue%20and%20Expenditure/2024%20-25%20Estimates%20of%20Provincial%20Revenue%20and%20Expenditure/OPRE%2024-25.pdf) · [남아공 통계청 중간 인구 추계](https://www.statssa.gov.za/?PPN=P0302&SCH=73952&page_id=1854) |

EU 노동력조사의 연금 모듈은 74세까지만 조사한다. 일부 수급 유형을 공표하지 않은 경우에도 확인할 수 있는 하한과 상한을 보존한다. 게임은 관측 가능한 연령대에 하한을 적용하고 조사 밖의 75세 이상은 전원을 수요 인구로 둔다. 50~64세에서는 전체 수급 인원과 비취업 수급 인원을 따로 집계한다. [연령별 번들](../simulation/src/main/resources/world/age_activity.json) · [Eurostat 조사 범위](https://ec.europa.eu/eurostat/cache/metadata/en/lfso_23_esms.htm)

</details>

<details>
<summary>개인송금 기준액의 보완 자료와 결측 국가</summary>

WDI 개인송금은 같은 방향의 가계 간 개인이전과 피용자보수를 합산한다. 아래는 2023년 WDI 이외의 자료를 썼거나 결측인 경우다. 2021년 KNOMAD 양자 경로에는 송금국·수취국 양쪽 기준액이 허용하는 금액까지 배정한다. 잔액은 상대 미배정으로 표시하고, 기준액 자체가 없는 방향은 월 합계에서 제외한다. [국가·연도별 번들](../simulation/src/main/resources/world/remittances.json) · [보완 자료와 산식](../tools/econdata/remittance_supplements.py)

| 처리 방식 | 국가 수 | 국가 코드 |
|---|---:|---|
| 수취·지급 각각 · 2023년 국가 국제수지 | 7 | CAF, COG, GAB, GNQ, TCD, TGO, TWN |
| 수취 · 세계은행 연간 추계 | 11 | 2023년 AFG, BRB, BRN, FSM, MMR, NZL, SOM, VNM, VUT, YEM / 2022년 IRN |
| 수취 · 2021년 KNOMAD 국가 합계 | 1 | TKM |
| 수취 · 일부 경로·조사 또는 하한 | 5 | 2023년 ARE 환전소, CUB 유엔무역개발회의 추계, CYN 국제수지 하한, ERI 허가 송금업자 / 2022년 SYR 가구조사 |
| 수취 · 자료 없음 | 8 | BHR, LBY, LIE, MCO, PRK, SGP, SOL, VAT |
| 지급 · 2023년 9월 말 회계연도 국제수지 | 1 | FSM |
| 지급 · 과거 WDI | 6 | 2022년 VUT, 2020년 AFG, 2019년 MMR, 2017년 BRB, 2016년 YEM, 2014년 VNM |
| 지급 · 2021년 KNOMAD 국가 합계 | 11 | BRN, CUB, ERI, IRN, LIE, MCO, PRK, SGP, SOM, SYR, TKM |
| 지급 · 2023년 국제수지 하한 | 1 | CYN |
| 지급 · 자료 없음 | 2 | SOL, VAT |

세계은행의 2023년 수취 추계 중 AFG는 2021년, BRB는 2017년, FSM은 2014년, YEM은 2016년부터 금액이 같다. 북키프로스는 개인이전 순수취만 공표해 수취·지급 각각의 최소 개인이전에 해당 방향의 피용자보수를 더했다. 시리아·쿠바의 조사와 아랍에미리트·에리트레아의 송금업자 통계는 WDI 총액의 일부 범위만 포괄한다. [세계은행 연간 송금표](https://datacatalogfiles.worldbank.org/ddh-published/0066877/DR0095407/Inward_remittance_flows_%28July_13_2025%29.xlsx) · [국가별 원표와 범위](../tools/econdata/remittance_supplements.py)

</details>

<details>
<summary>대외 투자소득의 기준연도·세부 항목과 결측 국가</summary>

IMF BPM6 연간 국제수지의 투자소득 총계로 수취·지급을 각각 집계한다. 2019~2023년 중 가장 최근 총계를 쓰며, 국가·지역 중앙은행이나 통계기관의 2023년 국제수지로 보완한다. 단위는 연간 명목 미 달러다. [IMF 국제수지](https://data.imf.org/en/datasets/IMF.STA:BOP) · [국가별 원표와 환산식](../tools/econdata/investment_income_supplements.py) · [국가·연도별 번들](../simulation/src/main/resources/world/investment_income.json)

| 출처와 연도 | 수취 | 지급 | 국가 코드 또는 범위 |
|---|---:|---:|---|
| IMF 2023년 | 169 | 170 | 국제수지 투자소득 총계를 보고한 국가 |
| 국가·지역 기관 2023년 | 9 | 9 | BRN, CAF, COG, CYN, GAB, GNQ, TCD, TGO, TWN |
| 국가 기관 2023년 9월 말 회계연도 | 0 | 1 | FSM 지급. 수취는 원표의 상·하위 항목이 맞지 않아 총액 결측 |
| IMF 2019~2022년 | 4 | 3 | 양방향 모두 2022년 VUT, 2020년 AFG, 2019년 MMR. SDS는 수취만 2019년 |
| 양방향 모두 총액 결측 | 16 | 16 | ARE, BRB, CUB, ERI, IRN, LIE, MCO, PRK, SGP, SOL, SOM, SYR, TKM, VAT, VNM, YEM |

배당(준법인 인출 포함)은 수취 166개국·지급 174개국, 이자는 176개국·177개국, 재투자수익은 164개국·173개국에서 적어도 한 구성 항목을 식별했다. 같은 방향·연도의 상위 합계와 나머지 항목으로 복원할 수 있는 금액도 포함한다. 기타·미분류는 총액에서 식별된 세 항목을 뺀 잔액이므로 보고되지 않은 배당·이자·재투자 손익도 섞일 수 있다. 손실에 따른 음수와 공표된 0을 보존한다. [세부 항목과 계산식](../tools/econdata/build_investment_income.py)

본원소득에 섞인 피용자보수·자원 임대료와 금융중개서비스 간접측정분(FISIM) 차감 전 이자 메모 항목은 투자소득 총계에 더하지 않는다. IMF 직접투자 채무소득 중 순수 이자가 분리되지 않는 금액은 미분류에 남긴다. 식별한 재투자수익에는 금융계정 대응액을 반영해 현금 결제에 중복 계상하지 않는다. [국제수지 월 결제](../simulation/src/main/kotlin/team/themoment/axia/simulation/economy/ExternalAccount.kt)

</details>

<details>
<summary>곡물 수요·금 제외 준비자산·시작 기준금리의 대체 범위</summary>

| 처리 방식 | 국가 수 | 국가 코드 |
|---|---:|---|
| 곡물 기본 수요를 인구로 추정 | 20 | AND, BDI, BRN, CAF, CUB, CYN, ERI, GNQ, KOS, LIE, MCO, PLW, PRK, PSX, SDS, SGP, SMR, SOL, SOM, VAT |
| 준비자산을 수입 3개월분으로 추정 | 25 | AFG, BEN, CUB, CYN, ERI, FSM, IRN, LIE, MCO, MHL, MLI, MRT, NRU, PLW, PRK, SDN, SEN, SOL, SOM, SYR, TGO, TKM, VAT, VEN, YEM |
| 준비자산을 WDI 연간 값으로 대체 | 1 | MMR |
| 기준금리에 고정환율 기준 통화 금리를 적용 | 15 | AND, BGR, BIH, ECU, FSM, KOS, MCO, MHL, MNE, PAN, PLW, SLV, SMR, TLS, VAT |
| 기준금리에 국가별 지정 추정치 또는 물가 기반 추정식을 적용 | 62 | AFG, AGO, BLR, BLZ, BOL, BRB, BRN, BTN, COM, CPV, CUB, CYN, DJI, DZA, ERI, ETH, GIN, GUY, HND, HTI, IRN, IRQ, KHM, KIR, LAO, LBN, LBR, LBY, LIE, LSO, MDG, MDV, MMR, MRT, MWI, NAM, NIC, NRU, PRK, PSX, SDN, SDS, SGP, SLB, SOL, SOM, STP, SUR, SWZ, SYC, SYR, TJK, TKM, TON, TUV, TZA, UGA, VEN, VUT, WSM, YEM, ZWE |

</details>

<details>
<summary>구조 지표 · 199개국 중 82개국에 추정 항목</summary>

| 항목과 원본 키 | 국가 수 | 해당 국가 코드 |
|---|---:|---|
| 섬유 제조 (`textileManufacturing`) | 74 | AFG, AND, ATG, BDI, BEN, BFA, BHR, BHS, BLZ, BRB, CAF, COG, COM, CUB, CYN, DJI, DMA, DOM, DZA, ETH, FSM, GAB, GHA, GIN, GMB, GNB, GNQ, GRD, GTM, GUY, HND, HTI, JAM, KIR, KNA, LBR, LBY, LCA, LIE, MCO, MHL, MOZ, MRT, NER, NRU, PLW, PNG, PRK, SDN, SDS, SLB, SLE, SLV, SMR, SOL, SOM, SUR, SWZ, SYC, SYR, TCD, TKM, TLS, TON, TTO, TUV, UGA, VAT, VCT, VEN, VUT, WSM, YEM, ZWE |
| 식품 제조 (`foodManufacturing`) | 71 | AFG, AND, ATG, BDI, BEN, BFA, BHR, BHS, BLZ, BRB, CAF, COM, CUB, CYN, DMA, DOM, DZA, ERI, ETH, FSM, GAB, GHA, GIN, GMB, GNQ, GRD, GTM, GUY, HND, HTI, JAM, KIR, KNA, LBR, LBY, LCA, LIE, MCO, MOZ, MRT, NER, NRU, PLW, PNG, PRK, SDN, SDS, SLB, SLE, SLV, SMR, SOL, SOM, SUR, SWZ, SYC, SYR, TCD, TKM, TLS, TON, TTO, TUV, UGA, VAT, VCT, VEN, VUT, WSM, YEM, ZWE |
| 기술 제조 (`techManufacturing`) | 45 | AND, ATG, BEN, BFA, BTN, COD, COM, CYN, DJI, DMA, DOM, FSM, GIN, GNB, GNQ, GRD, GUY, KIR, KNA, LBR, LIE, LSO, MCO, MHL, MRT, NRU, PLW, PRK, SDN, SDS, SLB, SLE, SMR, SOL, SOM, STP, SYC, TCD, TLS, TUV, TWN, VAT, VCT, VUT, WSM |
| 병력 (`armedForces`) | 30 | AND, BTN, COM, CYN, DMA, FSM, GRD, KIR, KNA, KOS, LCA, LIE, MCO, MDV, MHL, NRU, PLW, PRK, SLB, SMR, SOL, STP, SWZ, TON, TUV, TWN, VAT, VCT, VUT, WSM |
| 석유 지대 (`oilRents`) | 15 | AND, CYN, FSM, LIE, MCO, MHL, PLW, SDS, SMR, SOL, TUV, TWN, VAT, VEN, YEM |
| 자원 지대 (`resourceRents`) | 9 | CYN, ERI, PRK, SDS, SOL, TWN, VAT, VEN, YEM |
| 농업 부가가치 (`agricultureValueAdded`) | 4 | MCO, SOL, SOM, VAT |
| 순이동 (`netMigration`) | 3 | CYN, SOL, VAT |
| 제조업 부가가치 (`manufacturingValueAdded`) · 산업 부가가치 (`industryValueAdded`) · 서비스 부가가치 (`servicesValueAdded`) | 3 | SOL, SOM, VAT |
| 출산율 (`fertility`) · 출생률 (`birthRate`) · 사망률 (`deathRate`) · 기대수명 (`lifeExpectancy`) | 2 | SOL, VAT |
| 도시 비중 (`urbanShare`) | 2 | CYN, SOL |
| 수출 비중 (`exportShare`) · 교역 개방도 (`tradeOpenness`) | 1 | VAT |

</details>

<details>
<summary>체제·의석·무역 자료의 대체 범위</summary>

| 자료 | 국가 수 | 해당 국가 코드 |
|---|---:|---|
| 세계 체제 분류 미수록 | 25 | AND, ATG, BHS, BLZ, BRN, CYN, DMA, FSM, GRD, KIR, KNA, LCA, LIE, MCO, MHL, NRU, PLW, PSX, SMR, SOL, TON, TUV, VAT, VCT, WSM |
| 시작 의석의 출처가 이전 총선 | 22 | AUS, CAN, DEU, EGY, EST, FIN, IDN, IND, IRN, ISR, ITA, JPN, LTU, LVA, POL, PRK, RUS, RWA, THA, TUR, VNM, ZAF |
| 교역 상대의 수입 신고로 역산 | 40 | AFG, BGD, BLR, DEU, DZA, ERI, FSM, GBR, GIN, GNB, GNQ, HTI, KIR, KNA, LBY, LCA, MHL, NRU, NZL, PAK, PLW, PRK, RUS, SDN, SDS, SLB, SLE, SMR, SOM, SYR, TCD, TKM, TUV, TWN, USA, VAT, VEN, VNM, VUT, YEM |

</details>
