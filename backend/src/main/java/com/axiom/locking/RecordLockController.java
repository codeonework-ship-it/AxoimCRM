package com.axiom.locking;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.UUID;

/** HTTP application boundary for the lease-based record editing protocol. */
@RestController
@RequestMapping("/api/v1/record-locks")
public class RecordLockController {

    private final RecordLockService locks;

    public RecordLockController(RecordLockService locks) {
        this.locks = locks;
    }

    public record LockStatus(boolean locked, RecordLockService.Lock lock) {}

    @GetMapping("/{objectType}/{recordId}")
    public LockStatus status(@PathVariable String objectType, @PathVariable UUID recordId) {
        RecordLockService.Lock lock = locks.status(objectType, recordId);
        return new LockStatus(lock != null, lock);
    }

    @PostMapping("/{objectType}/{recordId}")
    @ResponseStatus(HttpStatus.CREATED)
    public RecordLockService.Lock acquire(@PathVariable String objectType, @PathVariable UUID recordId) {
        return locks.acquire(objectType, recordId);
    }

    @PutMapping("/{objectType}/{recordId}/heartbeat")
    public RecordLockService.Lock heartbeat(@PathVariable String objectType, @PathVariable UUID recordId) {
        return locks.heartbeat(objectType, recordId);
    }

    @DeleteMapping("/{objectType}/{recordId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void release(@PathVariable String objectType, @PathVariable UUID recordId) {
        locks.release(objectType, recordId);
    }

    @PostMapping("/{objectType}/{recordId}/force-release")
    public Map<String, String> forceRelease(@PathVariable String objectType, @PathVariable UUID recordId) {
        locks.forceRelease(objectType, recordId);
        return Map.of("message", "The edit lock was released. Acquire the record before editing it.");
    }

    @DeleteMapping("/mine")
    public Map<String, Integer> releaseMine() {
        return Map.of("released", locks.releaseAllForCurrentUser());
    }
}
