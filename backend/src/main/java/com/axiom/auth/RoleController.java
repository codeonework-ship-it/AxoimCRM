package com.axiom.auth;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/roles")
public class RoleController {
    @GetMapping
    public List<CrmRole.Descriptor> catalogue() { return CrmRole.catalogue(); }
}
