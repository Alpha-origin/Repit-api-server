package repit.repit_api_server.domain.userdata.interview.entity;

import jakarta.persistence.*;
import repit.repit_api_server.domain.userdata.interview.entity.enums.Status;

import java.time.LocalDateTime;

@Entity
@Table(name = "interview")
public class Interview {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "interview_id")
    private Long id;

    @Column(nullable = false)
    private Long userId;

    @JoinColumn(nullable = false)
    @OneToOne(fetch = FetchType.LAZY)
    private Persona persona;

    @Column(nullable = false)
    private String sessionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false)
    private LocalDateTime createdAt;
}
