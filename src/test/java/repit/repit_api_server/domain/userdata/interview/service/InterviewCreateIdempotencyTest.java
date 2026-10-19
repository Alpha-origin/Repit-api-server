package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import repit.repit_api_server.domain.company.dto.CompanyBadge;
import repit.repit_api_server.domain.company.dto.CompanySnapshot;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.CompanySourceEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.entity.enums.CompanySourceType;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.domain.company.repository.CompanySourceRepository;
import repit.repit_api_server.domain.company.service.CompanySnapshotFactory;
import repit.repit_api_server.domain.userdata.interview.dto.request.CreateInterviewRequest;
import repit.repit_api_server.domain.userdata.interview.entity.enums.AnswerMode;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.dto.response.InterviewResponse;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.InterviewTone;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.global.error.ErrorCodes;
import repit.repit_api_server.global.exception.BusinessException;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 최종 면접 생성의 중복 방지·원자성·기업 잠금(02 §5, §3-3)을 실제 PostgreSQL에 대고 본다.
 *
 * <p>유일 제약과 트랜잭션 롤백, 행 잠금은 테스트 더블로 확인할 수 없다. 요청마다 자기 트랜잭션에서 돌도록 테스트 트랜잭션으로
 * 감싸지 않고, 끝나면 이 테스트가 만든 사용자·기업·면접관을 지운다.
 */
@SpringBootTest
class InterviewCreateIdempotencyTest {

    @Autowired
    private InterviewCreateService createService;
    @Autowired
    private InterviewService interviewService;
    @Autowired
    private CompanySnapshotFactory companySnapshotFactory;
    @Autowired
    private InterviewRepository interviewRepository;
    @Autowired
    private PersonaRepository personaRepository;
    @Autowired
    private CompanyRepository companyRepository;
    @Autowired
    private CompanySourceRepository companySourceRepository;
    @Autowired
    private CompanyFactRepository companyFactRepository;
    @Autowired
    private TransactionTemplate transactionTemplate;
    @Autowired
    private JdbcTemplate jdbcTemplate;

    // 다른 테스트·로컬 데이터와 겹치지 않는 사용자와 이름.
    private final long userId = ThreadLocalRandom.current().nextLong(1_000_000_000L, 2_000_000_000L);
    private final long otherUserId = userId + 1;
    private final String suffix = UUID.randomUUID().toString().substring(0, 8);
    private final String companyCode = "idem-" + suffix;

    private Long techId;
    private Long hrId;
    private Long companyId;

    @BeforeEach
    void setUp() {
        techId = persona("기술-" + suffix, Role.TECH);
        hrId = persona("인사-" + suffix, Role.HR);
        companyId = company(companyCode, true);
    }

    @AfterEach
    void cleanUp() {
        // 요청 키는 면접을 지우면 함께 지워진다(ON DELETE CASCADE).
        jdbcTemplate.update("delete from interview where user_id in (?, ?)", userId, otherUserId);
        jdbcTemplate.update("delete from company_fact where company_id in (select company_id from company where code like ?)",
                companyCode + "%");
        jdbcTemplate.update("delete from company_source where company_id in (select company_id from company where code like ?)",
                companyCode + "%");
        jdbcTemplate.update("delete from company where code like ?", companyCode + "%");
        jdbcTemplate.update("delete from persona where name like ?", "%-" + suffix);
    }

    private Long persona(String name, Role role) {
        return personaRepository.save(PersonaEntity.builder()
                .personaName(name)
                .role(role)
                .major(Major.BACKEND)
                .type(Type.REALISTIC)
                .tone(InterviewTone.GENTLE)
                .level(Level.NORMAL)
                .career(5)
                .gender(Gender.FEMALE)
                .build()).getPersonaId();
    }

    /** 검수를 마친 공개 기업. talent가 false면 인재상이 없어 실행 스냅샷을 만들 수 없다. */
    private Long company(String code, boolean talent) {
        return transactionTemplate.execute(status -> {
            CompanyEntity company = companyRepository.save(CompanyEntity.builder()
                    .code(code)
                    .displayName("멱등커머스")
                    .legalName("멱등커머스 주식회사")
                    .aliases(List.of())
                    .category(CompanyCategory.CONSUMER)
                    .status(CompanyStatus.PUBLISHED)
                    .interviewerBrief("멱등커머스는 가상의 기업이다.")
                    .dataAsOf(LocalDate.parse("2026-10-15"))
                    .reviewedBy("검수자")
                    .reviewedAt(LocalDate.parse("2026-10-16"))
                    .contentHash("0".repeat(64))
                    .build());
            Long sourceId = companySourceRepository.save(CompanySourceEntity.builder()
                    .companyId(company.getCompanyId())
                    .sourceKey("about")
                    .sourceType(CompanySourceType.CAREER_PAGE)
                    .url("https://example.invalid/about")
                    .collectedAt(LocalDate.parse("2026-10-15"))
                    .build()).getSourceId();
            List<CompanyFactEntity> facts = new ArrayList<>();
            facts.add(fact(company.getCompanyId(), sourceId, CompanyFactKind.BUSINESS, "멱등마켓", 0));
            if (talent) {
                facts.add(fact(company.getCompanyId(), sourceId, CompanyFactKind.TALENT, "오너십", 1));
            }
            companyFactRepository.saveAll(facts);
            return company.getCompanyId();
        });
    }

    private static CompanyFactEntity fact(Long companyId, Long sourceId, CompanyFactKind kind, String title, int order) {
        return CompanyFactEntity.builder()
                .companyId(companyId)
                .kind(kind)
                .title(title)
                .content(title + "에 대한 설명")
                .positiveSignals(List.of())
                .concernSignals(List.of())
                .sourceId(sourceId)
                .sortOrder(order)
                .build();
    }

    private CreateInterviewRequest solo(Long companyId) {
        return new CreateInterviewRequest(techId, null, null, companyId);
    }

    private CreateInterviewRequest multi(Long companyId) {
        return new CreateInterviewRequest(null, null, List.of(hrId, techId), companyId);
    }

    private int interviews(long user) {
        return jdbcTemplate.queryForObject("select count(*) from interview where user_id = ?", Integer.class, user);
    }

    private int keys(long user) {
        return jdbcTemplate.queryForObject("select count(*) from interview_create_request where user_id = ?",
                Integer.class, user);
    }

    private CompanySnapshot snapshotOf(Long interviewId) {
        return interviewRepository.findById(interviewId).orElseThrow().getCompanySnapshot();
    }

    private static void assertError(Runnable call, HttpStatus status, String code) {
        assertThatThrownBy(call::run)
                .isInstanceOf(BusinessException.class)
                .satisfies(e -> {
                    assertThat(((BusinessException) e).getStatus()).isEqualTo(status);
                    assertThat(((BusinessException) e).getCode()).isEqualTo(code);
                });
    }

    @Test
    void 두_면접유형의_답변방식이_생성_재전송_완료후_단건_목록에_보존된다() {
        for (boolean multiMode : List.of(false, true)) {
            for (AnswerMode mode : AnswerMode.values()) {
                CreateInterviewRequest request = multiMode ? multi(null) : solo(null);
                request.setAnswerMode(mode);
                String key = UUID.randomUUID().toString();
                InterviewResponse created = createService.create(userId, key, request);
                assertThat(created.getAnswerMode()).isEqualTo(mode);
                assertThat(createService.create(userId, key, request).getInterviewId()).isEqualTo(created.getInterviewId());
                assertThat(createService.create(userId, key, request).getAnswerMode()).isEqualTo(mode);
                assertThat(jdbcTemplate.queryForObject("select answer_mode from interview where interview_id = ?",
                        String.class, created.getInterviewId())).isEqualTo(mode.name());
                jdbcTemplate.update("update interview set status = 'COMPLETED' where interview_id = ?", created.getInterviewId());
                InterviewResponse loaded = interviewService.getInterviewById(userId, created.getInterviewId());
                assertThat(loaded.getAnswerMode()).isEqualTo(mode);
                assertThat(loaded.getStatus()).isEqualTo(Status.COMPLETED);
                assertThat(interviewService.getAllInterviewsByUserId(userId)).anySatisfy(item -> {
                    assertThat(item.getInterviewId()).isEqualTo(created.getInterviewId());
                    assertThat(item.getAnswerMode()).isEqualTo(mode);
                });
                request.setAnswerMode(mode == AnswerMode.TEXT ? AnswerMode.VOICE : AnswerMode.TEXT);
                assertError(() -> createService.create(userId, key, request),
                        HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_KEY_REUSED);
            }
        }
    }

    @Test
    void 이전_답변방식_없는_요청키의_해시와_null_기록을_유지한다() {
        CreateInterviewRequest request = solo(null);
        String key = UUID.randomUUID().toString();
        // 새 필드가 생기기 전 canonical 본문으로 저장한 요청 키도 같은 면접을 복구해야 한다.
        String canonical = "{\"companyId\":null,\"mode\":\"SOLO\",\"personaId\":" + techId + "}";
        String oldHash;
        try {
            oldHash = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
        InterviewResponse created = createService.create(userId, key, request);
        jdbcTemplate.update("update interview_create_request set request_hash = ? where user_id = ? and idempotency_key = ?::uuid",
                oldHash, userId, key);
        assertThat(createService.create(userId, key, request).getInterviewId()).isEqualTo(created.getInterviewId());
        assertThat(interviewService.getInterviewById(userId, created.getInterviewId()).getAnswerMode()).isNull();
        request.setAnswerMode(AnswerMode.TEXT);
        assertError(() -> createService.create(userId, key, request), HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_KEY_REUSED);
    }

    @Test
    void 같은_키_같은_설정이면_처음_만든_면접을_돌려준다() {
        String key = UUID.randomUUID().toString();

        InterviewResponse first = createService.create(userId, key, solo(companyId));
        InterviewResponse again = createService.create(userId, key, solo(companyId));

        assertThat(again.getInterviewId()).isEqualTo(first.getInterviewId());
        assertThat(again.getCompany()).isEqualTo(new CompanyBadge(companyId, "멱등커머스"));
        assertThat(interviews(userId)).isEqualTo(1);
        assertThat(keys(userId)).isEqualTo(1);
        assertThat(snapshotOf(first.getInterviewId()).facts()).extracting(CompanySnapshot.Fact::title)
                .containsExactly("멱등마켓", "오너십");
    }

    @Test
    void N대1도_같은_키면_같은_면접과_면접관_순서를_돌려준다() {
        String key = UUID.randomUUID().toString();

        InterviewResponse first = createService.create(userId, key, multi(companyId));
        InterviewResponse again = createService.create(userId, key, multi(companyId));

        assertThat(again.getInterviewId()).isEqualTo(first.getInterviewId());
        assertThat(again.getPersonaIds()).containsExactly(techId, hrId);
        assertThat(jdbcTemplate.queryForObject("select count(*) from interview_persona where interview_id = ?",
                Integer.class, first.getInterviewId())).isEqualTo(2);
    }

    @Test
    void 같은_키로_다른_설정을_보내면_409이고_처음_면접은_그대로다() {
        String key = UUID.randomUUID().toString();
        InterviewResponse first = createService.create(userId, key, solo(companyId));

        assertError(() -> createService.create(userId, key, solo(null)),
                HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_KEY_REUSED);
        assertError(() -> createService.create(userId, key, multi(companyId)),
                HttpStatus.CONFLICT, ErrorCodes.IDEMPOTENCY_KEY_REUSED);

        assertThat(interviews(userId)).isEqualTo(1);
        assertThat(snapshotOf(first.getInterviewId())).isNotNull();
    }

    @Test
    void 다른_사용자는_같은_키로도_따로_만든다() {
        String key = UUID.randomUUID().toString();

        InterviewResponse mine = createService.create(userId, key, solo(companyId));
        InterviewResponse theirs = createService.create(otherUserId, key, solo(companyId));

        assertThat(theirs.getInterviewId()).isNotEqualTo(mine.getInterviewId());
        assertThat(theirs.getUserId()).isEqualTo(otherUserId);
    }

    @Test
    void 키가_없으면_예전처럼_요청마다_만든다() {
        createService.create(userId, null, solo(companyId));
        createService.create(userId, null, solo(companyId));

        assertThat(interviews(userId)).isEqualTo(2);
        assertThat(keys(userId)).isZero();
    }

    /** 시작 버튼 연타·네트워크 재시도. 유일 제약이 하나만 남기고, 진 요청은 롤백된 뒤 같은 면접을 받는다. */
    @Test
    void 같은_키가_동시에_와도_면접은_하나다() throws Exception {
        String key = UUID.randomUUID().toString();
        int concurrent = 6;
        ExecutorService pool = Executors.newFixedThreadPool(concurrent);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<InterviewResponse>> results = new ArrayList<>();
            for (int i = 0; i < concurrent; i++) {
                results.add(pool.submit(() -> {
                    start.await();
                    return createService.create(userId, key, multi(companyId));
                }));
            }
            start.countDown();

            List<Long> ids = new ArrayList<>();
            for (Future<InterviewResponse> result : results) {
                ids.add(result.get(30, TimeUnit.SECONDS).getInterviewId());
            }
            assertThat(ids).containsOnly(ids.getFirst());
        } finally {
            pool.shutdownNow();
        }
        assertThat(interviews(userId)).isEqualTo(1);
        assertThat(keys(userId)).isEqualTo(1);
        // 진 요청이 넣었던 면접관 명단도 롤백됐다.
        assertThat(jdbcTemplate.queryForObject("""
                select count(*) from interview_persona ip join interview i on i.interview_id = ip.interview_id
                 where i.user_id = ?""", Integer.class, userId)).isEqualTo(2);
    }

    /** 성공한 요청의 재전송은 기업 검증을 다시 하지 않는다. 새 키로는 숨긴 기업을 고를 수 없다. */
    @Test
    void 기업이_숨겨진_뒤에도_같은_키_재전송은_저장된_면접을_돌려준다() {
        String key = UUID.randomUUID().toString();
        InterviewResponse first = createService.create(userId, key, solo(companyId));
        jdbcTemplate.update("update company set status = 'HIDDEN' where company_id = ?", companyId);

        InterviewResponse again = createService.create(userId, key, solo(companyId));

        assertThat(again.getInterviewId()).isEqualTo(first.getInterviewId());
        assertThat(again.getCompany()).isEqualTo(new CompanyBadge(companyId, "멱등커머스"));
        assertError(() -> createService.create(userId, UUID.randomUUID().toString(), solo(companyId)),
                HttpStatus.NOT_FOUND, ErrorCodes.COMPANY_UNAVAILABLE);
        assertThat(interviews(userId)).isEqualTo(1);
    }

    /** 검증에 실패한 요청은 면접도 키도 남기지 않는다. 고친 뒤 같은 키로 다시 보내면 만들어진다. */
    @Test
    void 실패한_생성은_면접도_키도_남기지_않는다() {
        String key = UUID.randomUUID().toString();
        jdbcTemplate.update("update company set status = 'HIDDEN' where company_id = ?", companyId);

        assertError(() -> createService.create(userId, key, multi(companyId)),
                HttpStatus.NOT_FOUND, ErrorCodes.COMPANY_UNAVAILABLE);
        assertThatThrownBy(() -> createService.create(userId, key, new CreateInterviewRequest(null, null,
                List.of(hrId), null))).isInstanceOf(BusinessException.class);
        assertThat(interviews(userId)).isZero();
        assertThat(keys(userId)).isZero();

        jdbcTemplate.update("update company set status = 'PUBLISHED' where company_id = ?", companyId);
        InterviewResponse created = createService.create(userId, key, multi(companyId));
        assertThat(created.getCompany()).isEqualTo(new CompanyBadge(companyId, "멱등커머스"));
        assertThat(interviews(userId)).isEqualTo(1);
    }

    /** 공개 기업인데 스냅샷을 만들 수 없으면 503이다. 기업 없는 면접으로 바꿔 만들지 않는다. */
    @Test
    void 실행_스냅샷을_만들_수_없으면_503이고_면접을_만들지_않는다() {
        Long broken = company(companyCode + "-broken", false);

        assertError(() -> createService.create(userId, UUID.randomUUID().toString(), solo(broken)),
                HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.COMPANY_CONTEXT_UNAVAILABLE);
        assertError(() -> createService.create(userId, null, multi(broken)),
                HttpStatus.SERVICE_UNAVAILABLE, ErrorCodes.COMPANY_CONTEXT_UNAVAILABLE);
        assertThat(interviews(userId)).isZero();
        assertThat(keys(userId)).isZero();
    }

    /** 생성 뒤 기업 정보가 바뀌어도 그 면접은 만들 때의 스냅샷을 그대로 둔다. */
    @Test
    void 기업이_바뀌어도_만든_면접의_스냅샷은_그대로다() {
        InterviewResponse created = createService.create(userId, null, solo(companyId));

        jdbcTemplate.update("update company set display_name = '새이름', interviewer_brief = '새 요약' where company_id = ?",
                companyId);
        jdbcTemplate.update("update company_fact set title = '새인재상' where company_id = ? and kind = 'TALENT'", companyId);

        CompanySnapshot snapshot = snapshotOf(created.getInterviewId());
        assertThat(snapshot.displayName()).isEqualTo("멱등커머스");
        assertThat(snapshot.interviewerBrief()).isEqualTo("멱등커머스는 가상의 기업이다.");
        assertThat(snapshot.facts()).extracting(CompanySnapshot.Fact::title).containsExactly("멱등마켓", "오너십");
    }

    /**
     * 가져오기가 기업 행을 FOR UPDATE로 잡고 요약·fact를 바꾸는 동안 면접 생성은 기다린다. 끝난 뒤 만든 스냅샷은 바뀐 요약과
     * 바뀐 fact를 함께 담는다 — 이전 요약과 새 fact가 섞이지 않는다.
     */
    @Test
    void 가져오기가_기업을_고치는_동안_스냅샷은_기다렸다가_새_값만_담는다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<?> importing = pool.submit(() -> transactionTemplate.executeWithoutResult(status -> {
                CompanyEntity company = companyRepository.findForUpdateByCode(companyCode).orElseThrow();
                Long sourceId = companySourceRepository.findAll().stream()
                        .filter(source -> source.getCompanyId().equals(companyId))
                        .findFirst().orElseThrow().getSourceId();
                company.setInterviewerBrief("바뀐 요약");
                companyFactRepository.deleteAllByCompanyId(companyId);
                companyFactRepository.saveAllAndFlush(List.of(
                        fact(companyId, sourceId, CompanyFactKind.BUSINESS, "새마켓", 0),
                        fact(companyId, sourceId, CompanyFactKind.TALENT, "새인재상", 1)));
                locked.countDown();
                await(release);
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();

            Future<InterviewResponse> creating = pool.submit(() -> createService.create(userId, null, solo(companyId)));
            Thread.sleep(500);
            assertThat(creating.isDone()).as("가져오기가 끝나기 전에 스냅샷을 만들면 안 된다").isFalse();

            release.countDown();
            importing.get(10, TimeUnit.SECONDS);
            CompanySnapshot snapshot = snapshotOf(creating.get(10, TimeUnit.SECONDS).getInterviewId());
            assertThat(snapshot.interviewerBrief()).isEqualTo("바뀐 요약");
            assertThat(snapshot.facts()).extracting(CompanySnapshot.Fact::title).containsExactly("새마켓", "새인재상");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    /** 반대로 스냅샷을 만드는 트랜잭션이 기업을 읽고 있으면 가져오기가 기다린다. 스냅샷은 이전 값만 담는다. */
    @Test
    void 스냅샷을_만드는_동안_가져오기는_기다린다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch read = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            Future<CompanySnapshot> snapshotting = pool.submit(() -> transactionTemplate.execute(status -> {
                CompanySnapshot snapshot = companySnapshotFactory.create(companyId);
                read.countDown();
                await(release);
                return snapshot;
            }));
            assertThat(read.await(10, TimeUnit.SECONDS)).isTrue();

            Future<?> importing = pool.submit(() -> transactionTemplate.executeWithoutResult(status ->
                    companyRepository.findForUpdateByCode(companyCode).orElseThrow().setInterviewerBrief("바뀐 요약")));
            Thread.sleep(500);
            assertThat(importing.isDone()).as("스냅샷이 끝나기 전에 기업을 고치면 안 된다").isFalse();

            release.countDown();
            assertThat(snapshotting.get(10, TimeUnit.SECONDS).interviewerBrief()).isEqualTo("멱등커머스는 가상의 기업이다.");
            importing.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("테스트가 풀어 주지 않았습니다.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
