# 2024년 국가별 정책 모듈

플레이 가능 국가의 고유 제도와 효과를 국가 선택 화면의 순서로 정리했다. 정책 탭에서 변경안을 발의하고 개요 화면의 국가 특수 상황에서 집행 지표를 확인할 수 있다.

| 국가 | 제도와 결정 | 게임에서 나타나는 효과 | 참고 자료 |
|---|---|---|---|
| 대한민국 | 대북 정책 기조 | 남북 관계와 도발이 긴장도를 바꾸고, 긴장이 높으면 서해 충돌이 발생할 수 있다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/kor/KoreaModule.kt) |
| 미국 | 상원 필리버스터·예산조정, 연방 부채한도 유예 | 게임에서 최초 유예는 2025년 1월에 끝난다. 한도 초과 뒤 특별 조치가 6개월을 넘기면 채무불이행 위기가 발생한다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/usa/UnitedStatesModule.kt) |
| 중국 | 부동산 부양과 대만 압박 수위 | 부동산 부채 조정은 건설·금융 경기와 부채 위험에, 대만 정책은 해협 긴장과 주변국 관계에 영향을 준다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/chn/ChinaModule.kt) |
| 일본 | 헌법 9조 개정 | 양원 3분의 2와 국민투표를 거친다. 가결되면 국내 여론과 한국·중국·북한과의 관계가 달라진다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/jpn/JapanModule.kt) |
| 독일 | 부채 브레이크 비상 예외 | 적자를 늘리는 법안은 예상 재정수지가 GDP의 −0.35% 아래로 내려가면 막힌다. 비상 예외의 게임 내 효력은 1년이다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/deu/GermanyModule.kt) |
| 영국 | 잉글랜드 지방계획의 주택 부지 규모 | 지방 당국의 심사와 인허가를 거쳐 민간 착공과 준공 물량이 바뀐다. | [제도 자료](https://assets.publishing.service.gov.uk/media/66a8d6fece1fd0da7b592f87/NPPF_December_2023.pdf) |
| 프랑스 | 헌법 49조 3항 적용 | 국민의회 부결이 예상되는 법안을 표결 없이 채택할 수 있다. 게임에서는 연 1회이며 다음 달 만족도가 하락한다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/fra/FranceModule.kt) |
| 러시아 | 전시 경제 수위 | 군수 수요와 노동력 부족이 경기와 물가에 영향을 준다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/rus/RussiaModule.kt) |
| 우크라이나 | 서방 지원국과의 관계 | 주요 지원국과의 관계가 악화되면 재정과 경기에 부담이 생긴다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/ukr/UkraineModule.kt) |
| 인도 | 힌두 민족주의 정책 수위 | 종교 공동체 간 충돌 가능성과 국내 여론, 파키스탄·방글라데시와의 관계가 변한다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/ind/IndiaModule.kt) |
| 브라질 | Conab 곡물 가격차 보조 경매 | 지원 대상 물량과 가격차에 따라 농가 소득과 기초지출이 같은 달에 늘어난다. | [Conab 2023년 집행보고서](https://arquivosportal.conab.gov.br/api/public/dl/mCCRPnym/arquivos/auditoria/Relatorio-de-Gestao-2023.pdf) |
| 캐나다 | 주택공급촉진기금(HAF) 지방정부 협약 | 용도지역·인허가 개혁에 대한 보조금이 재정 지출과 협약 지역 착공을 바꾼다. | [제도 자료](https://budget.canada.ca/fes-eea/2023/report-rapport/chap1-en.html) |
| 북한 | 핵·미사일 개발 수위 | 정책 기조에 따라 시험 발사 빈도와 민수 전환의 경제 효과가 달라진다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/prk/NorthKoreaModule.kt) |
| 호주 | 주택미래기금(HAFF) 임대주택 지원 | 약정한 지원이 사회주택·저렴한 임대주택 착공으로 이어지고 준공 뒤 가격에 반영된다. | [제도 자료](https://www.housingaustralia.gov.au/sites/default/files/2023-12/HAFFF%20and%20NHAF%20Program%20Guidelines%20Dec%202023%20-%20FINAL.pdf) |
| 폴란드 | 가족 800+의 자녀당 월 지급액 조정 | 명목 급여의 구매력이 물가에 따라 달라지고 부모의 만족도와 서비스 소비가 시차를 두고 반응한다. | [폴란드 가족·노동·사회정책부](https://www.gov.pl/web/family/family-800) |
| 이탈리아 | 이탈리아 국가회복·복원계획의 사업 집행 속도 | 먼저 공사비를 부담한다. 2026년 8월까지 검수를 통과한 물량에서 EU 보조금 몫을 회수한다. | [유럽연합 집행위원회 국가별 계획](https://reforms-investments.ec.europa.eu/recovery-and-resilience-facility-1/country-pages/italys-recovery-and-resilience-plan_en) |
| 핀란드 | 주거 우선 사업에 배정하는 주택과 정착 지원 인력 | 확보한 주택과 인력으로 대기 가구를 입주시킨다. 실업과 퇴거율이 대기열과 실제 입주 가구 수를 바꾼다. | [사업 수행 기관 Y-Foundation](https://ysaatio.fi/en/housing-first/) |
| 에스토니아 | 전자영주권 심사 인력 배정 | 인력을 늘리면 신청 대기가 줄고, 승인된 신청자 일부가 법인을 설립해 금융·서비스 수요를 만든다. | [에스토니아 전자영주권 사업단의 2023~2024년 등록·창업 통계](https://www.e-resident.gov.ee/blog/posts/e-residency-applications-and-company-incorporation-increased-7-in-first-half-of-2024/) |
| 라트비아 | ALTUM 공동주택 에너지 개보수 승인 | 착공과 보조금 지급 사이에 공기가 있다. 준공된 주택의 난방비 절감 효과는 계절과 연료가격을 따른다. | [ALTUM 2022~2026년 사업](https://www.altum.lv/pakalpojumi/iedzivotajiem/daudzdzivoklu-maju-energoefektivitate-2022-2026/) |
| 리투아니아 | 클라이페다 LNG 터미널 슬롯 예약 규모 | 계약이 순차적으로 만료된다. 예약비는 전체 슬롯에 들고, 연료 수요가 높을 때 확보된 용량이 산업을 지지한다. | [터미널 운영사의 2022년 용량 배정 공고](https://knenergies.lt/en/klaipeda-lng-terminal-has-allocated-long-term-capacity/) |
| 튀르키예 | 2023년 지진 피해 농촌 주택 복구 사업을 바탕으로 한 복구 발주 | 한정된 피해 물량을 배정한다. 발주와 인도 때 비용이 들고 기존 발주분은 신규 발주가 줄어도 완공된다. | [세계은행 농촌 주택·공공서비스 복구 사업](https://www.worldbank.org/en/news/press-release/2023/06/27/world-bank-approves-1-billion-for-turkiye-to-help-restore-rural-homes-and-essential-public-services-to-people-in-earthqu) |
| 이스라엘 | 사법 개혁과 하레디 병역 면제 | 사법 개혁은 반대 시위를, 병역 면제 축소는 성향별 지지 변화를 일으킬 수 있다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/isr/IsraelModule.kt) |
| 이란 | 헌법수호위원회 심사와 히잡 단속 | 위원회가 의회 통과 법안을 심사한다. 단속 강도와 청년 불만이 전국 시위 가능성에 영향을 준다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/irn/IranModule.kt) |
| 사우디아라비아 | 관광개발기금과 카팔라(Kafalah)의 중소 관광 사업 금융지원에 기반한 보증 규모 | 사업이 만기에 영업을 시작하거나 부실해진다. 신규 보증을 줄여도 과거 보증의 경기 연동 손실이 발생할 수 있다. | [사우디 재무부 2024년 예산서, 97쪽](https://mof.gov.sa/documents/498521/498523/Bud-E%2B2024%2BF16%2B%281%29.pdf/b309e46e-82ec-8015-df69-d5016d4cf670?download=true&t=1792162193215&version=1.0) |
| 이집트 | 발라디 빵 보조 공급 범위 | 직전 월 국가 곡물 장부의 소비 부족이 공급량을 제한한다. 국제 곡물 원가의 이동에 따라 같은 공급량에도 재정 부담과 가계 혜택이 달라진다. | [세계은행 이집트 밀 조달·식량안보 사업](https://www.worldbank.org/en/news/press-release/2022/06/28/us-500-million-project-will-help-to-strengthen-egypt-food-and-nutrition-security) |
| 남아프리카 공화국 | 에스콤 발전 설비의 계획 정비 강도 | 즉시 발전 용량 일부를 정지시키는 대신 정비 완료 후 비계획 정지가 줄어든다. 발전과 중화학 부문이 영향을 받는다. | [에스콤 2023년 중간 실적](https://www.eskom.co.za/eskom-releases-its-interim-results-for-the-period-ended-30-september-2023/) |
| 대만 | 양안 정책 | 해협 긴장도는 중국의 무역 보복과 반도체 공급망 위험에 영향을 준다. | [게임 규칙](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/twn/TaiwanModule.kt) |
| 인도네시아 | 니켈 제련 투자의 인허가·기반시설 지원 | 인허가와 준공 사이에 시차가 있다. 전력 여력에 따라 설비 중 일부가 유휴 상태가 되며 가동량만 산업 생산으로 이어진다. | [인도네시아 에너지광물자원부의 2023년 니켈 제련소 인허가 방침](https://www.esdm.go.id/en/media-center/news-archives/pemerintah-batasi-izin-pembangunan-smelter-nikel-kelas-ii) |
| 베트남 | 메콩 삼각주 저배출 쌀 전환 농가 모집 | 2030년까지 작기별로 등록한다. 교육을 마친 논이 남아서 이후 작기의 생산성과 농가 투입비에 영향을 준다. | [베트남 정부의 2023년 100만 헥타르 계획](https://en.baochinhphu.vn/viet-nam-to-develop-1-million-ha-of-low-emission-high-quality-rice-by-2030-111231128100304407.htm) |
| 태국 | 보편의료보장제도에서 지역 일차 진료에 배정하는 예산 | 진료 역량과 환자 유입량으로 실제 처리량을 정한다. 대기 환자 일부가 상급병원으로 의뢰돼 비용이 든다. | [태국 국가건강보장청의 보편의료보장·일차 진료 설명](https://eng.nhso.go.th/ucs-at-a-glance) |
| 르완다 | 기린카 취약 농가 젖소 신규 매입 | 정부 매입과 기존 수혜자의 첫 암송아지 재분양이 별도로 움직인다. 공공 매입 중단 뒤에도 다른 가구에 분양될 수 있다. | [르완다 농업부 기린카 사업](https://www.minagri.gov.rw/updates/news-details/girinka-results-delight-jersey-s-chief-minister) |
| 파키스탄 | 베나지르 카팔라트 취약 가구 등록 범위 | 자격조사 후 매달 권리를 적립하고 분기말에 지급한다. 정책을 축소해도 이미 쌓인 급여는 소멸하지 않는다. | [BISP 2023년 이사회](https://bisp.gov.pk/NewsDetail/MGRjZTI4Y2YtM2UxMy00ODk1LWE3OTktZDlkYjQwMjFiODAw) |

## 수치와 범위

재정 화면의 **국가별 정책 지출 증감**은 같은 경제 여건에서 시작 정책을 유지했을 때와 실제 집행액의 차이다. 해당 월 결산 후 기초지출에 반영하며, 보조금 회수나 매입 축소로 음수가 될 수 있다.

폴란드의 자녀당 월 800즈워티, 베트남의 2030년 100만 헥타르 목표는 제도 자료에서 가져왔다. 집행 강도와 공기, 월별 심사·농사 달력, GDP 대비 사업비와 보증 손실률, 산업·여론에 미치는 효과는 게임 추정값이다.

- 이탈리아 회복계획은 개혁·투자 목표 달성에 연동해 EU 보조금과 대출을 지급한다. 게임에서는 반기마다 완료 사업을 검수해 보조금 몫을 국내 순지출에서 차감한다.
- 사우디아라비아의 기금과 보증 기관은 재정 한 계정으로 합산한다. 파키스탄의 급여는 매달 적립해 분기말에 지급한다.
- 라트비아의 여러 차수 단열 사업은 한정된 주택 물량으로 묶고, 리투아니아의 다년 계약은 12개월에 걸쳐 갱신한다. 두 나라의 에너지 가격은 국제 원유 지수를 따른다. 가스·전력·니켈·쌀은 게임의 국제 거래 품목에 별도로 잡히지 않는다.

영국·캐나다·호주의 정책 단위와 시차는 [주택 사업 자료](../simulation/src/main/resources/world/housing_programs.json)에 있다. 폴란드·이탈리아 등 15개국의 사업 계수와 지표 단위, 월별 집행 산식은 다음 파일에서 확인할 수 있다. [정책 데이터](../simulation/src/main/resources/world/national_programs.json) · [유럽·튀르키예 사업](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/EuropeanPrograms.kt) · [아프리카·아시아 사업](../simulation/src/main/kotlin/team/themoment/axia/simulation/country/AfricanAsianPrograms.kt)
