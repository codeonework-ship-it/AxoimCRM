package com.axiom.workspaces;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workspaces/forecast")
public class ForecastScenarioController {
    private final ForecastScenarioService scenarios;

    public ForecastScenarioController(ForecastScenarioService scenarios) {
        this.scenarios = scenarios;
    }

    @GetMapping("/{id}/scenarios")
    public List<ForecastScenarioService.Scenario> list(@PathVariable UUID id) {
        return scenarios.list(id);
    }

    @PostMapping("/{id}/scenarios")
    @ResponseStatus(HttpStatus.CREATED)
    public ForecastScenarioService.Scenario create(@PathVariable UUID id,
                                                   @RequestBody @Valid ForecastScenarioService.ScenarioRequest request) {
        return scenarios.create(id, request);
    }
}
