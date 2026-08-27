package repit.repit_api_server.domain.userdata.persona.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import repit.repit_api_server.global.common.ApiResponse;
import repit.repit_api_server.domain.userdata.persona.dto.request.PersonaRequest;
import repit.repit_api_server.domain.userdata.persona.dto.response.PersonaResponse;
import repit.repit_api_server.domain.userdata.persona.entity.enums.Role;
import repit.repit_api_server.domain.userdata.persona.service.PersonaService;

import java.util.List;

@RestController
@RequestMapping("/api/persona")
@RequiredArgsConstructor
public class PersonaController {
    private final PersonaService personaService;

    @PostMapping("/save")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<PersonaResponse> savePersona(
            @RequestBody PersonaRequest request) {
        return ApiResponse.created(personaService.createPersona(request));
    }

    @GetMapping("/getById")
    public ApiResponse<PersonaResponse> getPersonaById(
            @RequestParam Long id) {
        return ApiResponse.success(personaService.getPersonaById(id));
    }

    @GetMapping("/getByName")
    public ApiResponse<PersonaResponse> getPersonaByName(
            @RequestParam String name) {
        return ApiResponse.success(personaService.getPersonaByName(name));
    }

    // role을 주면 그 직책만 내려준다. 비우면 예전처럼 전부 내려간다.
    @GetMapping("/getAll")
    public ApiResponse<List<PersonaResponse>> getAllPersona(
            @RequestParam(required = false) Role role) {
        return ApiResponse.success(personaService.getAllPersona(role));
    }
}
