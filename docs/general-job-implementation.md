# 일반 직군 API 구현

기준: origin/main `6acc8e4` (2026-10-20 fetch). 계약 단일 출처: 상위 작업 디렉터리 `Doc/general-job-impl-spec/01-overview.md`, 상세 `03-api.md`, 비개발 선행 `Doc/job-family-impl-spec/03-api.md`.

- [가정] 사용자 전체 병렬 구현 요청에 따라 비개발 선행 미완료 C-3·C-5·C-7·C-8을 이 PR에서 먼저 구현한다. 병합 순서 auth → Analysis → Chat → API → Web은 유지한다.
- [가정] 기존 record 생성자 호출 호환을 위해 새 컴포넌트는 끝에 추가하고 기존 인자 생성자를 유지한다. JSON의 의미와 계약 값은 동일하다.
- [가정] 자료 분석 가능 판정과 `/profile` 변환은 AnalysisLaunchService에 모은다. 수동 재분석도 저장된 materials 전체를 사용한다.
- [가정] 옛 dataUpload는 현재 자료 API 검증·저장을 재사용한다. 직무 없음은 BACKEND, 문서 종류 PORTFOLIO, 새 file0 한 건으로 변환한다. 기존 사용자 정보를 덮어쓰는 별도 옛 저장 호출은 사용하지 않는다.
- 마이그레이션: 기존 최신 V38 확인. 비개발 V39, 일반 직군 V40 추가. 운영 DB·배포·병합은 수행하지 않는다.

검증 결과와 PR은 완료 시 기록한다.

## G-A31 시작 직무 스냅샷

최초 `prepareInterview`에서 일반 직군이면 외부 분석/테일러 호출 전에 `InterviewJobSnapshotService.capture`를 별도 짧은 트랜잭션으로 실행한다. 면접 행을 PESSIMISTIC_WRITE로 잠그고 소유자·상태·기존 tailor를 확인한다. 공유 persona를 수정하지 않고 TECH를 면접 전용으로 복제해 현재 auth major/jobTitle을 기록한다. SOLO 대표 ID 또는 MULTI 첫 링크만 교체한다. clone에 `interview_snapshot=true`, 원래 표시 이름을 `snapshot_source_name`으로 남긴다. 두 컬럼은 V40에 포함한다. 일반 선택 목록/직접 면접관 조회/새 면접에서 snapshot clone 선택은 막고, 면접 권한을 확인하는 목록·채점·Chat은 clone을 사용한다. 실제 면접관 표시 이름은 원래 이름을 유지한다.

복제본이 이미 연결됐거나 tailor가 있거나 면접이 종료됐으면 재기록하지 않는다. 외부 접수 전에 실패해 tailor가 없어도 clone은 확정값으로 남는다. 즉시 테일러·대기 재개·재시도는 snapshot major/jobTitle을 읽는다. 질문 세트 출처 직무와 snapshot 직무가 다르면 409로 막고 신규 PENDING을 만들지 않는다. 질문 풀의 면접별 세트 예약은 기존 멱등 규칙대로 남는다. IT 기존 경로는 유지한다.

## 계약 대조와 검증

- 01 §3.1: JobRole·Major 15개(개발3·비개발6·일반6), 일반 GitHub 숨김·보완 없음·자소서 허용.
- 01 §3.3: WORK_SAMPLE은 일반에서만 허용. 공통 RESUME·CAREER_DESCRIPTION 및 PORTFOLIO와 합쳐 일반 4종.
- 01 §3.4: 직무명 30자·자소서 5개/문항200/답2000, 일반은 이력서 외 자료 또는 자소서 필수. Reason 신규6개 대응.
- 신규 profile DTO는 jobRole/documents/githubUrls/supplements/callbackUrl, 일반만 jobTitle/coverLetters. IT에는 두 새 키를 생략한다. 비개발 선행 분석 차단·skipped 제거.
- Flyway V1~V40을 격리 PostgreSQL 16에 적용하고 스냅샷 생성·동시 시작·원본/완료 면접 보존을 검증했다. Testcontainers 대신 기존 프로젝트 SpringBootTest 및 일회용 Docker PostgreSQL을 사용했다.
- `JAVA_HOME` Java21, DB `127.0.0.1:55439`, 콜백 `localhost:18039`, 외부 auth/AI/Chat는 `127.0.0.1:9`, 더미 S3 자격으로 `./gradlew test`: **948개 통과, 실패0·skip0**.
- 자체 리뷰와 루트/독립 리뷰 반영: 콜백 직무명 소실, 공유 persona 변경 위험, 직무 불일치의 고립 PENDING, OSIV stale FK 재복제 위험을 수정했다. 코드 리뷰 전용 실행 도구는 제공되지 않아 저장소 인계의 자체 리뷰 기준을 적용했다.
- `git diff --check` 통과. 최종 fetch 기준 origin/main은 `6acc8e4`로 동일.
- 이슈: https://github.com/Alpha-origin/Repit-api-server/issues/113. 브랜치는 상위 작업에서 지정한 `codex/general-job-api`, PR 제목은 저장소 템플릿의 브랜치명 동일 규칙을 따른다.

## 남은 통합 검증

Auth·Analysis·Chat 선행 PR 병합 뒤 일반6직군 E2E, 유료 모델 실제 분석·이미지 PDF·꼬리질문·채점 품질 검증은 별도다. 이번 작업은 API 코드·격리 DB 테스트까지이며 배포·병합·운영 DB 변경은 하지 않았다.
