package repit.repit_api_server.domain.metadata.support;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * 직무 명세(비개발·일반 직군 명세 01 §3.1). 직무 코드로 GitHub 규칙, 받는 문서 종류, 보완 입력 여부를 찾는다.
 *
 * <p>직무 값을 해석하는 코드는 여기에 모은다.
 */
public enum JobRole {
    FRONTEND(JobFamily.DEV, GithubRule.REQUIRED, List.of("PORTFOLIO"), List.of(), false),
    BACKEND(JobFamily.DEV, GithubRule.REQUIRED, List.of("PORTFOLIO"), List.of(), false),
    WEB_PUBLISHER(JobFamily.DEV, GithubRule.REQUIRED, List.of("PORTFOLIO"), List.of(), false),
    DATA_ANALYST(JobFamily.NON_DEV, GithubRule.OPTIONAL,
            List.of("PORTFOLIO", "ANALYSIS_REPORT"), List.of("COMPETITION_SUMMARY"), false),
    DBA_INFRA(JobFamily.NON_DEV, GithubRule.OPTIONAL,
            List.of("PORTFOLIO", "CAREER_DESCRIPTION"), List.of("INCIDENT_REPORT", "TABLE_SPEC", "SYSTEM_ARCHITECTURE"), false),
    QA_ENGINEER(JobFamily.NON_DEV, GithubRule.OPTIONAL,
            List.of("TEST_PLAN", "TEST_CASE"), List.of("PORTFOLIO", "BUG_REPORT"), false),
    SECURITY(JobFamily.NON_DEV, GithubRule.OPTIONAL,
            List.of("VULNERABILITY_REPORT", "CTF_WRITEUP"), List.of("PORTFOLIO"), false),
    PRODUCT_MANAGER(JobFamily.NON_DEV, GithubRule.HIDDEN,
            List.of("PRD", "POLICY_DOC", "REQUIREMENT_SPEC"), List.of("PORTFOLIO", "SCREEN_SPEC"), true),
    IT_CONSULTANT(JobFamily.NON_DEV, GithubRule.HIDDEN,
            List.of("CAREER_DESCRIPTION", "PORTFOLIO"), List.of("PROPOSAL", "PROJECT_REPORT", "REQUIREMENT_SPEC"), true),
    GEN_STRATEGY(JobFamily.GENERAL, GithubRule.HIDDEN, List.of(),
            List.of("PORTFOLIO", "WORK_SAMPLE"), false, true, MaterialRule.NON_RESUME_OR_COVER_LETTER),
    GEN_HR_ADMIN(JobFamily.GENERAL, GithubRule.HIDDEN, List.of(),
            List.of("PORTFOLIO", "WORK_SAMPLE"), false, true, MaterialRule.NON_RESUME_OR_COVER_LETTER),
    GEN_FINANCE(JobFamily.GENERAL, GithubRule.HIDDEN, List.of(),
            List.of("PORTFOLIO", "WORK_SAMPLE"), false, true, MaterialRule.NON_RESUME_OR_COVER_LETTER),
    GEN_MARKETING(JobFamily.GENERAL, GithubRule.HIDDEN, List.of(),
            List.of("PORTFOLIO", "WORK_SAMPLE"), false, true, MaterialRule.NON_RESUME_OR_COVER_LETTER),
    GEN_SALES(JobFamily.GENERAL, GithubRule.HIDDEN, List.of(),
            List.of("PORTFOLIO", "WORK_SAMPLE"), false, true, MaterialRule.NON_RESUME_OR_COVER_LETTER),
    GEN_DESIGN(JobFamily.GENERAL, GithubRule.HIDDEN, List.of(),
            List.of("PORTFOLIO", "WORK_SAMPLE"), false, true, MaterialRule.NON_RESUME_OR_COVER_LETTER);

    /** 모든 직무에서 선택으로 올릴 수 있는 종류. 필수에 이미 있으면 필수로 센다. */
    private static final List<String> COMMON_TYPES = List.of("RESUME", "CAREER_DESCRIPTION");

    public enum JobFamily { DEV, NON_DEV, GENERAL }

    public enum GithubRule { REQUIRED, OPTIONAL, HIDDEN }

    public enum MaterialRule { REQUIRED_TYPE, NON_RESUME_OR_COVER_LETTER }

    private final boolean coverLettersAllowed;
    private final MaterialRule materialRule;
    private final JobFamily family;
    private final GithubRule githubRule;
    private final List<String> requiredTypes;
    private final List<String> optionalTypes;
    private final boolean supplementsAllowed;

    JobRole(JobFamily family, GithubRule githubRule, List<String> requiredTypes, List<String> optionalTypes,
            boolean supplementsAllowed) {
        this(family, githubRule, requiredTypes, optionalTypes, supplementsAllowed, false, MaterialRule.REQUIRED_TYPE);
    }

    JobRole(JobFamily family, GithubRule githubRule, List<String> requiredTypes, List<String> optionalTypes,
            boolean supplementsAllowed, boolean coverLettersAllowed, MaterialRule materialRule) {
        this.coverLettersAllowed = coverLettersAllowed;
        this.materialRule = materialRule;
        this.family = family;
        this.githubRule = githubRule;
        this.requiredTypes = requiredTypes;
        this.optionalTypes = optionalTypes;
        this.supplementsAllowed = supplementsAllowed;
    }

    /** MAJOR_ 접두사 없는 직무 코드로 찾는다. 모르는 값이면 빈 값이다. */
    public static Optional<JobRole> find(String code) {
        if (code == null || code.isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(valueOf(code.strip().toUpperCase(Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    public boolean allowsType(String type) {
        return type != null && (requiredTypes.contains(type) || optionalTypes.contains(type) || COMMON_TYPES.contains(type));
    }

    public boolean isRequiredType(String type) {
        return requiredTypes.contains(type);
    }

    /** 이 직무에서 next로 바꿀 때 저장된 자료를 그대로 둘 수 있는지. 개발 3직무 사이만 유지한다(01 §2.1). */
    public boolean keepsMaterialsTo(JobRole next) {
        if (this == next) {
            return true;
        }
        return switch (family) {
            case DEV -> next.family == JobFamily.DEV;
            case NON_DEV, GENERAL -> false;
        };
    }

    /** 인증 서버 저장값(MAJOR_ 접두사). */
    public String authMajor() {
        return "MAJOR_" + name();
    }

    public boolean requiresJobTitle() {
        return family == JobFamily.GENERAL;
    }

    public boolean coverLettersAllowed() {
        return coverLettersAllowed;
    }

    public MaterialRule materialRule() {
        return materialRule;
    }

    public JobFamily family() {
        return family;
    }

    public GithubRule githubRule() {
        return githubRule;
    }

    public List<String> requiredTypes() {
        return requiredTypes;
    }

    public boolean supplementsAllowed() {
        return supplementsAllowed;
    }
}
