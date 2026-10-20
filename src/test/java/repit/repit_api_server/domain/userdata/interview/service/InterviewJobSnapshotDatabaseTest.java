package repit.repit_api_server.domain.userdata.interview.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;
import repit.repit_api_server.domain.userdata.interview.entity.InterviewEntity;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;
import repit.repit_api_server.domain.userdata.interview.repository.InterviewRepository;
import repit.repit_api_server.domain.userdata.persona.entity.PersonaEntity;
import repit.repit_api_server.domain.userdata.persona.entity.enums.*;
import repit.repit_api_server.domain.userdata.persona.repository.PersonaRepository;
import repit.repit_api_server.global.auth.AuthUser;
import repit.repit_api_server.global.response.UserResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class InterviewJobSnapshotDatabaseTest {
    @Autowired InterviewJobSnapshotService snapshots;
    @Autowired InterviewRepository interviews;
    @Autowired PersonaRepository personas;
    @Autowired org.springframework.transaction.support.TransactionTemplate transactions;
    @jakarta.persistence.PersistenceContext jakarta.persistence.EntityManager entityManager;

    AuthUser user(String title) {
        var user = new UserResponse();
        ReflectionTestUtils.setField(user, "id", 87654321L);
        ReflectionTestUtils.setField(user, "major", "MAJOR_GEN_MARKETING");
        ReflectionTestUtils.setField(user, "jobTitle", title);
        return new AuthUser(user, "local-test");
    }

    @Test void 동시_최초_시작은_한_복제본만_확정하고_원본과_과거_면접을_보존한다() throws Exception {
        PersonaEntity source = personas.saveAndFlush(PersonaEntity.builder().personaName("snapshot-test-" + UUID.randomUUID())
                .role(Role.TECH).major(Major.GEN_MARKETING).jobTitle("옛 직무명")
                .type(Type.REALISTIC).tone(InterviewTone.GENTLE).level(Level.NORMAL).gender(Gender.MALE).build());
        InterviewEntity oldInterview = interviews.saveAndFlush(InterviewEntity.builder().userId(87654321L)
                .personaId(source.getPersonaId()).sessionId(UUID.randomUUID().toString()).status(Status.COMPLETED).build());
        InterviewEntity current = interviews.saveAndFlush(InterviewEntity.builder().userId(87654321L)
                .personaId(source.getPersonaId()).sessionId(UUID.randomUUID().toString()).status(Status.IN_PROGRESS).build());
        Long snapshotId = null;
        try {
            CountDownLatch start = new CountDownLatch(2);
            var first = CompletableFuture.supplyAsync(() -> captureAfter(start, "퍼포먼스 마케터", current.getInterviewId()));
            var second = CompletableFuture.supplyAsync(() -> captureAfter(start, "브랜드 마케터", current.getInterviewId()));
            InterviewEntity a = first.get(), b = second.get();
            assertThat(a.getPersonaId()).isEqualTo(b.getPersonaId()).isNotEqualTo(source.getPersonaId());
            snapshotId = a.getPersonaId();
            var snapshot = personas.findById(snapshotId).orElseThrow();
            assertThat(snapshot.getJobTitle()).isIn("퍼포먼스 마케터", "브랜드 마케터");
            assertThat(snapshot.isInterviewSnapshot()).isTrue();
            assertThat(personas.findByPersonaName("__interview_snapshot_" + current.getInterviewId())).isPresent();
            assertThat(personas.findById(source.getPersonaId()).orElseThrow().getJobTitle()).isEqualTo("옛 직무명");
            assertThat(interviews.findById(oldInterview.getInterviewId()).orElseThrow().getPersonaId()).isEqualTo(source.getPersonaId());
            snapshots.capture(user("나중 변경"), current.getInterviewId());
            assertThat(personas.findById(snapshotId).orElseThrow().getJobTitle()).isEqualTo(snapshot.getJobTitle());
        } finally {
            interviews.deleteById(current.getInterviewId());
            interviews.deleteById(oldInterview.getInterviewId());
            if (snapshotId != null) personas.deleteById(snapshotId);
            personas.deleteById(source.getPersonaId());
        }
    }

    InterviewEntity captureAfter(CountDownLatch start, String title, Long id) {
        return transactions.execute(status -> {
            // prepareInterview/OSIV처럼 두 요청 모두 갱신 전 대표 FK를 먼저 읽어 둔다.
            entityManager.find(InterviewEntity.class, id);
            start.countDown();
            try { start.await(); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException(e); }
            return snapshots.capture(user(title), id);
        });
    }
}
