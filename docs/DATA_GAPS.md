# 데이터 결측과 구현 범위

게임 시작일은 2024년 1월 1일이다. 아래 수치는 저장소에 수록된 **199개 경제와 32개 플레이 국가**를 기준으로 집계했다. 같은 나라가 여러 지표에서 반복될 수 있다. 국가 코드는 지도에서 쓰는 Natural Earth 코드이며, 코소보는 KOS, 팔레스타인은 PSX, 남수단은 SDS로 표기한다. 모델 수식과 출처 설명은 [설계 문서 14장](DESIGN.md#14-데이터-출처)에서 확인할 수 있다.

## 2024년 시작값의 자료 보완

| 항목 | 기존 자료에서 달라진 범위 | 사용한 원자료 |
|---|---|---|
| 금 제외 준비자산 | 전 국가 수입 대금 3개월분에서 173개국의 2023년 말 보고액으로 교체했다. 164개국은 12월 관측이고 9개국은 4분기 말·연말 국제투자대조표에서 금을 빼거나 금 외 항목을 합산했다. 미얀마 1개국은 월을 확인할 수 없는 연간 대용치, 25개국은 기존 추정이다. | [IMF 국제유동성](https://data.imf.org/en/Datasets/IL) · [IMF 국제투자대조표](https://data.imf.org/en/Datasets/IIP) · [세계은행 WDI](https://data.worldbank.org/indicator/FI.RES.XGLD.CD) |
| 곡물 수요 | 인구만 사용하던 199개국 중 172개국의 수요 가중치를 FAO 국내 공급 추계로 교체했다. 미국의 세계 기준 수요 비중은 4.24%에서 12.14%로 바뀐다. 플레이 가능 국가 32개국 중 30개국을 포함한다. 일본과 북한 등 미수록국은 수록국의 1인당 공급량으로 추정한다. | [FAOSTAT 식량수급표](https://www.fao.org/faostat/en/#data/FBS) |
| 지역 인구 | 1,497개 행정구역 중 8개국 305곳에 공식 인구 비중을 적용한다. 한국 17, 미국 51, 캐나다 13, 독일 16, 폴란드 16, 리투아니아 10, 튀르키예 81, 프랑스 101곳이다. 한국은 외국인을 제외한 2023년 말 주민등록인구, 미국은 2023년 7월 추계, 캐나다·유럽 통계는 2024년 1월 1일 인구다. 미국 원표 밖 23곳과 프랑스 원표 밖 20곳은 추정 지분을 유지한다. | [한국 시도별 통계](https://www.jeonnam.go.kr/contentsView.do?menuId=welfare0504000000) · [미국 Census](https://www2.census.gov/programs-surveys/popest/datasets/2020-2023/state/totals/NST-EST2023-ALLDATA.csv) · [캐나다 통계청](https://doi.org/10.25318/1710000901-eng) · [Eurostat](https://ec.europa.eu/eurostat/databrowser/view/demo_r_pjangrp3/default/table?lang=en) |
| 세입 구성 | 136개국에서 개인소득세·법인세·상품서비스세·무역세·사회보험료가 총세입에서 차지하는 비중을 동일한 2023년 자료로 계산했다. 87개국은 명시적 일반정부 자료다. 26개국은 GFS 중앙정부 자료, 23개국은 정부 범위를 확인할 수 없는 WoRLD 자료로 일반정부 구성을 추정한다. 나머지 63개국은 기존 WDI·모형 배분을 사용한다. | [IMF GFS 정부운영표](https://data.imf.org/en/Datasets/GFS_SOO) · [IMF WoRLD](https://www.imf.org/en/topics/fiscal-policies/world-revenue-longitudinal-database) |
| 기준금리 | 국가·통화동맹 기준 관측 금리가 43개국에서 63개국으로 늘었다. 아르헨티나는 100%, 덴마크는 3.6%로 시작한다. 중국의 역레포와 유로권의 예금금리 등 게임에서 선택한 정책수단은 각각의 기존 관측값으로 시작한다. | [BIS 월말 정책금리](https://data.bis.org/topics/CBPOL) |

게임은 2024년 1월 1일에 시작한다. 표는 각 자료의 대상 기간을 기준으로 하며, 이후 발간된 통계에서 2023년을 소급한 경우도 있다. 번들에는 결측과 수치 0을 별도로 기록한다.

## 게임 기능별 데이터 범위

| 기능 | 게임에 반영한 것 | 남은 자료와 추정 범위 |
|---|---|---|
| 브라질 Conab 가격차 보조 | 추가 경매로 농가가 받은 가격차를 재정과 소득에 한 번씩 계상한다. 지급 대상은 그 달 브라질의 게임 내 수확량 중 경매가 지원하는 물량이다. | 2023년 PEPRO 밀 경매는 제도 근거다. 지역·품종별 최저가격, 월별 산지가격과 입찰·낙찰 자료는 전국 곡물 지수와 지원 대상 비중으로 대체했다. 기준지수 105, 톤당 환산 250 실질 달러(지수 100), 생산의 최대 4%, 연 GDP의 최대 0.005%는 게임 계수다. |
| 곡물 수확 계절성 | 10개국의 작물 달력을 국가별 월 수확 비중으로 합산했다. 2023년 게임 내 관측 생산의 **66.26%**다. 2월 1일에 1월 수확을 정산하며, 평년 수확기에 비축하고 비수기에 방출한다. | 국가별 달력은 수확 창을 알려 준다. 월별 톤수·지역별 작부 면적, 농가·유통 창고의 월말 재고는 추정이다. 연간 생산이 수록된 나머지 169개국은 월 균등 배분이고, 별도 생산 통계가 없는 20개국은 수입과 재고로 소비를 충당한다. |
| 고령화와 은퇴층 | 플레이어 국가의 월별 65세 이상 인구를 은퇴층으로 보고, 15세 이상 인구에서 차지하는 비중을 직업 구성·여론·지역·조직에 반영한다. | 나라별 실제 퇴직 연령, 65세 이상 취업자, 조기 은퇴자, 연금 가입자·수급자 수가 필요하다. 일곱 직업의 산업별 이동률과 세대별 성향 변화에는 모형 계수를 쓴다. 비플레이 국가에는 이 세분화를 적용하지 않는다. |
| 파키스탄 과도 내각과 첫 총선 | 2024-02-08에 지역구 266석과 유보석 70석을 배분하고 국민의회와 새 정부를 구성한다. | 2018년 선거 당일 투표한 270개 지역구의 잠정 득표 보존본을 사용한다. 무소속과 지역구 당선 또는 전국 득표 1% 이상 정당·연합을 포함한 14개 항목 51,687,498표가 기준이다. 제외된 71개 정당의 1,436,235표, 연기된 2개 지역구와 이후 재검표는 반영하지 않았다. |
| 지역 정책·선거·입법 | 지역별 고용·서비스 사업, 모형 선거구 개표, 야당 법안과 AI 재정안의 심의·집행을 연결했다. | 실제 선거구 경계, 후보·선거구별 득표, 지역별 정책 집행액은 없다. 혼합제는 국가별 지역구 정원을 쓰고 나머지 의석의 선출 방식을 전국 배분으로 단순화한다. 국내 사업의 단가, AI 정당의 예산 성향과 야당의 발의 주기는 게임 가정이다. |
| 전후 처리 | 협정으로 행정구역 관할을 옮기고, 전쟁 이후의 피란과 귀환을 출신국·수용국 양쪽에서 계산한다. 재건 정책은 실제 기초지출을 바꾼다. | 행정구역 이관에는 지역 인구 지분과 산업 구성을 쓴다. 공식 인구가 없는 구역의 지분과 지역별 생산은 모형으로 배분한다. 시작 시점에 이미 피란한 인구와 전쟁 피해 재고는 관측 자료가 없어 후속 발생분부터 누적한다. |
| 외환·기업 | 양자 결제, 경상수지, 외환보유액, 페그 방어와 IMF 원리금 상환을 연결한다. 생성 기업의 부가가치는 기존 산업 총액에서 배분한다. | 25개국의 초기 준비자산은 기준 수입액 3개월분이다. 미얀마는 연간 대용치를 사용한다. 민간 자본 유출입·대출 조건과 기업 이름·진입·폐업은 모형이다. IMF는 2024년 1월 회원으로 시작하며, 가입·탈퇴와 기존 대출 상환을 따로 추적한다. |

브라질 정책의 제도와 실제 집행 범위는 [Conab PEPRO 안내](https://www.gov.br/conab/pt-br/atuacao/comercializacao/leiloes-publicos/compra-publica/premio-equalizador-pago-ao-produtor-pepro)와 [2023년 집행보고서](https://arquivosportal.conab.gov.br/api/public/dl/mCCRPnym/arquivos/auditoria/Relatorio-de-Gestao-2023.pdf)에 있다. 국가별 수확 출처와 12개월 비중은 [수확 달력](../simulation/src/main/resources/world/grain_harvest.json)에 있다.

파키스탄의 여성 유보석 60석은 헌법상 주별로, 비무슬림 유보석 10석은 전국 단위로 배분한다. 게임은 두 몫을 전국 단위로 합산한다. PTI와 MMA 계열은 2018년 당시 소속으로 묶었고, 2024년 후보별 소속 변경과 무소속 당선자의 입당은 모델에서 생략했다. 파키스탄 정당별 지지층의 직업 분포는 수록하지 않아 특정 직업 집단을 배정하지 않았다. 개원과 총리 선출은 게임에서 선거 당일에 처리한다. [당시 헌법 제51·52·91조](https://na.gov.pk/uploads/documents/63ea176f52421_610.pdf) · [2018년 선거관리위원회 후보별 득표 보존본](https://github.com/colincookman/pakistan_election_results_2018/blob/81761f218106a8b054e4b70651d002f4050a1696/data/pk_candidate_data_consolidated_090418.csv)

## 거시경제·재정·산업

| 대상 | 현재 자료와 게임의 처리 | 보완하려면 필요한 자료 |
|---|---|---|
| 경제 초기값 | 199개국 중 91개국에 한 항목 이상 중앙값·수동 대체 등의 추정 표시가 있다. 세입 자료의 정부범위 차이는 별도 출처 필드에 기록한다. IMF·세계은행 자료에 따라 적용 연도가 다르며, 비어 있는 값은 소득 그룹 또는 전체 중앙값, 일부 국가는 수동값을 사용한다. | 해당 연도 국가통계·예결산과 항목별 원자료 연도 |
| GDP와 인구 | 번들에서 GDP가 추정으로 표시된 나라는 CYN·PRK·SOL·VAT다. 인구 규모·증가율은 CYN·SOL·VAT에서 추정이다. 생성기에 ERI의 GDP 수동 대체값도 있으나 현재 번들에 ERI GDP 추정 표시는 없다. | 통계 범위가 일치하는 국민계정과 인구 |
| 세입·기초지출 | 소득군 등으로 보완한 사회보험료 비중은 110→55개국, 무역세 비중은 79→39개국이다. IMF 구성을 쓰는 국가는 개인·법인세를 분리하며, 미분류 소득세는 기타에 둔다. IMF의 사회보험료는 사회보장기여금(G121)이다. 그 밖의 사회기여금(G122)은 기타에 포함된다. WDI의 사회기여금은 범위가 더 넓다. 중앙정부 등 대용치를 쓴 49개국은 이 결측 집계와 별도다. 낮은 부채에서 실효이율 역산이 불안정한 경우도 추정에 포함된다. | 세목별 결산액, 이자비용·차입 조건 |
| 초기 산업·인구 구조 | 199개국 중 95개국에서 구조 지표 한 항목 이상을 추정했다. 섬유·식품·기술 제조 비중, 고용 분류별 자료에 공백이 많다. 병력·순이동 결측에는 0을 저장하며 병력은 국방비로 추정할 수 있다. 대만의 다수 구조값은 별도 수동 근사치다. | 제조업 세분류와 고용, 군 인력·이민 통계 |
| 모형으로 나눈 산업 | 건설과 광업, 금융·공공·기타 서비스, 산업별 노동집약도, 직업별 소득·조세 귀착은 모형 계수다. | 산업연관표와 직업별 고용·소득 미시자료 |
| 통화정책 | 199개국 중 63개국은 국가별 또는 통화동맹의 관측 금리로 시작한다. 첫 43개국에 BIS 2023년 12월 말 20개국을 더했다. 나머지 136개국은 추정치 또는 기준 통화 금리를 사용한다. 체제 자료가 없는 119개국 중 19개국은 금리만 확보했다. | 미수록국의 같은 날짜 정책금리·물가 목표, 실제 환율제도와 정책 반응 |
| 국가채무 | 국내·해외 민간·공적 채권자별로 일반 국채와 교환채의 원리금을 정산한다. 초기 채권자 비중은 소득군 추정이다. 위기 조건·교환 조건·만기는 게임 가정이다. 동티모르의 석유기금 인출 등 금융자산 사용을 별도 계정으로 처리하지 않아 모형 부채가 과대해질 수 있다. | 채권자·통화·만기별 잔액, 국부펀드, IMF 프로그램 현금 흐름 |
| 주택 정책 | 잉글랜드 부지계획, 캐나다 HAF, 호주 HAFF에는 제도 출처가 있다. 월별 착공·준공 지연, 지원금 증감, 전국 건설·가격 효과와 실제 사업 기간 이후의 집행은 게임 가정이다. | 지방별 인허가·착공·준공, 실집행액, 주택 유형별 가격 |

실제 번들 목록은 문서 끝의 지표별 표를 따른다. 원자료 작업 경로는 [경제 초기값 생성기](../tools/econdata/build_economy.py), [구조 자료 생성기](../tools/econdata/build_structure.py), [중앙은행 목록](../simulation/src/main/resources/world/central_banks.json)에 있다.

## 상품·교역

| 대상 | 누락 범위와 현재 처리 |
|---|---|
| 곡물 생산 | 179/199개국에서 연간 수확량을 확보했다. 미보고 20개국은 AND BHR CYN GNQ KIR KNA LCA LIE MCO MHL NRU PLW SGP SMR SOL SYC TON TUV VAT WSM이다. |
| 원유 생산 | 187/199개국에서 원유·리스 콘덴세이트 생산량을 확보했다. 미보고 12개국은 AND CYN FSM KOS LIE MCO MHL PLW SMR SOL TUV VAT다. 관측값 0과 미보고(null)를 구분하며, 미보고국에 별도 생산량을 배정하지 않는다. |
| 원유 수요 | EIA 석유·기타 액체연료 소비를 원유 수요의 대용치로 쓴다. AND CYN LIE MCO MHL PLW SMR SOL TUV VAT는 소비 관측이 없어 GDP로 가중치를 추정한다. 정유공정별 투입량은 없다. |
| 곡물 소비·재고 | 172/199개국의 수요 가중치는 FAO 식량수급표의 2023년 국내 곡물 공급량(품목 2905, 요소 5301)이다. 모두 FAO 추계이며, 미수록 27개국은 수록국의 인구 가중 1인당 공급량으로 채운다. 쌀 제품 등가와 생산 통계의 중량 기준이 달라 세계 연간 수요를 게임 생산 총량에 맞춘다. 용도별 소비와 실제 개시 재고는 수록하지 않았다. 비축 개월 수, 저장 한도, 가격 탄력성은 게임 계수다. |
| 양자 경로 | 전체 상품 수출 기준으로 직접 보고 2023년 152개국, 2022년 2개국(CUB·NPL), 상대국이 신고한 2023년 수입으로 역산한 나라가 40개국이다. CYN KOS LIE MCO SOL은 상대 자료가 없다. 미보고 경로·작은 상대의 잔여 비중을 거리·경제 규모로 채운다. |
| 물량·가격 | 게임에서 한 건의 선적을 양국 장부에 기록한다. 기준 통계는 전체 상품 수출 비중이라 품목별 양자 실측 톤·배럴, 운송 계약·운임, 기준 월 현물가격이 필요하다. 서비스 교역은 별도 자료가 필요하다. |

국가별 원본의 null과 추정 소비국 목록은 [상품 데이터](../simulation/src/main/resources/world/commodities.json)에, 신고·거울통계 구분은 [교역 데이터](../simulation/src/main/resources/world/trade.json)에 있다.

## 선거·제도·사회

| 대상 | 누락 범위와 현재 처리 |
|---|---|
| 정부 지지율 | 32개국 중 CHN PRK IRN SAU EGY VNM RWA는 공개 조사 부재·신뢰성 문제로, LVA PAK는 신정부·과도 내각의 대략값으로 표시한다. 다른 조사도 조사기관과 질문이 다르다. 비플레이 167개국에는 같은 상세 여론·정당·민심 지도 자료가 없다. |
| 의석 기준일 | 22개국은 시작일 현원 대신 2018~2023년 총선 의석표를 출처로 쓴다. FRA·UKR은 2023년 12월 본회의 자료, TWN은 2023년 4월 현원표다. 당적 이동·보궐·공석은 출처 기준일 이후 달라질 수 있다. |
| 선거 공백 | 이란·이집트 무당적 대통령과 해당 국가의 결선, 한국·미국의 당내 경선이 동작한다. 후보별 실제 개표, 주별 경선 일정, 개표구·선거구 경계와 인도네시아의 지역별 득표 요건은 별도 자료와 규칙 보정이 필요하다. |
| 체제 분류 | V-Dem 세계 체제 파일은 174/199개국을 포함한다. 누락 국가는 부록에 있다. 상세 정치 자료가 없는 국가의 양국 체제 비교에서는 체제를 미상으로 둔다. |
| 성향·조직 | 노조 조직률은 PRK IRN SAU, 종교 중요도는 CHN GBR PRK AUS FIN IRN TWN에서 추정이다. 종교 중요도 원자료는 2009년이며 노조 자료는 국가마다 조사 연도가 다르다. 조직 실명에 비해 회원 비율·조직력·정부 태도는 게임 추정이다. |
| 제도·인물 | 사법 견제와 시민 자유 등에는 지표가 있지만, 다른 비예산 규제 초기값은 소득 그룹 추정이다. 쿠데타 이력 표시는 THA PAK EGY TUR에 한정된다. 지도자의 실제 취임일·정당 경선, 상원 정당별 자율투표 자료가 없다. 이름 생성기는 문화권별 표본을 쓴다. |

의석과 선거 범위는 각국의 출처 주석에 근거한다. [의석 자료](../simulation/src/main/resources/world/parliaments.json) · [선거 자료](../simulation/src/main/resources/world/elections.json) · [시작 지지율](../simulation/src/main/resources/world/approval.json) · [성향 자료](../simulation/src/main/resources/world/society_identities.json)

## 지도·군사·외교와 사건

- **지역 통계.** 플레이 가능 국가의 지도 구역 자료 1,497건 중 567건은 Natural Earth 도시 인구 합계가 0이다. 이는 해당 도시 표본이 배정되지 않았다는 뜻일 수 있다. 광역권 인구가 겹치거나 작은 도서에서 면적이 반올림될 수도 있다. 305개 구역의 인구 비중은 공식 통계를 사용하고, 나머지 1,192개 구역은 도시·면적으로 추정한다. 기존 도시 표본이 0인 곳 중 61개 구역은 공식 인구 비중을 확보했다. 세계 지도에서 주권국에 귀속된 해외 구역은 미국의 50개 주·D.C. 등 원통계 모집단과 구분한다. 도시화·소득·지지·실업과 산업 생산·고용에는 모형 배분이 남아 있다. 지역 고용 조사, 광산·산업단지 위치, 통근권 및 연령 구조가 필요하다. [지역 자료](../simulation/src/main/resources/world/regions.json) · [지역 자료 생성기](../tools/geodata/build_regions.py)
- **지도 해상도.** 국경·해안·면적과 인접 관계는 간소화된 형상에 근거한다. 작은 섬·해협과 확대 상한을 넘는 상세 형상, 지역별 치안 자료는 후속 범위다. [지도 설계](DESIGN.md#4-지도)
- **국제 분쟁·외교.** 시작 시나리오는 국제기구 18곳과 선별한 동맹·제재·관계, 전쟁 2건·내전 11건·영토 분쟁 16곳을 수록한다. 관계·제재 강도, 점령 비율·전선·피로도, 핵전력 추정과 분쟁 긴장에는 불확실성이 있다. 특히 이스라엘·하마스 전쟁에서 가자와 서안 C구역이 분리되지 않는다. 해상 봉쇄·교역로 차단은 양자 선적과 결제에 적용된다. 국경·해안 분류와 공통 수송계수로 운송 충격을 추정하며 항구별 통과 능력·복합운송 자료는 없다. [외교 자료](../simulation/src/main/resources/world/diplomacy.json) · [군사 자료](../simulation/src/main/resources/world/military.json)
- **사건·장기 추세.** 국가별 재난 노출은 32개국에 기재돼 있고 나머지는 기본 위험을 쓴다. 발생 확률·선택지 효과와 기후·배출·기술의 장기 경로는 시나리오 계수다. 실제 국가별 재난 발생률과 손실 시계열로 보정할 여지가 있다. [사건 자료](../simulation/src/main/resources/world/events.json)

## 기본 시스템 구현 이후 남은 자료와 보정

- **국가 특화.** 32/32개국에 전용 모듈이 있다. 추가 15개국의 실제 제도 근거와 게임에서 정한 집행 기간·예산·효과는 [국가별 정책 모듈](COUNTRY_MODULES.md)에 정리했다.
- **정치와 인구.** 양원 절충, 연정 교섭, AI 신당, 세대교체와 직업 이동은 동작한다. 성향 좌표, 정당별 실제 지지층, 경선·결선 지지 이전, 지역별 직업 전환·범죄율의 보정 자료가 필요하다.
- **경제와 사회.** 총재는 생성 인물이며 임기·정책 성향·압력에 대한 반응은 모형이다. 채권자별 보유액·감면 조건, 월별 국제수지·난민 재고·피해 자산·기업 장부도 추정에 의존한다. 고물가국은 추정 기준금리 상한 때문에 실질금리가 장기간 낮을 수 있다.
- **게임 전체.** 실제 세계와 채무 경감 가상역사를 지원한다. 기본 배속과 자동 일시정지 설정 보정이 남아 있다. 30년 이상 실행해 수치가 유한하다는 검증과 실제 국가별 정책·선거·사건 빈도를 맞추는 보정은 다른 작업이다. [설계의 미정 사항과 한계](DESIGN.md#17-미정-사항)

## 부록 · 국가별 결측 조회

아래 표는 해당 JSON에서 `estimated`로 표시한 필드를 전부 집계한다. 국가 집합이 같은 필드는 한 행에 모았다. 개별 필드의 국가는 행에 적힌 코드를 그대로 검색하면 된다. 세입 구성의 일반정부·중앙정부·보고범위 미상 구분은 `budget.revenueCompositionSource`에 따로 기록한다. 수동 추정과 중앙값 보완이 같은 플래그를 쓰며, 수식·상한·대체 연도만 적용한 값에는 플래그가 없을 수 있다.

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
