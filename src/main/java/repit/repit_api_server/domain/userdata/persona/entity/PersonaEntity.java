package repit.repit_api_server.domain.userdata.persona.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Level;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Major;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Type;

@Entity
@Table(name = "persona")
@Getter
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class PersonaEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "persona_id")
    private Long personaId;

    @Column(name = "name", nullable = false,  unique = true)
    private String personaName;

    // 직책. 기존 페르소나는 전부 기술 면접관이라 기본값이 TECH다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Role role = Role.TECH;

    // 기술 면접관의 세부 전공. 인사팀·CEO에게는 해당 값이 없어 비어 있다.
    @Enumerated(EnumType.STRING)
    private Major major;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    // 난이도. 안 보내던 기존 요청과 기존 행은 전부 NORMAL이다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private Level level = Level.NORMAL;

    @Column(nullable = false)
    private int career;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;
}
