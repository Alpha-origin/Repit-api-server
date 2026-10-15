package repit.repit_api_server.domain.company.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import repit.repit_api_server.domain.company.dto.CompanySummaryResponse;
import repit.repit_api_server.domain.company.entity.enums.CompanyCategory;
import repit.repit_api_server.domain.company.service.CompanyQueryService;
import repit.repit_api_server.global.common.ApiResponse;

import java.util.List;

@RestController
@RequestMapping("/api/companies")
@RequiredArgsConstructor
public class CompanyController {

    private final CompanyQueryService companyQueryService;

    // 공개 기업 목록. 공개 기업이 없으면 빈 목록이고, 웹은 그때 기업 선택 섹션을 숨긴다.
    @GetMapping
    public ApiResponse<List<CompanySummaryResponse>> getCompanies(
            @RequestParam(required = false) String q,
            @RequestParam(required = false) CompanyCategory category) {
        return ApiResponse.success(companyQueryService.search(q, category));
    }
}
