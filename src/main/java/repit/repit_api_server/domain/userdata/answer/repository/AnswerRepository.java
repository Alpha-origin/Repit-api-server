package repit.repit_api_server.domain.userdata.answer.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import repit.repit_api_server.domain.userdata.answer.entity.AnswerEntity;

import java.util.List;

public interface AnswerRepository extends JpaRepository<AnswerEntity, Long> {
    List<AnswerEntity> findAllByInterviewId(Long interviewId);

    // 질문을 지우기 전에 답변부터 비워야 한다. 행을 하나씩 읽어올 이유가 없어 벌크로 지운다.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from AnswerEntity a where a.interviewId = :interviewId")
    void deleteAllByInterviewId(Long interviewId);
}
