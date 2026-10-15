package repit.repit_api_server.domain.company.service;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import repit.repit_api_server.domain.company.dto.CompanySummaryResponse;
import repit.repit_api_server.domain.company.entity.CompanyEntity;
import repit.repit_api_server.domain.company.entity.CompanyFactEntity;
import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.entity.enums.CompanyFactKind;
import repit.repit_api_server.domain.company.entity.enums.CompanyStatus;
import repit.repit_api_server.domain.company.repository.CompanyFactRepository;
import repit.repit_api_server.domain.company.repository.CompanyRepository;
import repit.repit_api_server.global.exception.BusinessException;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * 공개 기업 목록·검색.
 *
 * <p>공개 기업이 30개 안팎이라 요청마다 전부 읽어 메모리에서 거른다. 캐시는 두지 않는다.
 */
@Service
@RequiredArgsConstructor
public class CompanyQueryService {

    private static final int QUERY_MIN_LENGTH = 2;
    private static final int QUERY_MAX_LENGTH = 30;
    private static final int TALENT_KEYWORDS = 3;

    private final CompanyRepository companyRepository;
    private final CompanyFactRepository companyFactRepository;

    @Transactional(readOnly = true)
    public List<CompanySummaryResponse> search(String q, CompanyCategory category) {
        String query = q == null ? "" : normalize(q);
        if (!query.isEmpty() && (query.length() < QUERY_MIN_LENGTH || query.length() > QUERY_MAX_LENGTH)) {
            throw BusinessException.unprocessable(
                    "검색어는 " + QUERY_MIN_LENGTH + "~" + QUERY_MAX_LENGTH + "자로 입력해주세요.");
        }

        List<CompanyEntity> companies = companyRepository.findAllByStatus(CompanyStatus.PUBLISHED).stream()
                .filter(company -> category == null || company.getCategory() == category)
                .filter(company -> query.isEmpty() || matches(company, query))
                .sorted(Comparator.comparing(CompanyEntity::getCategory)
                        .thenComparing(CompanyEntity::getDisplayName))
                .toList();
        if (companies.isEmpty()) {
            return List.of();
        }

        Map<Long, List<String>> talentKeywords = companyFactRepository
                .findAllByKindOrderBySortOrder(CompanyFactKind.TALENT).stream()
                .collect(Collectors.groupingBy(CompanyFactEntity::getCompanyId,
                        Collectors.mapping(CompanyFactEntity::getTitle, Collectors.toList())));

        return companies.stream()
                .map(company -> new CompanySummaryResponse(
                        company.getCompanyId(),
                        company.getDisplayName(),
                        company.getLegalName(),
                        company.getParent() == null ? null : company.getParent().getDisplayName(),
                        company.getCategory(),
                        company.getLegalSize(),
                        talentKeywords.getOrDefault(company.getCompanyId(), List.of()).stream()
                                .limit(TALENT_KEYWORDS)
                                .toList(),
                        company.getDataAsOf()))
                .toList();
    }

    /** 보이는 이름, 법인 이름, 별칭 가운데 하나라도 검색어를 품으면 맞다. 공백·대소문자는 보지 않는다. */
    private boolean matches(CompanyEntity company, String query) {
        return Stream.concat(Stream.of(company.getDisplayName(), company.getLegalName()), company.getAliases().stream())
                .anyMatch(name -> normalize(name).contains(query));
    }

    private static String normalize(String value) {
        return value.replaceAll("\\s+", "").toLowerCase(Locale.ROOT);
    }
}
