package repit.repit_api_server.domain.userdata.interview.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.domain.userdata.interview.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.interview.dto.response.PersonaResponse;
import repit.repit_api_server.domain.userdata.interview.service.PersonaService;

import java.util.List;

@RestController
@RequestMapping("/api/interviews")
@RequiredArgsConstructor
public class PersonaController {
    private final PersonaService personaService;

    @PostMapping("/savePersona")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PersonaResponse> savePersona(
            @RequestBody PersonaRequest request) {
        return ApiResponse.created(personaService.createPersona(request));
    }

    @GetMapping("/getPersonaById")
    public ApiResponse<PersonaResponse> getPersonaById(
            @RequestParam Long id) {
        return ApiResponse.success(personaService.getPersonaById(id));
    }

    @GetMapping("/getPersonaByName")
    public ApiResponse<PersonaResponse> getPersonaByName(
            @RequestParam String name) {
        return ApiResponse.success(personaService.getPersonaByName(name));
    }

    @GetMapping("/getAllPersona")
    public ApiResponse<List<PersonaResponse>> getAllPersona() {
        return ApiResponse.success(personaService.getAllPersona());
    }
}
