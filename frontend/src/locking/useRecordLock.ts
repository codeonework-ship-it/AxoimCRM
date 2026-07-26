import { useCallback, useEffect, useMemo, useState } from "react";
import { api, ApiError, type RecordLock } from "../api/client";

type LockPhase = "idle" | "acquiring" | "held" | "blocked" | "lost" | "error";

interface SharedLease {
  references: number;
  acquire?: Promise<RecordLock>;
  releaseTimer?: ReturnType<typeof setTimeout>;
}

// React StrictMode mounts, cleans up and mounts once more in development. A
// shared, reference-counted lease prevents that diagnostic cycle from releasing
// the second mount's newly acquired lock.
const leases = new Map<string, SharedLease>();

export interface RecordLockState {
  phase: LockPhase;
  lock: RecordLock | null;
  message: string | null;
  checking: boolean;
  blocked: boolean;
  retry: () => void;
  forceReleaseAndRetry: () => Promise<void>;
}

export function useRecordLock(objectType: string, recordId: string | null, enabled: boolean): RecordLockState {
  const key = useMemo(() => `${objectType.toUpperCase()}:${recordId ?? ""}`, [objectType, recordId]);
  const [phase, setPhase] = useState<LockPhase>(enabled ? "acquiring" : "idle");
  const [lock, setLock] = useState<RecordLock | null>(null);
  const [message, setMessage] = useState<string | null>(null);
  const [revision, setRevision] = useState(0);

  const retry = useCallback(() => setRevision((value) => value + 1), []);

  useEffect(() => {
    if (!enabled || !recordId) {
      setPhase("idle");
      setLock(null);
      setMessage(null);
      return;
    }

    let alive = true;
    const lease = leases.get(key) ?? { references: 0 };
    lease.references += 1;
    if (lease.releaseTimer) {
      clearTimeout(lease.releaseTimer);
      lease.releaseTimer = undefined;
    }
    leases.set(key, lease);

    setPhase("acquiring");
    setMessage(null);
    lease.acquire = api.acquireRecordLock(objectType, recordId);
    void lease.acquire.then((value) => {
      if (!alive) return;
      setLock(value);
      setPhase("held");
    }).catch((error: unknown) => {
      if (!alive) return;
      setLock(null);
      setMessage(error instanceof Error ? error.message : "The edit lock could not be acquired.");
      const conflict = error instanceof ApiError && error.status === 409;
      setPhase(conflict ? "blocked" : "error");
      if (conflict) {
        void api.recordLockStatus(objectType, recordId).then((status) => {
          if (alive) setLock(status.lock);
        }).catch(() => undefined);
      }
    });

    return () => {
      alive = false;
      lease.references = Math.max(0, lease.references - 1);
      if (lease.references > 0) return;
      lease.releaseTimer = setTimeout(() => {
        const current = leases.get(key);
        if (!current || current.references > 0) return;
        // Wait for an in-flight acquire before releasing; otherwise a slow POST
        // could arrive after DELETE and leave a lease behind until expiry.
        void Promise.resolve(current.acquire).catch(() => undefined).finally(() => {
          void api.releaseRecordLock(objectType, recordId).catch(() => undefined);
          leases.delete(key);
        });
      }, 200);
    };
  }, [enabled, key, objectType, recordId, revision]);

  useEffect(() => {
    if (!enabled || !recordId || phase !== "held" || !lock) return;
    const interval = window.setInterval(() => {
      void api.heartbeatRecordLock(objectType, recordId).then((value) => {
        setLock(value);
      }).catch((error: unknown) => {
        setLock(null);
        setMessage(error instanceof Error ? error.message : "The edit lock heartbeat failed.");
        setPhase(error instanceof ApiError && error.status === 409 ? "lost" : "error");
      });
    }, Math.max(5, lock.heartbeatSeconds) * 1000);
    return () => window.clearInterval(interval);
  }, [enabled, lock, objectType, phase, recordId]);

  const forceReleaseAndRetry = useCallback(async () => {
    if (!recordId) return;
    await api.forceReleaseRecordLock(objectType, recordId);
    retry();
  }, [objectType, recordId, retry]);

  return {
    phase,
    lock,
    message,
    checking: enabled && phase === "acquiring",
    blocked: enabled && phase !== "held",
    retry,
    forceReleaseAndRetry,
  };
}
