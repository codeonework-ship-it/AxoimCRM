package com.axiom.admin;

import com.axiom.auth.CrmRole;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/rbac")
public class RbacController {
    private final RbacService rbac;

    public RbacController(RbacService rbac) {
        this.rbac = rbac;
    }

    @GetMapping("/roles")
    public List<CrmRole.Descriptor> roles() {
        return CrmRole.catalogue();
    }

    @GetMapping("/policies")
    public List<RbacService.ScreenPolicy> policies(@RequestParam(required = false) String role) {
        return rbac.policies(role);
    }
}
