package com.axiom.audit;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/audit")
public class AuditController {
    private final AuditService audit;

    public AuditController(AuditService audit) { this.audit = audit; }

    @GetMapping
    public List<AuditService.AuditRow> list(@RequestParam(required = false) String entityType,
                                            @RequestParam(defaultValue = "50") int limit) {
        return audit.list(entityType, limit);
    }
}
