package com.axiom.mobile;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/mobile/offline")
public class MobileOfflineController {
    private final MobileOfflineService offline;

    public MobileOfflineController(MobileOfflineService offline) { this.offline = offline; }

    @PostMapping("/packages")
    @ResponseStatus(HttpStatus.CREATED)
    public MobileOfflineService.OfflinePackage create(@RequestBody @Valid MobileOfflineService.PackageRequest request) {
        return offline.createPackage(request);
    }

    @GetMapping("/devices/{deviceId}/packages")
    public List<MobileOfflineService.OfflinePackage> packages(@PathVariable UUID deviceId) {
        return offline.packages(deviceId);
    }

    @GetMapping("/packages/{id}/records")
    public List<MobileOfflineService.OfflineSnapshot> records(@PathVariable UUID id) { return offline.snapshots(id); }

    @PostMapping("/packages/{id}/sync")
    public MobileOfflineService.SyncResult sync(@PathVariable UUID id,
                                                @RequestBody @Valid MobileOfflineService.SyncRequest request) {
        return offline.synchronize(id, request);
    }

    @GetMapping("/devices/{deviceId}/conflicts")
    public List<MobileOfflineService.ConflictView> conflicts(@PathVariable UUID deviceId,
                                                             @RequestParam(required = false) String status) {
        return offline.conflicts(deviceId, status);
    }

    @PostMapping("/conflicts/{id}/resolve")
    public MobileOfflineService.ConflictView resolve(@PathVariable UUID id,
                                                      @RequestBody @Valid MobileOfflineService.ResolveRequest request) {
        return offline.resolve(id, request);
    }
}
