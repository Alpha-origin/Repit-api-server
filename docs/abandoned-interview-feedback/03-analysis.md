# 03. Analysis v2 서버 구현 명세

- 대상: `Alpha-origin/repit-analysis-server-v2`
- 기준: `main@3b2103dbcaab2b6a3f33402eae6a3cd19d0a356b`
- 선행: [공통 계약](00-common-contracts.md)
- 상태: 구현 설계안. 계약의 저장소 내 단일 레퍼런스인 `docs/api.md`도 구현 시 함께 갱신한다.

## 1. 변경 위치

| 위치 | 역할 | 변경 |
|---|---|---|
| `src/app/inbound/http/interview_feedback/solo/dto.py`의 `FeedbackRequest` | SOLO HTTP 요청 검증 | 완료 상태 필수 검증 |
| `src/app/inbound/http/interview_feedback/multi/dto.py`의 `FeedbackMultiHttpRequest` | MULTI HTTP 요청 검증 | 같은 검증 |
| 같은 디렉터리의 각 `router.py` | UUID 발급·core DTO 변환·BackgroundTasks 등록 | 검증 통과 후에만 기존 접수 |
| `docs/api.md` | API 계약 | 필수 필드·동기 422·호환 순서 문서화 |
| HTTP DTO·router 테스트 | 접수 경계 검증 | 부작용 0회·완료 회귀 검증 |

근거: [SOLO DTO](https://github.com/Alpha-origin/repit-analysis-server-v2/blob/3b2103dbcaab2b6a3f33402eae6a3cd19d0a356b/src/app/inbound/http/interview_feedback/solo/dto.py), [MULTI router](https://github.com/Alpha-origin/repit-analysis-server-v2/blob/3b2103dbcaab2b6a3f33402eae6a3cd19d0a356b/src/app/inbound/http/interview_feedback/multi/router.py). 현재 두 router는 HTTP DTO를 받은 다음 UUID를 발급하고 `background_tasks.add_task(dispatcher.execute, ...)`를 호출한다.

## 2. 수신 경계 검증

두 최상위 HTTP DTO에 다음 의미의 필드를 추가한다.

```python
interview_status: Literal["COMPLETED"] = Field(..., alias="interviewStatus")
```

[가정] 기존 CamelModel alias 규칙을 확인한 뒤 위와 같은 필수 Literal로 구현한다. Wire 이름은 반드시 `interviewStatus`다. 누락을 기본값으로 보정하지 않으며 별도 작업을 발급한 다음 비동기 실패로 처리하지 않는다.

| 값 | 동기 응답 | 작업 부작용 |
|---|---|---|
| `"COMPLETED"` + 기존 유효 payload | 기존 202 + jobId | 기존 dispatcher 실행 |
| 필드 누락, null | 422 | 0회 |
| `"ABANDONED"`, `"IN_PROGRESS"` | 422 | 0회 |
| 알 수 없는 값·소문자·다른 JSON 타입 | 422 | 0회 |

검증 실패는 UUID 발급 전에 종료해야 한다. BackgroundTasks 등록, 채점 LLM, companyFit 평가, 결과 콜백 모두 0회여야 한다. 기존 FastAPI 422 표현을 유지한다. 인증·내부 접근 제어를 완화하지 않는다.

## 3. 기존 채점 경로 보존

상태는 HTTP 접수 자격만 나타낸다. `FeedbackSoloRequest`/`FeedbackMultiRequest` core DTO, 프롬프트, 점수·의도·문항 조립 로직으로 전달할 필요가 없다. API DB를 조회하거나 별도 상태 저장소를 만들지 않는다.

- 질문·답변·intention·문항 ID 매칭·누락 문항 오류 정책 유지.
- SOLO·MULTI의 미답변 처리, 면접관별 평가, 기존 인원 호환 유지.
- 기업 없는 면접의 기본 채점 유지.
- 기업 있는 면접의 company 문맥·근거·companyFit·점수 분리 유지.
- 202 이후 기존 성공·실패 콜백 형식과 전달 정책 유지.
- `/analysis/audio`, `/analysis/video`에는 이번 필드를 추가하지 않는다. API가 각 미디어 요청 직전 자격을 검사한다.

이미 접수된 과거 작업의 취소 API·콜백 상태 재조회는 추가하지 않는다. 과거 작업 결과의 저장·노출 차단은 API 책임이다.

## 4. 호환 순서

1. API에서 모든 텍스트 피드백 요청에 DB 기반 `interviewStatus`를 보낸다.
2. 자동·수동·재시도·모든 인스턴스에서 필드 전송을 확인한다.
3. 그 뒤 Analysis의 필수 검증을 적용한다.

엄격 검증 적용 후 구 API로 단독 되돌리면 정상 완료 요청도 422가 된다. 이때 누락 허용 기본값을 넣지 말고 필드를 보내는 API 버전을 유지하거나 호환 버전 쌍으로 복구한다. 실제 배포 실행은 별도 승인 범위다.

## 5. 구현 후 검증 기준

SOLO/MULTI 각각 유효한 기존 payload에서 상태 필드만 바꿔 검증한다. 잘못된 questions 때문에 우연히 422가 되는 테스트로 대체하지 않는다.

- 각 불허 값에서 422와 UUID·task 등록·dispatcher·LLM·companyFit·콜백 0회 확인.
- COMPLETED에서 기존 202와 1회 task 등록·기존 결과 콜백 확인.
- 기업 있음/없음, 정상·미답변·실패 처리 회귀 유지.
- HTTP 문서·예제·fixtures에 필수 필드 반영.

구현 후 저장소 기준 명령: `uv run ruff check && uv run mypy src && uv run pytest`. 이번 문서 작업에서는 실행하지 않는다.
