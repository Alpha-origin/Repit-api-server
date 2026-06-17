package repit.repit_api_server.domain.userdata.interview.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Gender;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Major;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Type;

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
    private Long id;

    @Column(name = "name", nullable = false,  unique = true)
    private String personaName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Major major;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Type type;

    @Column(nullable = false)
    private int career;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Gender gender;
}
