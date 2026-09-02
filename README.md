# sourcing-pipeline

해외 쇼핑몰 상품을 **수집 → 정규화 → 오픈마켓 연동**까지 흘려보내는 파이프라인.

커머스 상품 소싱 시스템을 5년간 운영하며 반복해서 부딪힌 문제들 — 수집이 조용히 실패하는 것,
같은 상품이 중복 등록되는 것, 브라우저 탭이 죽어 큐가 멈추는 것 — 을 처음부터 다시 설계한 개인 프로젝트다.

> 1단계(수집기)가 동작하는 상태이며, 2단계(오픈마켓 연동)는 진행 중이다. [로드맵](#로드맵) 참고.

## 무엇을 푸는가

상품 상세 페이지는 로그인·봇 차단·동적 렌더링 때문에 서버에서 직접 긁기 어렵다.
그래서 **실제 브라우저(크롬 확장)가 수집을 수행하고, 서버는 큐와 정규화를 맡는다.**
이 구조에서 진짜 어려운 부분은 크롤링이 아니라 **신뢰성**이다.

| 문제 | 이 프로젝트의 답 |
|---|---|
| 탭이 죽으면 작업이 영원히 진행중으로 남는다 | lease 기반 점유 + 만료분 자동 회수 |
| 대상 사이트에 부하를 주면 차단당한다 | 서버가 동시 실행 수를 통제(확장이 아니라 서버가 결정) |
| 같은 상품이 중복 등록된다 | (사이트, 상품ID) 유니크 + upsert 로 재수집 멱등 |
| 일시적 실패와 영구적 실패가 섞인다 | 네트워크 실패는 지수 백오프 재시도, 구조 오류는 즉시 확정 실패 |
| 정규화 규칙을 고치면 과거 수집분을 못 살린다 | 원본 페이로드를 그대로 보존해 재처리 가능 |
| 사이트마다 데이터 구조가 다르다 | `SiteAdapter` 구현체 추가만으로 신규 사이트 지원 |

## 구조

```
┌──────────────────┐   ① claim         ┌─────────────────────────┐
│  크롬 확장 (MV3) │ ◀───────────────  │   Spring Boot 서버      │
│                  │                   │                         │
│  background.js   │   ② 탭 열고 추출  │  CollectJob (상태기계)  │
│  extractor.js    │ ─────────────▶    │  ├ lease/동시성 제어    │
│                  │   ③ result        │  ├ 지수 백오프 재시도   │
│  popup (현황)    │ ─────────────▶    │  └ 만료 lease 회수      │
└──────────────────┘                   │                         │
                                       │  SiteAdapter (정규화)   │
                                       │  ├ TaobaoAdapter        │
                                       │  └ GenericAdapter       │
                                       │        ↓                │
                                       │  Product / ProductOption│
                                       │  RawProduct (원본 보존) │
                                       └─────────────────────────┘
```

작업 상태 전이:

```
PENDING ──claim──▶ RUNNING ──성공──▶ SUCCEEDED
   ▲                  │
   │                  ├── 일시 실패(시도 < 상한) ──▶ 백오프 후 PENDING
   └──────────────────┤
                      ├── 일시 실패(시도 = 상한) ──▶ FAILED
                      ├── 구조 오류(정규화 실패) ──▶ FAILED (재시도 안 함)
                      └── lease 만료(워커 사망) ──▶ 회수 후 PENDING
```

## 설계에서 신경 쓴 것

**동시성 상한은 서버가 가진다.**
확장에서 세면 창을 두 개 띄우는 순간 상한이 무너진다. 서버가 `RUNNING` 개수를 보고
`claim` 에 204를 돌려주면 확장은 대기한다. 확장은 자기가 몇 개를 돌리는지 알 필요가 없다.

**실패를 예외로 흘리지 않는다.**
초기 구현은 정규화 실패 시 예외를 던졌는데, 같은 트랜잭션에서 기록한 `FAILED` 상태가
함께 롤백되어 작업이 `RUNNING` 으로 남고 동시 실행 슬롯을 영구 점유했다.
지금은 `SubmitOutcome.Rejected` 반환값으로 바꿔 상태가 반드시 커밋되게 했다.
([관련 커밋](../../commit/0fcd47b) · 이 결함을 잡는 회귀 테스트는 일부러 `@Transactional` 없이 작성했다)

**원본을 버리지 않는다.**
`RawProduct` 에 수집 원본을 그대로 남긴다. 정규화 규칙이 바뀌어도 과거 수집분을 다시 돌릴 수 있고,
"왜 이 값이 이렇게 들어왔는가"를 사후에 추적할 수 있다.

**신규 사이트 추가에 기존 코드를 건드리지 않는다.**
`SiteAdapter` 구현체를 하나 추가하면 `AdapterRegistry` 가 자동으로 등록한다.

## 실행

필요: JDK 21

```bash
./gradlew bootRun
```

동작 확인 (서버만으로 파이프라인 전체를 검증할 수 있다):

```bash
# 1. 수집 요청 등록
curl -X POST http://localhost:8080/api/v1/jobs \
  -H 'Content-Type: application/json' \
  -d '{"siteCode":"taobao","externalId":"888777","sourceUrl":"https://item.taobao.com/item.htm?id=888777"}'

# 2. 워커가 작업을 점유
curl -X POST 'http://localhost:8080/api/v1/jobs/claim?workerId=worker-A'

# 3. 수집 결과 제출 → 정규화 + 저장
curl -X POST http://localhost:8080/api/v1/jobs/1/result \
  -H 'Content-Type: application/json' \
  -d '{"workerId":"worker-A","payload":{"goods":{"itemId":"888777","title":"테스트 상품"},
       "skus":[{"price":"89.00","props":[{"name":"색상","value":"블랙"}]},
               {"price":"75.50","props":[{"name":"색상","value":"아이보리"}]}]}}'
# → {"id":1,"title":"테스트 상품","optionCount":2}   대표가는 SKU 최저가 75.50 으로 잡힌다

# 4. 큐 현황
curl http://localhost:8080/api/v1/jobs/stats
# → {"pending":0,"running":0,"succeeded":1,"failed":0,"maxConcurrent":3}
```

확장까지 포함해 확인하려면 `chrome://extensions` → 개발자 모드 → `extension/` 폴더를 로드한 뒤,
팝업에서 **시작**을 누르고 아래 데모 상품을 큐에 넣으면 된다.

```bash
curl -X POST http://localhost:8080/api/v1/jobs -H 'Content-Type: application/json' \
  -d '{"siteCode":"generic","externalId":"demo-1001","sourceUrl":"http://localhost:8080/demo/product.html"}'
```

## API

| 메서드 | 경로 | 설명 |
|---|---|---|
| POST | `/api/v1/jobs` | 수집 요청 등록 (같은 상품이면 기존 작업 반환) |
| POST | `/api/v1/jobs/claim?workerId=` | 작업 점유. 없거나 상한이면 204 |
| POST | `/api/v1/jobs/{id}/result` | 결과 제출. 정규화 실패 시 422 |
| POST | `/api/v1/jobs/{id}/failure` | 실패 보고 (백오프 재시도 예약) |
| GET | `/api/v1/jobs/stats` | 큐 현황 |

## 설정

`application.yml` 의 `collector` 항목으로 운영 파라미터를 조절한다.

```yaml
collector:
  max-concurrent-jobs: 3    # 동시 수집 상한
  claim-timeout: 3m         # 이 시간 안에 결과가 없으면 죽은 워커로 간주
  max-attempts: 3           # 재시도 상한
  retry-base-delay: 30s     # 30s → 60s → 120s 로 증가
```

## 테스트

```bash
./gradlew test
```

22건. 큐 상태 전이(백오프 증가·시도 상한·lease 회수), 정규화 규칙(SKU 최저가·중복 옵션 접기),
수집 플로우(멱등 등록·동시성 상한·워커 소유권), 그리고 트랜잭션 경계 회귀 테스트를 다룬다.

## 기술 스택

Java 21 · Spring Boot 4.1 · Spring Data JPA · H2 · Gradle · Chrome Extension MV3

## 로드맵

- [x] 1단계 — 수집기: 큐·재시도·정규화·크롬 확장 워커
- [ ] 2단계 — 오픈마켓 연동: 마켓별 어댑터, 업로드 큐, 실패 재전송, 등록 상태 추적
- [ ] 수집 현황 대시보드
- [ ] PostgreSQL + Flyway 로 전환
