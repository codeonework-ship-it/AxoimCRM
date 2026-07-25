package com.axiom.workspaces;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class EpicWorkspaceController {
    private final EpicWorkspaceService workspaces;

    public EpicWorkspaceController(EpicWorkspaceService workspaces) {
        this.workspaces = workspaces;
    }

    @GetMapping("/forecast")
    public EpicWorkspaceService.WorkspacePage forecast(@RequestParam(required = false) String search,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "0") int page) {
        return workspaces.forecast(search, status, page);
    }

    @GetMapping("/contracts")
    public EpicWorkspaceService.WorkspacePage contracts(@RequestParam(required = false) String search,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "0") int page) {
        return workspaces.contracts(search, status, page);
    }

    @GetMapping("/campaigns")
    public EpicWorkspaceService.WorkspacePage campaigns(@RequestParam(required = false) String search,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "0") int page) {
        return workspaces.campaigns(search, status, page);
    }

    @GetMapping("/cases")
    public EpicWorkspaceService.WorkspacePage cases(@RequestParam(required = false) String search,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "0") int page) {
        return workspaces.cases(search, status, page);
    }

    @GetMapping("/migration")
    public EpicWorkspaceService.WorkspacePage migrations(@RequestParam(required = false) String search,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "0") int page) {
        return workspaces.migrations(search, status, page);
    }
}
