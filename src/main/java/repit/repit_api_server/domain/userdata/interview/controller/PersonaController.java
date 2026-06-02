package repit.repit_api_server.domain.userdata.interview.controller;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.domain.userdata.interview.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.PersonaResponse;
import repit.repit_api_server.domain.userdata.interview.service.PersonaService;

@RestController
@RequestMapping("/api/v1/interview")
public class PersonaController {
    private PersonaService personaService;

    @PostMapping("/savePersona")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PersonaResponse> savePersona(@RequestBody PersonaRequest request) {
        return ApiResponse.created(personaService.createPersona(request));
    }
}
