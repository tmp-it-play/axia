# AXIA Desktop MVP 구현 명세

> 상태: 구현 기준 v1 · 살아있는 명세
>
> 최초 작성: 2026-09-02
>
> 대상 저장소: `it-play/axia`
>
> 기준 조직: `themoment-team`
>
> 문서 원칙: 이후 요구사항은 이 파일을 직접 수정하며, 확정되지 않은 내용은 마지막의 **미결정 사항**에만 둔다.

## 1. 제품 목표

AXIA는 더모먼트 구성원을 위한 Desktop 우선 HR·업무 관리 시스템이다. DataGSM을 구성원 자격과 기본 인적 정보의 원본으로 사용하고, `themoment-team` GitHub 조직의 Issue, Pull Request, Projects 정보를 하나의 화면에서 조회·관리한다.

핵심 목표는 다음과 같다.

1. 더모먼트 구성원만 DataGSM OAuth로 로그인한다.
2. 조직 역할, 기술 포지션, 제품 POC를 서로 독립적으로 관리한다.
3. 제품별 GitHub 태스크를 통합 조회하고 허용된 사용자가 Axia에서 수정한다.
4. 도입 이후의 Issue Close와 PR Merge 기여를 일관되게 집계한다.
5. 지연, 마감 임박, 담당자 변경, 휴가·작업 불가 상황을 인앱·Desktop 알림으로 전달한다.
6. macOS와 Windows 앱을 자동 업데이트 가능한 형태로 배포한다.

### 1.1 MVP 범위

- Compose Multiplatform Desktop 앱
- 공식 지원 OS·아키텍처: macOS Apple Silicon(arm64), Windows x64
- 한국어 UI만 지원
- Ktor 백엔드
- 기존 MySQL 8.0.32 사용
- DataGSM OAuth와 Events(`student.updated`, `club.updated`) 연동
- `themoment-team` 조직 GitHub App, Issues, Pull Requests, Projects v2 연동
- 인앱 알림과 macOS·Windows 네이티브 알림
- GitHub Releases 기반 Desktop 자동 업데이트

### 1.2 MVP 제외 범위

- Linux Desktop
- Intel Mac
- Windows ARM64
- Android 및 Web 제품화
- Discord 알림
- PR 병합
- 임의 제품 생성 UI
- CSV 등 분석 데이터 내보내기
- 강제 업데이트 또는 최소 지원 버전 차단
- 범용 감사 로그와 역할·설정 변경 이력
- 도입 이전에 이미 완료된 GitHub 태스크의 소급 집계
- DataGSM의 GitHub ID를 Axia에서 직접 수정하는 기능
- DataGSM OpenAPI와 주기적 DataGSM 구성원 polling/reconciliation

기존 Android·Web 모듈은 삭제할 필요가 없지만 MVP 검증과 배포 범위에서는 제외한다. Desktop 패키징 설정에서는 Linux `deb` 산출물을 제거한다.

## 2. 용어와 고정 도메인

### 2.1 제품

MVP 제품은 아래 네 개로 고정한다. 표시 이름의 쉼표도 제품명의 일부다.

| 내부 키 | 표시 이름 |
|---|---|
| `HELLO_GSM` | `Hello,GSM` |
| `DATA_GSM` | `DataGSM` |
| `EVERY_GSM` | `EveryGSM` |
| `READY_GSM` | `Ready,GSM` |

내부 코드와 DB는 제품 ID를 사용하고, 제품마다 별도 구현을 복제하지 않는다. 추후 제품을 추가할 수 있도록 공통 인터페이스를 사용하되 MVP에는 제품 생성·삭제 UI를 제공하지 않는다.

### 2.2 조직 역할

사용자는 아래 `OrganizationRole` 중 최대 하나만 가질 수 있으며 역할이 없어도 된다.

- `Team Leader`
- `Server Head`
- `Cloud Head`
- `Front Head`
- `Design Head`
- `C-Leader`
- `Hello,GSM-PO`
- `DataGSM-PO`
- `EveryGSM-PO`
- `Ready,GSM-PO`

`Hello,GSM-PO`, `Ready,GSM-PO`의 쉼표는 반드시 보존한다.

- `Team Leader`는 여러 활성 사용자가 동시에 보유할 수 있다.
- 나머지 각 조직 역할은 활성 보유자를 최대 한 명으로 제한한다.

### 2.3 기술 포지션

`TechnicalPosition`은 독립적인 0..N 다중 선택 값이다.

- `Server`
- `Frontend`
- `Cloud`
- `Design`

사용자는 조직 역할 없이 기술 포지션만 가질 수도 있고, 기술 포지션이 전혀 없어도 가입할 수 있다.

### 2.4 제품 POC

`ProductPoc`도 독립적인 0..N 다중 선택 값이다.

- `Hello,GSM-POC`
- `DataGSM-POC`
- `EveryGSM-POC`
- `Ready,GSM-POC`

PO 역할과 POC는 별개다. 한 제품의 PO가 다른 여러 제품의 POC일 수 있다.

### 2.5 구성원 구분

- 재학생
- 졸업생

졸업은 탈퇴가 아니다. 기존 구성원이 졸업해도 일반 조회, 역할, 기술 포지션, POC 권한은 재학생과 동일하게 유지한다. 다만 관리자성 알림 수신 규칙에는 별도의 재학생 조건이 있다.

## 3. 시스템 구조

```text
macOS / Windows Desktop
        |
        | HTTPS + short access token
        v
Ktor API + background scheduler  ----  MySQL 8.0.32
        |                |
        |                +---- notification/reminder jobs
        |
        +---- DataGSM OAuth / UserInfo / Events
        +---- GitHub App API / Webhooks / Projects v2
```

### 3.1 책임 경계

- DataGSM은 구성원 신원, 학교 정보, 전공동아리, 등록 GitHub 로그인 문자열의 원본이다.
- GitHub는 Issue, PR, 리뷰 요청, Project 항목과 필드의 원본이다.
- Axia는 역할·포지션·POC, 휴가·작업 불가, 알림 상태, 연동 구성, 도입 이후 완료 이벤트와 통계 projection의 원본이다.
- Desktop은 표현 계층이며 모든 인가 판정과 외부 쓰기 검증은 서버가 수행한다.

### 3.2 현재 코드베이스 기준

- Kotlin 2.4.10
- Compose Multiplatform 1.11.1
- Ktor 3.5.2
- `app/shared`: 공통 UI와 presentation 로직
- `app/desktopApp`: macOS·Windows 진입점과 플랫폼 어댑터
- `core`: 공유 도메인 타입과 API 계약
- `server`: 인증, 권한, 외부 연동, 영속화, 스케줄러

Keychain, Credential Manager, 메뉴바·트레이, 네이티브 알림, 자동 업데이트는 플랫폼별 인터페이스 뒤에 둔다.

## 4. 로그인과 계정 생명주기

### 4.1 인증 원칙

- 로그인 수단은 DataGSM OAuth 하나뿐이다.
- 사용자별 GitHub OAuth와 로그인 후 별도 GitHub 연결 절차는 없다.
- Desktop은 내장 WebView가 아닌 시스템 기본 브라우저를 연다.
- DataGSM OAuth는 Authorization Code + PKCE S256을 사용한다.
- 공개 콜백 URI는 `https://kimtaeeun.site/axia/api/auth/datagsm/callback`이다.
- 서버가 OAuth 콜백을 처리하고, Desktop은 짧은 수명의 일회성 로그인 거래 ID를 폴링해 결과를 받는다.
- 로그인 거래의 secret은 원문 저장하지 않고 해시하며 성공, 실패, 만료 시 즉시 사용할 수 없게 한다.
- OAuth token 교환 뒤 `datagsm:self_read` 권한으로 DataGSM `/userinfo`를 한 번 호출한다.
- `/userinfo`의 `student.id`, 이름·이메일·학적·역할, `student.majorClub`, `student.githubId`를 로그인 시점의 authoritative snapshot으로 사용한다.
- Axia는 DataGSM OpenAPI `/v1/students`와 `X-API-KEY`를 사용하지 않는다.
- 배포 전 운영 `/userinfo`가 `majorClub`과 `githubId`를 포함하는 최신 `StudentResDto` 계약을 충족하는지 확인한다. 충족하지 않으면 DataGSM 배포·계약을 먼저 수정하며, Axia에 OpenAPI fallback을 만들지 않는다.

### 4.2 검증 순서

로그인은 반드시 다음 순서로 진행한다.

1. DataGSM OAuth token 교환과 `/userinfo` 사용자 식별
2. `/userinfo`의 `student.majorClub`로 더모먼트 전공동아리 소속 확인
3. `/userinfo`의 `student.githubId` 존재 여부 확인
4. GitHub 계정 실존 여부 확인
5. GitHub 로그인 문자열을 불변 numeric GitHub ID로 변환
6. 활성 Axia 사용자와 numeric GitHub ID 충돌 여부 확인
7. GitHub 계정이 `themoment-team`의 정식 organization member인지 확인
8. Axia 사용자 생성 또는 기존 사용자 상태 갱신
9. Desktop 세션 발급

앞 단계가 실패하면 뒤 단계는 실행하지 않는다. 특히 더모먼트 구성원임을 확인하기 전에는 GitHub ID 관련 정보나 충돌 여부를 노출하지 않는다. Outside collaborator는 organization member가 아니므로 로그인 자격으로 인정하지 않는다.

### 4.3 최초 가입

- 가입은 최초 정상 로그인 시 JIT로 수행한다.
- 조직 역할, 기술 포지션, POC가 모두 없는 상태로 가입할 수 있다.
- 기술 포지션이 없는 사용자는 가입을 막지 않는다.
- 기술 포지션 없이 가입하면 현재 Team Leader와 명시적 시스템 관리자에게 한 번만 기능성 알림을 생성하며, 동일 사용자를 이유로 반복 발송하지 않는다.
- 포지션이 배정될 때까지 관리 화면의 `포지션 미지정` 목록에 계속 표시한다. 가입 이후 포지션이 다시 0개가 되는 경우에도 목록에는 표시하지만 가입 알림을 다시 보내지는 않는다.
- 과거 더모먼트 소속 정보가 없는 졸업생을 위한 수동 가입 예외는 제공하지 않는다.

### 4.4 GitHub ID 누락·오류 화면

GitHub ID가 없거나 실제 계정이 존재하지 않으면 로그인을 차단하고 전용 화면을 보여준다.

- 문제 설명
- [DataGSM 내 정보](https://www.datagsm.kr/myinfo) 열기
- `수정 완료 — 다시 확인` 버튼

Axia는 DataGSM의 GitHub ID를 조회만 하며 등록·수정 API를 호출하지 않는다. 사용자가 DataGSM 서비스에서 직접 변경해야 한다.

졸업생도 Axia 접근 자격을 유지하므로, DataGSM이 졸업생에게도 GitHub ID 등록·수정 경로를 제공하는 것이 외부 선행 조건이다. 현재 DataGSM UI가 이를 허용하지 않는다면 DataGSM 측에서 먼저 해소해야 하며 Axia는 별도 우회 입력란을 만들지 않는다.

### 4.5 GitHub ID 충돌

- 활성 연결은 numeric GitHub ID별 한 명만 허용한다.
- 먼저 정상 연결한 사용자가 현재 연결을 유지한다.
- 나중에 로그인한 사용자는 충돌 전용 화면으로 보내고 로그인시키지 않는다.
- 차단 화면에는 나중에 로그인한 사용자 본인의 이름을 표시한다.
- 기존 소유자의 이름이나 식별 정보는 절대 표시하지 않는다.
- 기존 소유자에게는 나중에 로그인한 사용자의 이름이 포함된 인앱 알림을 보낸다.
- 동일한 기존 소유자, 충돌 사용자, numeric GitHub ID 조합의 충돌이 해결되지 않은 동안에는 알림을 한 번만 생성한다.
- 반복 로그인은 새 알림을 만들지 않으며, 충돌이 해결된 뒤 같은 조합이 다시 충돌할 때만 새 알림을 만든다.
- 시스템 관리자에게 별도로 알리지 않는다.
- Axia 내부에서 강제 소유권 변경·수동 우회 기능을 제공하지 않는다.

### 4.6 GitHub ID 변경과 해제

- `student.updated` Event 또는 다음 OAuth 로그인에서 `/userinfo`를 다시 조회해 GitHub login 값이 달라진 사실을 정상 확인하면 기존 numeric GitHub ID의 활성 점유를 즉시 해제한다.
- 이후 새 GitHub 계정의 실존성, 중복, 조직 가입을 다시 검증한다.
- 검증이 끝날 때까지 기존 GitHub 연결로 Axia를 계속 사용하게 하지 않는다.
- 새 연결이 정상이면 새 numeric GitHub ID를 연결하고, 실패하면 세션을 차단한 미연결 상태로 둔다.
- 기존 numeric GitHub ID는 다른 활성 사용자가 연결할 수 있다.
- 과거 완료 이벤트는 당시 Axia 사용자에게 고정되며 ID 재사용으로 이동하지 않는다.
- 새 ID가 누락, 무효, 충돌, 조직 미가입이면 세션을 즉시 차단한다.
- 이전 GitHub 계정에 남은 미완료 태스크의 담당자는 자동 변경하거나 해제하지 않는다.
- 별도의 재배정 알림이나 일괄 교체 기능을 제공하지 않고, 현재 연결 계정과 다른 GitHub 담당자가 남아 있음을 태스크 UI에서 경고한다.

### 4.7 GitHub 조직 가입 상태

- 최초 로그인 때 `themoment-team` organization member가 아니면 로그인할 수 없다.
- 기존 사용자가 조직에서 제거되면 Axia 접근과 세션 사용을 즉시 정지한다.
- 이 경우 역할, 기술 포지션, POC, 명시적 관리자, 기록은 삭제하지 않는다.
- 다시 조직 member가 되면 보존된 설정으로 접근 자격을 자동 복구한다.
- 아직 절대 만료일이 지나지 않은 세션은 정지 기간 후 다시 사용할 수 있다.

### 4.8 DataGSM 동기화와 탈퇴

- 로그인마다 OAuth `/userinfo`를 조회해 해당 사용자의 현재 상태를 덮어쓴다.
- `student.updated` Event는 이름·이메일·학적·재학생/졸업생/자퇴 상태·역할·전공동아리·GitHub ID가 포함된 변경 후 학생 snapshot으로 반영한다.
- 더모먼트 동아리의 `club.updated` Event도 반드시 구독한다. 동아리 관리 경로의 부원 변경은 학생별 `student.updated`를 항상 발생시키지 않으므로, 가입·탈퇴 판정에는 두 Event를 함께 사용한다.
- 두 Event는 HMAC 서명을 검증하고 DataGSM Event payload ID로 멱등 처리한 뒤 즉시 반영한다.
- DataGSM OpenAPI를 호출하거나 주기적으로 전체 구성원을 polling/reconciliation하지 않는다.
- DataGSM Event는 전송 재시도가 모두 실패하면 영구 유실될 수 있으며, MVP는 이 잔여 위험을 수용한다. 누락된 상태는 같은 사용자나 더모먼트 동아리의 후속 전체 snapshot Event 또는 다음 OAuth 로그인 때 복구된다.
- 정상 수신한 탈퇴 Event는 즉시 권한 회수에 반영하지만, 유실된 Event만 있고 후속 Event나 로그인이 없다면 마지막 정상 상태가 남을 수 있다.
- DataGSM OAuth 요청 오류는 탈퇴로 해석하지 않는다.
- 별도의 DataGSM 장애 유예·차단 모드는 만들지 않는다. 신규 로그인 중 오류는 해당 로그인만 실패하고 기존 사용자는 마지막 정상 상태를 유지한다.

더모먼트 탈퇴가 정상적으로 확인되면 다음을 원자적으로 수행한다.

1. 사용자 비활성화
2. 모든 Axia 세션 종료
3. 현재 numeric GitHub ID 점유 해제
4. 조직 역할 제거
5. 모든 기술 포지션 제거
6. 모든 POC 제거
7. 명시적 시스템 관리자 제거
8. 완료·통계 기록 보존

GitHub의 기존 담당자 지정은 자동으로 제거하지 않는다. 재배정 알림 수신자는 권한 제거 직전 스냅샷과 미완료 태스크의 제품을 기준으로 먼저 산출한다. 해당 미완료 태스크에는 `비활성 담당자` 경고를 표시하고 해당 제품의 현재 PO, 탈퇴 직전 담당자의 기술 포지션에 대응하는 현재 재학생 Head, 현재 재학생 Team Leader에게 중복 제거한 재배정 필요 알림을 보낸다. C-Leader와 명시적 시스템 관리자는 이 알림의 기본 수신자가 아니다.

### 4.9 재가입

- 동일한 DataGSM 불변 사용자 ID가 다시 더모먼트 소속이 되면 기존 Axia 사용자 레코드를 사용한다.
- GitHub ID와 조직 가입 검증을 처음부터 다시 수행한다.
- 과거 완료 기록만 기존 사용자에게 이어 붙인다.
- 이전 조직 역할, 기술 포지션, POC, 명시적 관리자 권한은 복구하지 않는다.
- 권한 상태는 신규 팀원과 동일한 빈 상태로 시작한다.

### 4.10 세션

- 최초 발급 시점부터 정확히 240일의 절대 만료기간을 사용한다.
- 사용 활동으로 만료일을 연장하지 않는다.
- 짧은 access token과 서버 저장형 refresh session을 사용한다.
- refresh credential은 매 갱신 시 회전시키고 재사용을 탐지한다.
- macOS는 Keychain, Windows는 Credential Manager에 refresh credential을 저장한다.
- 여러 기기 동시 로그인을 허용한다.
- 사용자는 기기 목록 조회, 개별 세션 해제, 전체 세션 해제를 할 수 있다.
- DataGSM 탈퇴, GitHub ID 오류, GitHub 조직 이탈은 240일보다 우선한다.

## 5. 권한 모델

모든 권한은 현재 사용자 상태를 기준으로 요청마다 서버에서 계산한다. Desktop에서 버튼을 숨기는 것은 UX일 뿐 보안 경계가 아니다.

### 5.1 시스템 관리자

- 명시적 시스템 관리자는 조직 역할과 별도 boolean capability다.
- Team Leader는 역할을 가진 동안 유효 시스템 관리자 권한을 자동으로 얻는다.
- Team Leader를 잃으면 파생 관리자 권한도 사라진다.
- 명시적 관리자도 함께 가진 경우 그 권한은 별도로 유지한다.
- C-Leader는 자동 관리자가 아니다.
- 명시적 관리자만으로는 분석 그래프 권한을 얻지 않는다.

### 5.2 권한표

| 행위자 | 전체 태스크 조회 | 태스크 쓰기 | 연동 대상 관리 | 조직 역할 관리 | 기술 포지션 관리 | POC 관리 | 타인 휴가 조정 | 분석 |
|---|---:|---|---|---|---|---|---|---|
| 일반 활성 구성원 | O | X | X | X | X | X | X | X |
| 제품 POC | O | POC 제품 | X | X | X | X | X | X |
| 제품 PO | O | PO 제품 + POC 제품 | PO 제품 | X | X | 전 제품 | X | PO 제품 |
| Head | O | 전 제품 | 전 제품 | X | 자신의 기술 포지션 | 전 제품 | 자신의 포지션 구성원 | 자신의 포지션 |
| Team Leader | O | 전 제품 | 전 제품 | O | 전 포지션 | 전 제품 | 전 구성원 | 조직 전체 |
| C-Leader | O | 전 제품 | 전 제품 | O | 전 포지션 | 전 제품 | 전 구성원 | 조직 전체 |
| 명시적 시스템 관리자 | O | 전 제품 | 전 제품 | O | 전 포지션 | 전 제품 | X | 역할이 없다면 X |

세부 규칙:

- Team Leader, C-Leader, 유효 시스템 관리자는 조직 역할을 부여·변경·회수할 수 있다.
- C-Leader도 Team Leader를 부여하거나 회수할 수 있다.
- 현재 유효 시스템 관리자만 명시적 시스템 관리자 권한을 부여·회수할 수 있다.
- 각 Head는 자신의 기술 포지션만 부여·회수한다.
- Team Leader, C-Leader, 유효 시스템 관리자는 모든 기술 포지션을 관리한다.
- 모든 PO, 모든 Head, Team Leader, C-Leader, 명시적 시스템 관리자는 모든 제품의 POC를 관리한다.
- PO 역할만으로 기술 포지션이나 타인의 휴가를 관리할 수 없다.
- 명시적 시스템 관리자만으로 타인의 휴가를 관리할 수 없다.

### 5.3 POC 제거 후 태스크 한정 접근

- POC를 제거할 때 해당 제품의 미완료 담당 태스크가 있으면 경고하지만 제거는 허용한다.
- 제거 즉시 해당 제품의 일반 쓰기 권한은 사라진다.
- 다만 제거 시점에 자신이 담당한 미완료 태스크에는 완료 또는 담당 해제 때까지 읽기·쓰기 권한을 유지한다.
- 더모먼트 탈퇴 또는 GitHub 조직 미가입 상태는 이 예외보다 우선하며 모든 접근을 차단한다.

### 5.4 부트스트랩

임시 부트스트랩은 빈 DB에서만 한 번, fail-closed로 동작한다.

- `s24058@gsm.hs.kr`: 명시적 시스템 관리자
- 이름이 정확히 `정연돈`: Team Leader
- 이름이 정확히 `정효주`: Team Leader

둘 이상의 사용자가 이름으로 매칭되는 등 조건이 모호하면 권한을 부여하지 않는다. 구현에는 제거 대상임을 명확히 하는 `TODO(REMOVE_BOOTSTRAP)` 주석을 남긴다. 일반 애플리케이션 로직은 이메일이나 이름을 식별자로 사용하지 않는다.

## 6. GitHub 연동

### 6.1 인증과 최소 권한

- `themoment-team` 조직에 설치된 GitHub App의 Installation Token으로 서버가 호출한다.
- 사용자의 PAT 또는 사용자 OAuth token을 요구하지 않는다.
- Axia 변경은 GitHub에서 App bot의 행위로 보인다.
- Installation Token은 필요할 때 발급하고 장기 저장하지 않는다.
- 필요한 GitHub App 권한은 최소한 Issues read/write, Pull requests read/write, Projects read/write, organization Members read, Metadata read다.
- Webhook secret으로 원문 payload 서명을 검증한 뒤 처리한다.

GitHub App의 조직 설치 및 저장소 선택은 Axia의 책임 범위가 아니다. Axia는 연결 시 접근 가능 여부를 검사하며, 미설치·권한 부족이면 연결을 차단하고 GitHub App 설정으로 가는 경고 UI를 제공한다.

### 6.2 초기 저장소 후보 16개

| 제품 | 저장소 |
|---|---|
| Hello,GSM | `hellogsm-server-26`, `hellogsm-client-26`, `go-hellogsm`, `go-hellogsm-score-calculator`, `go-hellogsm-ops` |
| DataGSM | `datagsm-server`, `datagsm-client`, `datagsm-oauth-sdk-java`, `datagsm-openapi-sdk-java`, `datagsm-oauth-sdk-react`, `datagsm-openapi-sdk-python`, `datagsm-openapi-sdk-javascript` |
| EveryGSM | `everygsm-server-v2`, `everygsm-client-v2` |
| Ready,GSM | `readygsm-server`, `readygsm-client` |

Archived, fork, template, onboarding, bot, 기타 무관 저장소는 초기 대상에서 제외한다. 초기 후보는 seed이며 실제 GitHub App 접근 가능 여부를 통과한 저장소만 활성화한다.

### 6.3 Projects v2

[Project #19 `[Template] themoment-team Project Board`](https://github.com/orgs/themoment-team/projects/19)를 공통 스키마 기준으로 사용한다. 운영 보드는 다음과 같다.

| 번호 | 용도 |
|---:|---|
| 23 | DataGSM Server |
| 24 | DataGSM Client |
| 25 | Hello,GSM Server |
| 26 | Hello,GSM Client |
| 27 | Ready,GSM Server |
| 28 | Ready,GSM Client |
| 29 | EveryGSM Server |
| 30 | EveryGSM Client |

현재 공통 의미 필드:

- Status: `Todo`, `In Progress`, `Done`
- Priority: `High`, `Medium`, `Low`, `In review`
- Start date, Target date
- Assignees, Labels, Milestone, Repository
- Linked pull requests, Reviewers, Parent/Sub-issues
- Created, Updated, Closed timestamps

구현은 다음 원칙을 따른다.

- 필드명 문자열에 의존하지 않고 Project, Field, Option의 불변 Node ID를 의미 필드에 매핑한다.
- 공통 `ProjectBoardAdapter`를 사용한다.
- 제품·보드별 코드는 ID와 의미 매핑 설정만 재정의한다.
- 보드가 없거나 접근할 수 없으면 보드 기능만 비활성화하고 Issue·PR 연동은 유지한다.
- #19는 템플릿 참고용이며 운영 태스크·통계에 포함하지 않는다.
- 저장소와 보드는 각각 정확히 한 제품에만 속한다. API 검증과 DB 고유 제약을 함께 둔다.
- 한 제품은 여러 저장소와 여러 보드를 가질 수 있다.
- 저장소에는 지연 규칙을 결정할 기본 보드를 최대 하나 지정한다.
- Issue·PR이 여러 연동 보드에 있어도 저장소 기본 보드의 규칙만 사용한다.
- Draft는 자신이 생성된 보드의 규칙을 사용한다.

### 6.4 초기 기본 보드 매핑

2026-09-02 운영 보드의 실제 항목과 연결 저장소를 기준으로 다음 값을 seed한다.

| 저장소 | 기본 보드 |
|---|---:|
| `datagsm-server` | #23 |
| `datagsm-client` | #24 |
| `hellogsm-server-26` | #25 |
| `hellogsm-client-26` | #26 |
| `readygsm-server` | #27 |
| `readygsm-client` | #28 |
| `everygsm-server-v2` | #29 |
| `everygsm-client-v2` | #30 |
| DataGSM SDK 5개 | 없음 |
| Hello,GSM Go 저장소 3개 | 없음 |

SDK·Go 저장소는 Issue·PR 기능과 태스크 유형별 전역 지연값을 사용하되, 기본 보드가 지정되기 전까지 Project 필드와 보드별 지연 정책 기능을 비활성화한다. 운영 #29는 현재 항목이 없어도 `everygsm-server-v2`가 연결돼 있으므로 기본 보드로 사용한다. #23은 항목 7개가 모두 `datagsm-server`지만 GitHub의 연결 저장소 메타데이터가 비어 있으므로 Axia에서 연결 상태 경고를 표시할 수 있다.

## 7. 통합 태스크

### 7.1 태스크 유형

- GitHub Issue assignee 작업
- GitHub Pull Request assignee 작업
- GitHub Pull Request review request
- GitHub Project item assignee 작업
- 저장소 없는 GitHub Project Draft Issue

동일 Issue가 Project 항목으로도 존재하면 canonical GitHub content Node ID로 합쳐 중복 태스크로 표시하지 않는다. 보드 참여 정보는 태스크의 부가 컨텍스트다.

### 7.2 공통 조회와 쓰기

- 모든 활성 재학생·졸업생은 전 제품의 현재 태스크와 모든 구성원의 완료 이력 목록을 볼 수 있다.
- 일반 사용자는 읽기 전용이다.
- POC는 자신이 POC인 제품의 모든 태스크를 읽고 쓸 수 있다.
- PO는 자신의 PO 제품과 추가 POC 제품을 쓸 수 있다.
- 모든 Head, Team Leader, C-Leader, 유효 시스템 관리자는 전 제품 태스크를 쓸 수 있다.
- 모든 쓰기 작업은 서버에서 제품 범위와 계정 활성 상태를 다시 검사한다.

### 7.3 담당자 규칙

- 새 담당자 후보는 해당 제품의 활성 POC이면서 유효한 GitHub 연결과 조직 membership을 가져야 한다.
- 권한 있는 사용자는 같은 제품 POC를 복수 담당자로 추가·해제할 수 있다.
- 담당 해제 시 경고를 표시하지만 작업은 허용한다.
- 담당자 없이 Issue 또는 Draft를 만들 수 있다.
- 외부 GitHub에서 비-POC가 담당자로 지정돼도 자동 해제하지 않는다.
- 비-POC 외부 담당자는 경고 표시하며 그 사실만으로 Axia 쓰기 권한을 얻지 않는다.
- 사용자의 GitHub ID 변경 뒤 이전 계정이 담당자로 남은 경우에도 GitHub 원본은 수정하지 않고 시각적 경고만 표시한다.
- 담당자가 없으면 `담당자 없음` 배지, 경고 테두리, 전용 필터, 제품별 미할당 큐를 제공한다.

### 7.4 Issue 기능

권한 있는 사용자는 Axia에서 다음을 수행할 수 있다.

- 생성
- 제목·본문 수정
- 담당자 추가·해제
- Label, Milestone 수정
- 댓글 작성
- Open, Close, Reopen
- Project 보드 추가
- Status, Priority, Start date, Target date 수정

### 7.5 Pull Request 기능

- PR 상세, 담당자, 리뷰 요청, 연결 이슈, Project 필드와 상태를 조회한다.
- 권한 있는 사용자는 담당자, 리뷰 요청·재요청·해제, Label, Milestone, 댓글, Project 필드를 관리할 수 있다.
- PR Close/Reopen, 리뷰 승인·변경 요청 제출, Merge는 GitHub에서만 수행한다.
- Axia는 원본 PR 링크를 항상 제공한다.
- PR 리뷰 요청은 별도 할당 작업으로 추적하되 완료 기여 통계에는 포함하지 않는다.

### 7.6 Draft Issue

- 저장소 없이 Axia에서 Project Draft를 만들 수 있다.
- `DRAFT` 텍스트 배지, 전용 아이콘·설명으로 실제 Issue와 명확히 구분한다.
- 담당자, Status, Priority, Start date, Target date, 지연 알림을 적용한다.
- Draft의 `Done`은 완료 기여 통계에 포함하지 않는다.
- Axia에서 Draft를 실제 저장소 Issue로 변환할 수 있다.
- 변환 시점부터 새 Issue의 처리 시간과 지연 시간을 계산한다.

### 7.7 Project 항목 보관

- 권한 있는 사용자는 Axia에서 Project 항목을 보관하거나 보관 해제할 수 있다.
- Issue·PR 항목을 보관해도 저장소의 원본 Issue·PR은 변경하거나 삭제하지 않는다.
- Draft도 영구 삭제하지 않고 보관해 복구 가능하게 유지한다.
- Project 항목과 Draft의 영구 삭제 기능은 Axia에 제공하지 않으며 필요한 경우 GitHub에서 수행한다.

### 7.8 외부 변경과 출처

- Axia뿐 아니라 GitHub에서 직접 발생한 변경도 같은 projection과 알림 규칙으로 반영한다.
- GitHub 태스크 데이터는 GitHub가 원본이며 Axia는 캐시·검색·권한·알림용 projection을 유지한다.
- Project의 `Issue close → Done → board remove` 자동화 때문에 보드 현재 스냅샷을 완료 이력으로 사용하지 않는다.

## 8. 완료 기여와 분석

### 8.1 집계 시작

- 저장소 연결 시 `trackingStartedAt`을 기록한다.
- 그 이전에 완료된 태스크를 가져오지 않는다.
- 연결 당시 열려 있던 태스크는 가져온다.
- 기존 열린 태스크가 도입 후 완료되면 집계하며 처리 시간과 지연은 GitHub 원본 생성 시각을 사용한다.
- 완료 이벤트와 구성원 귀속은 계속 보존한다.

### 8.2 인정 이벤트

정확히 다음 두 이벤트만 완료 기여로 센다.

1. Issue Close
2. PR Merge

Issue 생성, 댓글, Project `Done`, Draft `Done`, PR 작성자라는 사실, 리뷰 제출·횟수는 기여로 세지 않는다.

### 8.3 적립 규칙

- 완료 순간 numeric GitHub ID가 Axia 사용자로 식별되는 모든 assignee에게 각 1건을 적립한다.
- 여러 담당자 사이에 1건을 나누지 않는다.
- Issue Close와 관련 PR Merge는 서로 다른 이벤트이므로 중복 적립을 허용한다.
- 제품 전체 완료 수는 assignee 수와 무관하게 완료 이벤트 한 건으로 센다.
- 사용자별 수치는 각 사용자의 full credit 합계다.
- 완료 시점에 Axia 사용자로 식별되지 않는 GitHub assignee를 위한 미귀속 credit은 만들지 않는다. 해당 완료는 제품 전체 이벤트에는 포함하지만 개인 credit에는 포함하지 않으며, 그 사용자가 나중에 가입해도 소급 귀속하지 않는다.
- 완료 이벤트에는 Axia 사용자, numeric GitHub ID, 제품, 당시 역할·포지션·POC 스냅샷을 저장한다.
- Reopen은 과거 Close 기여를 취소하지 않는다.
- 다시 Close하면 당시 assignee에게 새 완료 이벤트와 credit을 만든다.
- PR author는 merge 순간 assignee이기도 한 경우에만 credit을 받는다.

### 8.4 처리 시간

- Issue: 원본 `createdAt` → 각 Close 시각
- PR: 원본 `createdAt` → Merge 시각
- Draft에서 변환한 Issue: 변환 시각 → Close 시각
- 담당자 변경은 시작점을 바꾸지 않는다.
- 휴가·작업 불가 시간은 통계 처리 시간에서 빼지 않는다.

### 8.5 분석 접근과 화면

| 역할 | 분석 범위 |
|---|---|
| Team Leader, C-Leader | 조직 전체 |
| 각 Head | 현재 자신의 기술 포지션 구성원 |
| 각 PO | 자신의 제품과 제품 POC 구성원 |
| POC만 보유 | 없음 |
| 명시적 시스템 관리자만 보유 | 없음 |
| 역할 없음 | 없음 |

제공 지표:

- 기간별 완료 이벤트 추이
- 구성원별 Issue Close와 PR Merge 수
- 구성원별 평균 처리 시간
- 제품별 지연·미할당 현황

기간 필터는 `7일 / 30일(기본) / 90일 / 사용자 지정 / 전체 기간`이다. 기간에 따라 일·주·월 bucket을 자동 선택한다. 여러 포지션 사용자는 각 관련 Head 화면에 포함될 수 있으나 조직 전체 합계는 포지션 수만큼 중복하지 않는다. 그래프 권한이 없으면 분석 메뉴와 그래프를 아예 표시하지 않는다.

## 9. 지연·마감 정책

### 9.1 마감일 없는 태스크

적용 우선순위:

1. 태스크 직접 설정
2. 저장소 기본 보드의 Priority별 설정
3. 저장소 기본 보드의 기본값
4. 태스크 유형별 전역 기본값

전역 기본값:

| 유형 | 기본 기간 | 기준 시각 |
|---|---:|---|
| Issue | 14일 | Issue `createdAt` |
| Draft Issue | 14일 | Draft 생성 시각 |
| PR assignee 작업 | 3일 | PR `createdAt` |
| PR review request | 3일 | 해당 리뷰어에게 요청한 시각 |

현재 Priority bucket은 `High`, `Medium`, `Low`, `In review`, `Unset`이다. 기본 보드가 없으면 전역값을 사용한다. 리뷰 재요청은 새 요청 시점부터 새 주기를 시작한다. 담당자 변경은 Issue나 PR 자체의 시작 시각을 재설정하지 않는다.

### 9.2 Target date 우선

- Target date가 있으면 모든 기간 기반 규칙을 무시한다.
- 마감 3일 전부터 `마감 임박`으로 강조하고 임박 알림을 한 번 생성한다.
- 이미 3일 이내인 태스크를 새로 연결하거나 마감일을 설정하면 즉시 임박 상태로 본다.
- 마감일 당일 종료 후 다음 날 `00:00 Asia/Seoul`부터 지연 상태다.
- 휴가 때문에 Target date나 지연 상태 시작을 늦추지 않는다.

### 9.3 휴가를 반영한 개인별 계산

- 마감일이 없는 태스크는 담당자마다 독립적으로 계산한다.
- 담당자의 휴가·작업 불가와 겹치는 구간을 경과 시간에서 제외한다.
- 겹치는 여러 구간은 합집합으로 처리해 중복 차감하지 않는다.
- 다중 담당자의 지연 시작 시점은 서로 다를 수 있다.
- 마감일이 있는 태스크는 휴가 시간을 기간에서 빼지 않고 발송만 건너뛴다.

### 9.4 지연 반복

- 임계점을 처음 넘는 순간 즉시 첫 알림을 만든다.
- 완료되거나 수동으로 반복 알림을 중지할 때까지 정확히 72시간마다 반복한다.
- 주말과 공휴일을 포함한다.
- 휴가 중 건너뛴 알림을 복귀 즉시 보충하지 않고 다음 정규 주기를 기다린다.
- 도입 때 이미 지연된 열린 태스크는 최초 동기화 후 즉시 알린다.
- 완료 시 반복 상태를 종료한다.
- Reopen 시 이전 중지 상태를 초기화하고 새 반복 주기로 취급한다.
- 생성일 기준 이미 지연됐다면 Reopen 직후 즉시 알린다.

### 9.5 설정 권한

- 현재 담당자는 자신이 담당한 태스크의 직접 지연 기간을 바꿀 수 있다.
- 변경 사실만으로 별도 알림을 보내지 않는다.
- PO는 자신의 제품 보드 기본값과 Priority별 값을 설정할 수 있다.
- 모든 Head는 모든 제품 보드의 기본값과 Priority별 값을 설정할 수 있다.
- Team Leader, C-Leader, 유효 시스템 관리자는 모든 보드와 전역값을 설정할 수 있다.
- PO는 자신의 제품 태스크의 반복 알림을 중지하거나 다시 활성화할 수 있다.
- 모든 Head, Team Leader, C-Leader는 모든 제품 태스크의 반복 알림을 중지하거나 다시 활성화할 수 있다.
- 일반 POC, 현재 담당자, 명시적 시스템 관리자는 해당 자격만으로 반복 알림을 중지·재활성화할 수 없다.

## 10. 휴가·작업 불가

### 10.1 생성과 공개 범위

- 모든 활성 사용자는 자신의 일정을 승인 없이 즉시 등록·수정할 수 있다.
- `휴가`와 `작업 불가`를 구분한다.
- 시작 시각, 종료 시각, 사유가 모두 필수다.
- 시간 단위로 입력할 수 있고 무기한 일정은 허용하지 않는다.
- 캘린더 드래그와 수동 날짜·시간 입력을 모두 제공한다.
- 모든 활성 구성원은 다른 구성원의 유형, 기간, 사유를 볼 수 있다.
- 사유 입력란에는 경고 아이콘과 `모든 팀원이 볼 수 있으므로 민감한 건강·개인정보를 입력하지 마세요.`라는 안내를 표시한다.

### 10.2 타인 일정 수정

- 각 Head: 자신의 기술 포지션 구성원
- Team Leader, C-Leader: 모든 구성원
- PO: 권한 없음
- 명시적 시스템 관리자만 보유: 권한 없음

타인이 수정하면 영향받은 사용자에게 알림을 보낸다. 휴가 등록·변경에 대한 관리자성 알림은 관련 재학생 Head와 현재 재학생 Team Leader에게 보내며 PO, C-Leader, 명시적 시스템 관리자에게는 보내지 않는다. 본인이 만든 변경을 본인에게 다시 알리지 않는다.

### 10.3 캘린더 쓰기

- 휴가·작업 불가 일정과 GitHub Start date·Target date를 통합 표시한다.
- 제품, 사용자, 기술 포지션, 일정 유형 필터를 제공한다.
- Target date 드래그는 GitHub 원본에 즉시 반영한다.
- 휴가와 날짜 변경 성공 후 `되돌리기` 토스트를 제공한다.
- 되돌리기는 로컬 UI만 복원하지 않고 Axia DB 또는 GitHub 원본에 반대 쓰기를 수행한다.

## 11. 알림

### 11.1 전달 채널

- 서버 영속 인앱 알림 센터와 read/unread 상태
- 실행 중인 macOS 네이티브 알림
- 실행 중인 Windows 네이티브 알림
- Discord 미지원

Unread 알림은 읽을 때까지 기간 제한 없이 보존한다. Read로 전환된 알림은 `readAt`부터 90일이 지나면 자동 삭제한다. 완료 이력과 태스크 기록은 별도 기능 데이터이므로 알림 삭제의 영향을 받지 않는다.

창의 X 버튼은 앱을 종료하지 않는다. macOS에서는 메뉴바, Windows에서는 트레이에서 백그라운드 실행한다. 명시적인 `종료`만 프로세스를 끝낸다. 완전 종료 중 생성된 알림은 서버에 unread로 쌓고 다음 실행 시 가져온다.

### 11.2 담당자 변경

- 새 담당자에게 배정 알림을 보낸다.
- 제거된 담당자에게 해제 알림을 보낸다.
- PO, Head, Leader에게 배정 변경 자체를 통보하지 않는다.
- GitHub에서 직접 변경한 경우도 동기화 후 같은 규칙을 적용한다.
- 수신자가 휴가·작업 불가 중이면 일회성 알림을 잃지 않고 unread로 보존해 복귀 후 확인하게 한다.

### 11.3 지연 리마인드 수신자

각 지연 담당자별 수신자 집합을 계산한 뒤 중복을 제거한다.

- 지연된 담당자 본인: 재학생·졸업생 모두 포함
- 담당자의 현재 기술 포지션에 해당하는 현재 재학생 Head
- 현재 재학생 Team Leader

담당자가 Server와 Cloud를 모두 보유하면 양쪽 Head에게 보낸다. 관리 수신자에서는 졸업생 Head·Team Leader, PO, C-Leader, 명시적 시스템 관리자를 제외한다. 단, 제외 역할의 사용자가 지연 담당자 본인이면 본인 알림은 받는다.

발송 시점에 수신자가 휴가·작업 불가 중이면 해당 수신자에게 보내지 않는다. 지연 담당자 자신이 휴가 중인 경우 그 담당자 기준 리마인드를 건너뛴다.

### 11.4 마감 임박 알림 수신자

마감 3일 전 한 번 보내는 임박 알림은 각 담당자별 수신자를 계산한 뒤 중복 제거한다.

- 담당자 본인: 재학생·졸업생 모두 포함
- 담당자의 현재 기술 포지션에 해당하는 현재 재학생 Head
- 해당 제품의 현재 재학생 PO

Team Leader, C-Leader, 명시적 시스템 관리자는 마감 임박 알림의 관리 수신자에서 제외한다. 담당자가 복수 기술 포지션을 가지면 모든 관련 Head에게 보낸다. 발송 시점에 휴가·작업 불가 중인 수신자에게는 보내지 않는다.

### 11.5 미할당 태스크 알림

- 담당자가 없는 태스크가 마감 임박 또는 지연 상태가 되면 해당 제품의 현재 재학생 PO에게만 알린다.
- 담당자가 없으므로 관련 Head를 추정하지 않는다.
- Team Leader, C-Leader, 명시적 시스템 관리자를 대체 수신자로 추가하지 않는다.
- 수신 가능한 재학생 PO가 없으면 알림을 만들지 않고 `담당자 없음` 경고와 제품별 미할당 큐만 유지한다.

### 11.6 알림 멱등성

- GitHub Webhook delivery 또는 DataGSM Event가 재전송되어도 같은 알림을 중복 생성하지 않는다.
- 배정, 해제, 임박, 지연 회차마다 기능적 dedupe key를 둔다.
- GitHub ID 충돌 알림은 해결되지 않은 충돌 episode의 기존 소유자·충돌 사용자·numeric GitHub ID 조합을 dedupe key로 사용한다.

## 12. Desktop UI와 정보 구조

### 12.1 좌측 내비게이션

1. 내 대시보드
2. 태스크
3. 제품
4. 멤버
5. 캘린더
6. 분석 — 권한자에게만 표시
7. 알림
8. 관리 — 관리 기능이 하나라도 있을 때만 표시
9. 설정

로그인 후 첫 화면은 `내 대시보드`다.

### 12.2 내 대시보드

- unread 알림
- 지연 및 마감 임박 태스크
- 내 Issue, PR, 리뷰 요청, Project 항목
- 최근 배정·해제
- 최근 완료
- 현재 휴가·작업 불가 상태

태스크는 제품 기준으로 묶고 저장소와 원본 유형을 보조 정보로 표시한다. 기본 정렬은 `마감 초과 → 마감 임박 → High → 최근 갱신`이며 동률이면 가까운 마감일, 최근 갱신 순이다.

### 12.3 태스크 상태 표현

최소한 다음 상태를 색상 외 텍스트·아이콘·배지로 구분한다.

- Issue
- PR
- 리뷰 요청
- Draft
- 담당자 없음
- 비-POC 담당자
- 비활성 담당자
- 마감 임박
- 지연
- GitHub 장애로 인한 읽기 전용

### 12.4 멤버 디렉터리

모든 활성 사용자가 전체 목록과 상세를 볼 수 있다.

목록 우선 정보:

- 이름
- 재학생·졸업생
- 조직 역할
- 기술 포지션
- POC
- 현재 가용 상태

상세에서 점진적으로 노출할 정보:

- 이메일, 학년·반·번호, 전공, 전공동아리
- GitHub 계정과 조직 상태
- 조직 역할, 기술 포지션, POC
- 현재 할당 태스크와 완료 이력
- 휴가·작업 불가 정보

정보 자체를 숨기지는 않되 탭, 섹션, 접기 UI로 정보 범람을 막는다.

### 12.5 초기 디자인 시스템

초기 시각 방향은 더모먼트의 제품 업무를 빠르게 훑는 `작업 원장`이다. 동일한 둥근 카드가 반복되는 대시보드 대신 제품별 태스크 목록과 선택 항목의 우측 상세 패널을 중심으로 구성한다. 포지션 색상은 넓은 장식 면이 아니라 얇은 작업 흐름 rail과 작은 배지에 사용한다.

테마는 `시스템 설정 따름 / 라이트 / 다크`를 지원하고 기본값은 시스템 설정이다. 선택은 기기 로컬에 저장한다.

#### 기본 색상

| semantic token | Light | Dark |
|---|---|---|
| Background | `#F5F7FB` | `#15182A` |
| Surface | `#FFFFFF` | `#1E2435` |
| Subtle surface | `#ECEFF5` | `#252C40` |
| Primary text | `#172033` | `#F4F6FB` |
| Secondary text | `#5D687A` | `#AAB3C5` |
| Border | `#D8DEE9` | `#343D55` |
| Primary action | `#3659D9` | `#89A4FF` |

#### 포지션 색상

| 포지션 | 대표색 | Light 배지 `text / background` | Dark 배지 `text / background` |
|---|---|---|---|
| Server | `#2563EB` | `#1D4ED8 / #DBEAFE` | `#BFDBFE / #1E3A8A` |
| Frontend | `#7C3AED` | `#6D28D9 / #EDE9FE` | `#DDD6FE / #4C1D95` |
| Cloud | `#0F8F83` | `#0F766E / #CCFBF1` | `#99F6E4 / #134E4A` |
| Design | `#C02678` | `#BE185D / #FCE7F3` | `#FBCFE8 / #831843` |

위 텍스트·배경 조합은 모두 WCAG 일반 텍스트 기준인 4.5:1 이상의 초기 대비를 가진다. 색상만으로 포지션을 표현하지 않고 이름과 아이콘·배지를 함께 사용한다. 상태색과 포지션색을 혼동하지 않도록 마감·오류·성공에는 별도 semantic token을 사용한다.

#### 타이포그래피와 아이콘

- `Pretendard Variable`을 앱에 포함하고 OFL 라이선스 고지를 함께 배포한다.
- 크기 단계: 12 metadata, 13 secondary, 14 body, 16 emphasized body, 20 section title, 28 page title.
- 기본 weight는 400, 상호작용·강조는 500·600, 페이지 제목만 700까지 사용한다.
- 숫자 통계에는 tabular figures를 사용한다.
- 아이콘은 하나의 둥근 outline 계열로 통일하고 텍스트 없는 핵심 동작에는 tooltip과 접근성 이름을 제공한다.

#### 창과 레이아웃

- 기본 창 크기: `1440×900`
- 최소 창 크기: `1100×700`
- 좌측 사이드바: `224px`
- 선택 태스크 우측 상세 패널: `380px`
- 본문과 표는 왼쪽 정렬하며 긴 설명의 한 줄 길이는 80자 안팎으로 제한한다.
- 태스크 행은 최소 44px 높이를 유지하고 상태·제품·담당자 정보를 열 정렬한다.
- Dashboard는 동일 크기 카드 격자보다 넓은 내 작업 queue와 좁은 상태 rail을 우선한다.
- radius는 입력·버튼 6px, panel 10px, 완전한 pill은 태그와 POC·포지션 배지에만 사용한다.
- 구획은 그림자보다 border와 간격으로 구분하며 장식용 gradient는 사용하지 않는다.
- 직접 조작 피드백은 약 140ms, panel 전환은 약 200ms를 기본으로 하고 OS의 reduced motion 설정을 존중한다.

### 12.6 로그인 차단 화면

각 원인을 하나의 일반 오류로 뭉치지 않는다.

- 더모먼트 소속 아님
- GitHub ID 누락
- GitHub 계정 없음
- GitHub ID 충돌
- GitHub 조직 미가입
- 외부 인증 요청 실패

## 13. GitHub 장애 처리

### 13.1 읽기

- 최초 호출 포함 총 4회 자동 시도한다.
- 재시도는 지수 백오프와 jitter를 사용한다.
- 모두 실패하면 마지막 캐시 데이터를 계속 보여준다.
- 마지막 동기화 시각은 사용자에게 표시하지 않는다.
- 전역 오류 배너와 [GitHub Status](https://www.githubstatus.com/) 링크를 제공한다.
- GitHub 쓰기를 잠그고 백그라운드에서 복구를 재시도한다.
- 복구되면 전체 reconciliation 후 잠금을 해제한다.

### 13.2 쓰기

- 장애 중 변경을 로컬 큐에 저장해 나중에 몰아서 실행하지 않는다.
- timeout 또는 응답 유실 시 원격 상태를 먼저 조회한다.
- 미반영이 확실할 때만 재시도한다.
- 반영 여부를 판별할 수 없으면 자동 중복 실행하지 않고 새로고침·수동 재시도를 안내한다.
- 충돌하는 최신 GitHub 상태가 있으면 `409` 성격의 도메인 오류와 최신 projection을 반환해 사용자가 다시 판단하게 한다.

## 14. 자동 업데이트

### 14.1 공통 흐름

- 앱 시작 때 새 버전을 자동 확인하고 다운로드한다.
- 다운로드 완료 후 `업데이트 후 재시작 / 나중에`를 보여준다.
- `나중에`는 앱 사용을 막지 않으며 다음 시작 때 다시 확인한다.
- 설정에 수동 `업데이트 확인`을 제공한다.
- 강제 업데이트와 최소 지원 버전 차단은 없다.
- 시스템 관리자에게 업데이트 알림을 보내지 않는다.
- 릴리스 asset은 GitHub Releases를 사용한다.
- 업데이트 feed는 `https://kimtaeeun.site/axia/updates/...`에서 제공한다.

### 14.2 macOS

- Apple Silicon(arm64) 전용 DMG
- Sparkle 2 사용
- Apple Developer ID 서명
- Apple 공증
- Sparkle feed 서명
- 업데이트 후 재시작

### 14.3 Windows

- x64 전용 MSI
- WinSparkle 사용
- Ed25519 feed 서명
- 유료 Authenticode 인증서 없음
- unsigned installer로 인한 Unknown Publisher·SmartScreen 경고를 수용
- 경고 우회 기능은 만들지 않음

## 15. 데이터 저장 설계

물리 테이블명은 구현 중 조정할 수 있지만 아래 책임 분리는 유지한다. 시각은 `DATETIME(6)` UTC로 저장하고 UI에서 `Asia/Seoul`로 변환한다. GitHub Project의 date-only 필드는 `DATE`로 보존한다.

| 영역 | 주요 데이터 |
|---|---|
| `users` | DataGSM 불변 ID, 활성·탈퇴·GitHub정지 상태, 재학생·졸업생, 현재 프로필 |
| `user_github_links` | 현재 numeric GitHub ID, login 표시값; 현재 ID unique, 해제 시 null |
| `organization_role_assignments` | 사용자별 최대 1 역할, Team Leader 외 역할별 최대 1 활성 보유자 |
| `user_positions` | 사용자-기술 포지션 다대다 현재 상태 |
| `user_pocs` | 사용자-제품 다대다 현재 상태 |
| `explicit_admins` | 명시적 관리자 현재 상태 |
| `products` | 네 개 고정 제품 seed |
| `repository_integrations` | repo Node ID, 제품, 활성 여부, trackingStartedAt, 기본 보드 |
| `project_integrations` | Project Node ID, 제품, 활성 여부, 의미 필드 매핑 |
| `project_field_mappings` | Status/Priority/date 필드와 option의 불변 ID |
| `task_projections` | Issue/PR/Draft canonical 상태와 GitHub 원본 ID |
| `task_assignees` | GitHub actor ID, 선택적 Axia user ID, 현재 담당 상태 |
| `review_assignments` | 리뷰어, 요청·재요청 시각, 완료·해제 상태 |
| `completion_events` | Close/Merge occurrence, 제품, 완료 시각, 처리 시간 |
| `completion_credits` | 완료 당시 식별된 Axia 사용자·GitHub ID·역할·포지션·POC snapshot |
| `unavailability_periods` | 유형, 시작·종료, 공개 사유, 현재 값 |
| `reminder_policies` | 전역, 보드, Priority 기본값 |
| `task_reminder_overrides` | 태스크별 기간 override |
| `reminder_states` | 담당자별 due, last sent, next due, suppress 상태 |
| `notifications` | 수신자, 유형, payload, created/readAt/delivery 상태; read 후 90일 정리 |
| `desktop_sessions` | 기기, refresh hash, 고정 만료, revoke·suspend 상태 |
| `oauth_transactions` | 짧은 로그인 거래, state/PKCE/nonce hash, 만료·소비 상태 |
| `webhook_receipts` | GitHub delivery ID 또는 DataGSM Event payload ID와 처리 상태, 재전달 dedupe |
| `external_write_operations` | 불확실한 GitHub 쓰기 확인용 단기 operation state; 감사 이력 아님 |

### 15.1 고유 제약과 트랜잭션

- DataGSM 불변 ID unique
- 현재 numeric GitHub ID unique
- 사용자별 현재 조직 역할 최대 1
- Team Leader 외 조직 역할별 활성 보유자 최대 1
- 사용자-포지션 unique
- 사용자-제품 POC unique
- GitHub repo Node ID unique 및 제품 귀속 1개
- GitHub Project Node ID unique 및 제품 귀속 1개
- provider + receipt ID unique
- 완료 occurrence + credited user unique
- 알림 기능 dedupe key + recipient unique

MySQL unique 제약만으로 표현하기 어려운 활성·조건부 제약은 같은 트랜잭션에서 row lock을 잡고 검증한다.

### 15.2 보존과 비보존

계속 보존:

- 완료 이벤트와 credit
- 탈퇴 사용자와 과거 기록 연결
- unread notification과 기능상 필요한 reminder 상태

기간 보존:

- read notification: `readAt`부터 90일

저장하지 않음:

- 역할·포지션·POC 변경 이력
- actor, before/after 형태의 범용 감사 로그
- GitHub 쓰기 감사 이력
- 기능 구현과 무관한 관리자 변경 기록

`external_write_operations`는 중복 쓰기 방지를 위한 짧은 TTL의 기능 데이터이며 장기 감사 로그로 사용하지 않는다.

## 16. 서버 API 경계

외부 base URL은 `https://kimtaeeun.site/axia/api`다. 내부 버전 경로는 `/v1`을 사용한다.

### 16.1 인증

- `POST /v1/auth/datagsm/transactions`
- `GET /v1/auth/datagsm/transactions/{id}`
- `GET /auth/datagsm/callback`
- `POST /v1/auth/refresh`
- `POST /v1/auth/logout`
- `GET /v1/auth/sessions`
- `DELETE /v1/auth/sessions/{id}`
- `DELETE /v1/auth/sessions`

### 16.2 멤버와 권한

- `GET /v1/members`
- `GET /v1/members/{id}`
- `PUT /v1/members/{id}/organization-role`
- `PUT /v1/members/{id}/positions`
- `PUT /v1/members/{id}/pocs`
- `PUT /v1/members/{id}/explicit-admin`

### 16.3 제품과 연동

- `GET /v1/products`
- `GET /v1/products/{id}/integrations`
- `POST /v1/products/{id}/repositories`
- `PATCH /v1/products/{id}/repositories/{integrationId}`
- `POST /v1/products/{id}/projects`
- `PATCH /v1/products/{id}/projects/{integrationId}`
- `PUT /v1/repositories/{id}/default-project`
- `PUT /v1/projects/{id}/reminder-policy`

### 16.4 태스크

- `GET /v1/tasks`
- `GET /v1/tasks/{id}`
- `POST /v1/issues`
- `PATCH /v1/issues/{id}`
- `POST /v1/issues/{id}/close`
- `POST /v1/issues/{id}/reopen`
- `POST /v1/issues/{id}/comments`
- `POST /v1/projects/{id}/drafts`
- `POST /v1/drafts/{id}/convert`
- `PUT /v1/tasks/{id}/assignees`
- `PUT /v1/tasks/{id}/project-fields`
- `PUT /v1/tasks/{id}/reminder-override`
- `PUT /v1/tasks/{id}/reminder-suppression`

### 16.5 휴가·알림·분석

- `GET/POST /v1/unavailability`
- `PATCH/DELETE /v1/unavailability/{id}`
- `GET /v1/calendar`
- `GET /v1/notifications`
- `POST /v1/notifications/{id}/read`
- `POST /v1/notifications/read-all`
- `GET /v1/analytics/completions`
- `GET /v1/analytics/members`
- `GET /v1/analytics/operations`

### 16.6 외부 Webhook

- `POST /webhooks/datagsm`
- `POST /webhooks/github`

정확한 route naming은 구현 중 다듬을 수 있으나 인증, 권한, 멱등성, 오류 코드 계약은 공통 API 계약으로 고정한다.

## 17. 동기화와 안정성

### 17.1 DataGSM Event와 GitHub reconciliation

- DataGSM은 로그인 시 OAuth `/userinfo`와 `student.updated`·`club.updated` Events만 사용하며 별도 주기 조회를 하지 않는다.
- GitHub는 Webhook을 우선 처리하고, 전달 누락·서버 중단·Projects 이벤트 제한을 보완하는 주기적 reconciliation을 병행한다.
- 원문 payload 서명을 검증한 뒤 GitHub delivery ID 또는 DataGSM Event payload ID를 먼저 확보한다.
- DB transaction 안에서 projection 변경, 완료 이벤트, 알림을 함께 반영한다.
- 성공한 receipt만 완료 표시하고 실패한 receipt는 동일 식별자로 안전하게 재처리한다.
- 보드에서 자동 삭제된 완료 항목도 Axia 완료 이벤트는 남는다.

### 17.2 완료 이벤트 멱등성

- 동일 GitHub Webhook delivery ID와 동일 DataGSM Event payload ID는 각각 한 번만 처리한다.
- Axia에서 Close를 실행할 때 로컬에서 별도 credit을 선적립하지 않고 GitHub 결과 이벤트 또는 확인된 timeline occurrence를 통해 한 경로로 적립한다.
- reconciliation은 GitHub timeline의 Close occurrence 또는 Merge identity를 사용해 누락 이벤트만 보충한다.
- Reopen 후 재Close는 서로 다른 Close occurrence이므로 별도 이벤트다.

### 17.3 스케줄러

초기 배포는 단일 Ktor application container 안에서 background worker를 실행할 수 있다. 모든 주기 작업은 MySQL lease를 사용해 향후 다중 인스턴스에서도 한 worker만 같은 작업을 수행하게 한다.

주기 작업:

- GitHub open task·membership·Project reconciliation
- 지연·임박 계산
- 72시간 반복 알림 생성
- 알림 delivery 상태 처리
- GitHub 장애 복구 감지
- 만료 OAuth 거래와 세션 정리
- 단기 외부 쓰기 operation 정리

초기 운영 주기는 다음과 같이 설정하고 환경 설정으로 조정 가능하게 한다.

| 작업 | 초기 주기 |
|---|---:|
| GitHub Issue·PR·Projects 증분 reconciliation | 15분 |
| GitHub 전체 authoritative reconciliation | 6시간 |
| 실패한 GitHub App Webhook delivery 점검·재전송 | 6시간 |
| 지연·마감 임박 reminder due 판정 | 1분 |
| 애플리케이션 liveness·readiness health check | 30초 |

- 서버 시작 또는 외부 장애 복구 직후 GitHub 증분 reconciliation을 한 번 즉시 실행한다.
- GitHub 정기 작업에는 동시 집중을 피하기 위한 jitter를 둔다.
- `Retry-After`와 provider rate-limit header를 우선해 adaptive backoff한다.
- 주기 작업이 겹치면 동일 종류의 새 실행을 중첩하지 않고 기존 실행이 끝난 뒤 다음 회차를 계산한다.

## 18. 배포와 운영

### 18.1 서버

- 기존 Linux 서버에서 Docker container로 실행한다.
- MySQL 8.0.32는 기존 외부 배포 인스턴스를 사용한다.
- Desktop의 Linux 미지원과 서버 OS Linux는 별개다.
- container image에는 자격 증명을 넣지 않는다.

### 18.2 Nginx와 외부 경로

- API base: `https://kimtaeeun.site/axia/api`
- `/aixa`는 오타이며 사용하지 않는다.
- OAuth callback과 Webhook에서도 외부 scheme, host, path가 유지되도록 forwarded header를 설정한다.
- Webhook route는 body 변형·cache 없이 원문이 서버에 전달되어야 한다.
- Cloudflare와 Nginx에서 API와 Webhook 응답을 cache하지 않는다.
- 업데이트 feed·asset은 `https://kimtaeeun.site/axia/updates/...`에서 제공한다.

### 18.3 비밀 설정

배포 환경에서 주입:

- MySQL URL, 계정, 비밀번호
- DataGSM OAuth client 정보
- DataGSM Event secret
- GitHub App ID, Installation ID, Private Key
- GitHub Webhook secret
- access token 서명 키
- update feed 서명 관련 secret
- 임시 bootstrap 대상 설정

### 18.4 로깅과 개인정보

- 구조화 로그에 request/correlation ID를 포함한다.
- access·refresh token, OAuth code, PKCE verifier, private key, webhook secret, DB password를 기록하지 않는다.
- GitHub·DataGSM payload 전체와 휴가 사유를 무분별하게 로그에 남기지 않는다.
- 범용 사용자 행위 감사 로그는 만들지 않는다.
- 운영 로그는 일 단위로 회전하고 14일 후 삭제한다.

## 19. 구현 순서

### 단계 1 — 기반과 인증

- Desktop-only build target 정리
- server 설정, MySQL 연결, 공통 오류 계약
- DataGSM browser OAuth 거래
- 운영 OAuth `/userinfo`의 `majorClub`·`githubId` 계약 검증
- 로그인 gate와 240일 device session
- 회원 JIT 생성, `student.updated`·`club.updated` 처리, lifecycle
- 임시 bootstrap

### 단계 2 — 권한과 멤버

- 조직 역할, 포지션, POC, 명시적 관리자
- 중앙 authorization policy
- 멤버 목록·상세와 관리 UI
- GitHub ID 충돌·조직 membership 상태
- 휴가·작업 불가 CRUD와 캘린더 기초

### 단계 3 — GitHub 읽기와 제품

- GitHub App installation 인증
- 네 제품과 초기 repo·Project seed
- 공통 Projects adapter와 field mapping
- Webhook, reconciliation, task projection
- 제품별·사용자별 태스크 조회
- GitHub 장애 cache와 read-only UX

### 단계 4 — GitHub 쓰기와 알림

- Issue, assignee, comment, field, Draft 쓰기
- remote-state 확인형 재시도
- 배정·해제 알림
- 지연·마감·휴가 개인별 계산
- native notification, 메뉴바·트레이

### 단계 5 — 완료 통계와 분석

- Close·Merge occurrence와 credit snapshot
- 7/30/90/custom/all 필터
- 역할별 분석 범위
- 운영 지연·미할당 지표

### 단계 6 — 패키징과 배포

- macOS 서명·공증·Sparkle
- Windows MSI·WinSparkle·feed signing
- GitHub Releases와 update feed
- Docker, Nginx, Cloudflare 경로 검증
- 운영 smoke verification

## 20. 핵심 인수 기준

### 20.1 인증

- 로그인 시 OAuth `/userinfo`만으로 현재 더모먼트 소속과 GitHub ID를 검증하며 DataGSM OpenAPI를 호출하지 않는다.
- `student.updated`와 더모먼트 `club.updated`가 각각 멱등하게 반영되고, 별도의 DataGSM 주기 polling 작업이 존재하지 않는다.
- 더모먼트 소속 확인 전 GitHub 관련 정보가 노출되지 않는다.
- GitHub ID 누락·무효·충돌·조직 미가입이 각기 올바른 전용 화면으로 간다.
- 충돌 화면은 기존 소유자를 숨기고 기존 소유자 알림에는 나중 사용자 이름을 포함한다.
- DataGSM 탈퇴는 모든 현재 권한을 초기화하고 이력은 유지한다.
- GitHub 조직 이탈은 권한을 보존한 채 접근만 정지한다.
- 240일 만료는 활동으로 연장되지 않는다.

### 20.2 권한

- 일반 구성원은 전 태스크를 보지만 변경할 수 없다.
- POC는 자신의 제품만 변경한다.
- PO의 PO 제품과 추가 POC 제품 권한이 합집합으로 계산된다.
- POC 제거 뒤 기존 미완료 담당 태스크만 한정 접근된다.
- 명시적 관리자만 가진 사용자는 분석을 볼 수 없고 타인의 휴가를 바꿀 수 없다.
- 서버가 직접 API 호출에도 같은 규칙을 강제한다.

### 20.3 GitHub와 통계

- 한 Issue가 보드에도 있어도 태스크·완료 이벤트가 중복되지 않는다.
- close 순간 Issue assignee 모두가 각 1 credit을 받는다.
- merge 순간 PR assignee 모두가 각 1 credit을 받는다.
- 리뷰, 생성, Draft Done은 credit을 만들지 않는다.
- Reopen은 과거 credit을 취소하지 않고 재Close는 새 credit을 만든다.
- `trackingStartedAt` 이전 완료는 나타나지 않는다.

### 20.4 알림

- 마감 없는 Issue·Draft 기본 14일, PR·리뷰 요청 기본 3일이 적용된다.
- Target date가 있으면 기간 규칙이 무시된다.
- 3일 전 임박 알림과 마감 다음 날 지연 알림이 생성된다.
- 반복은 주말·공휴일 포함 정확히 72시간 간격이다.
- 휴가가 개인별 계산과 발송 건너뛰기에 반영된다.
- 앱 완전 종료 중 알림은 다음 실행에 unread로 보인다.

### 20.5 장애와 Desktop

- GitHub 읽기 네 번째 실패 후 캐시가 유지되고 쓰기가 잠긴다.
- GitHub Status 링크가 표시되며 복구 뒤 자동 동기화된다.
- 불확실한 GitHub 쓰기가 무조건 중복 실행되지 않는다.
- X 버튼은 백그라운드 실행, 명시적 종료만 프로세스 종료로 동작한다.
- macOS·Windows 업데이트 다운로드 뒤 재시작 또는 나중에를 선택할 수 있다.

검증을 위한 임시 test harness와 fixture는 사용할 수 있지만 저장소에 영구 테스트 코드로 남기지 않는다. 각 단계 완료 전에 권한표, 로그인 gate, Webhook 멱등성, 시간 경계는 반복 가능한 임시 검증으로 확인한 뒤 제거한다.

## 21. 미결정 사항

현재 구현을 막는 미결정 사항은 없다. 이후 요구가 바뀌거나 새 범위가 생기면 이 절에 먼저 추가하고, 결정 즉시 해당 본문으로 이동한다.

## 22. 참고 자료

- [DataGSM 문서 인덱스](https://docs.datagsm.kr/llms.txt)
- [DataGSM Events](https://docs.datagsm.kr/event)
- [DataGSM Student Event payload](https://docs.datagsm.kr/event/payloads/student)
- [DataGSM Club Event payload](https://docs.datagsm.kr/event/payloads/club)
- [DataGSM OAuth PKCE](https://docs.datagsm.kr/oauth/pkce)
- [DataGSM Authorize](https://docs.datagsm.kr/oauth/http/authorize)
- [DataGSM Token Exchange](https://docs.datagsm.kr/oauth/http/token-exchange)
- [DataGSM UserInfo](https://docs.datagsm.kr/oauth/http/userinfo)
- [DataGSM 최신 Student DTO](https://github.com/themoment-team/datagsm-server/blob/master/datagsm-common/src/main/kotlin/team/themoment/datagsm/common/domain/student/dto/response/StudentResDto.kt)
- [DataGSM 내 정보](https://www.datagsm.kr/myinfo)
- [themoment-team GitHub 조직](https://github.com/themoment-team)
- [Project #19 템플릿](https://github.com/orgs/themoment-team/projects/19)
- [Project #23 예시](https://github.com/orgs/themoment-team/projects/23/views/1)
- [GitHub REST rate limits](https://docs.github.com/en/rest/using-the-rest-api/rate-limits-for-the-rest-api)
- [GitHub GraphQL rate limits](https://docs.github.com/en/graphql/overview/rate-limits-and-query-limits-for-the-graphql-api)
- [GitHub App Webhook delivery 복구](https://docs.github.com/en/webhooks/using-webhooks/automatically-redelivering-failed-deliveries-for-a-github-app-webhook)
