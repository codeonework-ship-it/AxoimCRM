package com.axiom.workspaces;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/workspaces")
public class EpicWorkspaceController {
    private final EpicWorkspaceService workspaces;
    private final WorkspaceExportService exports;

    public EpicWorkspaceController(EpicWorkspaceService workspaces, WorkspaceExportService exports) {
        this.workspaces = workspaces;
        this.exports = exports;
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

    @GetMapping("/partners")
    public EpicWorkspaceService.WorkspacePage partners(@RequestParam(required = false) String search,
                                                       @RequestParam(required = false) String status,
                                                       @RequestParam(defaultValue = "0") int page) {
        return workspaces.partners(search, status, page);
    }

    @GetMapping("/automation")
    public EpicWorkspaceService.WorkspacePage automation(@RequestParam(required = false) String search,
                                                         @RequestParam(required = false) String status,
                                                         @RequestParam(defaultValue = "0") int page) {
        return workspaces.automation(search, status, page);
    }

    @GetMapping("/analytics")
    public EpicWorkspaceService.WorkspacePage analytics(@RequestParam(required = false) String search,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "0") int page) {
        return workspaces.analytics(search, status, page);
    }

    @GetMapping("/copilot")
    public EpicWorkspaceService.WorkspacePage copilot(@RequestParam(required = false) String search,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "0") int page) {
        return workspaces.copilot(search, status, page);
    }

    @GetMapping("/mobile")
    public EpicWorkspaceService.WorkspacePage mobile(@RequestParam(required = false) String search,
                                                     @RequestParam(required = false) String status,
                                                     @RequestParam(defaultValue = "0") int page) {
        return workspaces.mobile(search, status, page);
    }

    @GetMapping("/integrations")
    public EpicWorkspaceService.WorkspacePage integrations(@RequestParam(required = false) String search,
                                                           @RequestParam(required = false) String status,
                                                           @RequestParam(defaultValue = "0") int page) {
        return workspaces.integrations(search, status, page);
    }

    @GetMapping("/sandbox")
    public EpicWorkspaceService.WorkspacePage sandbox(@RequestParam(required = false) String search,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(defaultValue = "0") int page) {
        return workspaces.sandbox(search, status, page);
    }

    @GetMapping("/audit")
    public EpicWorkspaceService.WorkspacePage audit(@RequestParam(required = false) String search,
                                                    @RequestParam(required = false) String status,
                                                    @RequestParam(defaultValue = "0") int page) {
        return workspaces.audit(search, status, page);
    }

    @GetMapping("/bfsi")
    public EpicWorkspaceService.WorkspacePage bfsi(@RequestParam(required = false) String search,
                                                   @RequestParam(required = false) String status,
                                                   @RequestParam(defaultValue = "0") int page) {
        return workspaces.bfsi(search, status, page);
    }

    @GetMapping("/commodity")
    public EpicWorkspaceService.WorkspacePage commodity(@RequestParam(required = false) String search,
                                                        @RequestParam(required = false) String status,
                                                        @RequestParam(defaultValue = "0") int page) {
        return workspaces.commodity(search, status, page);
    }

    @GetMapping("/{module}/export")
    public ResponseEntity<byte[]> export(@PathVariable String module,
                                         @RequestParam WorkspaceExportService.ExportFormat format,
                                         @RequestParam(required = false) String search,
                                         @RequestParam(required = false) String status,
                                         @RequestParam(defaultValue = "0") int page) {
        WorkspaceExportService.FilePayload file = exports.export(module, format, search, status, page);
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + file.filename() + "\"")
                .contentType(MediaType.parseMediaType(file.contentType()))
                .body(file.bytes());
    }
}
