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

    // 직책. 기본값을 두지 않는다 — 안 채우고 저장하면 TECH로 묻히는 대신 NOT NULL로 걸린다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Role role;

    // 기술 면접관의 세부 전공. 인사팀·CEO에게는 해당 값이 없어 비어 있다.
    @Enumerated(EnumType.STRING)
    private Major major;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    // 난이도. 직책과 마찬가지로 기본값을 두지 않는다.
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Level level;

    @Column(nullable = false)
    private int career;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;
}
