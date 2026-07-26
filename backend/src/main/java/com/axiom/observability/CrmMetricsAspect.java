package com.axiom.observability;

import com.axiom.automation.RuleModel;
import com.axiom.automation.RunContext;
import com.axiom.common.ConflictException;
import com.axiom.common.ForbiddenException;
import com.axiom.reporting.ReportService;
import io.micrometer.core.instrument.Timer;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.stereotype.Component;

import java.util.Locale;

/** Keeps instrumentation cross-cutting instead of mixing it into domain services. */
@Aspect
@Component
public class CrmMetricsAspect {

    private final CrmMetrics metrics;

    public CrmMetricsAspect(CrmMetrics metrics) {
        this.metrics = metrics;
    }

    @Around("execution(public * com.axiom.automation.RuleEngine.run(..))")
    public Object automation(ProceedingJoinPoint call) throws Throwable {
        Timer.Sample sample = metrics.start();
        RunContext context = (RunContext) call.getArgs()[0];
        String operation = context.dryRun() ? "dry_run" : "execute";
        try {
            RuleModel.ExecutionTrace trace = (RuleModel.ExecutionTrace) call.proceed();
            metrics.record(sample, "automation", operation, trace.status());
            return trace;
        } catch (Throwable failure) {
            metrics.record(sample, "automation", operation, outcome(failure));
            throw failure;
        }
    }

    @Around("execution(public * com.axiom.reporting.ReportService.export(..)) || "
            + "execution(public * com.axiom.reporting.ReportService.documentPreview(..))")
    public Object reporting(ProceedingJoinPoint call) throws Throwable {
        Timer.Sample sample = metrics.start();
        String method = call.getSignature().getName();
        String operation = "documentPreview".equals(method) ? "document_preview" : reportOperation(call.getArgs());
        try {
            Object result = call.proceed();
            metrics.record(sample, "reporting", operation, "generated");
            return result;
        } catch (Throwable failure) {
            metrics.record(sample, "reporting", operation, outcome(failure));
            throw failure;
        }
    }

    @Around("execution(public * com.axiom.locking.RecordLockService.*(..))")
    public Object recordLock(ProceedingJoinPoint call) throws Throwable {
        return around(call, "record_lock", snake(call.getSignature().getName()));
    }

    @Around("execution(public * com.axiom.security.MakerCheckerService.submit(..)) || "
            + "execution(public * com.axiom.security.MakerCheckerService.approve(..)) || "
            + "execution(public * com.axiom.security.MakerCheckerService.reject(..)) || "
            + "execution(public * com.axiom.security.MakerCheckerService.delegate(..)) || "
            + "execution(public * com.axiom.security.MakerCheckerService.revokeDelegation(..))")
    public Object approval(ProceedingJoinPoint call) throws Throwable {
        return around(call, "approval", snake(call.getSignature().getName()));
    }

    private Object around(ProceedingJoinPoint call, String module, String operation) throws Throwable {
        Timer.Sample sample = metrics.start();
        try {
            Object result = call.proceed();
            metrics.record(sample, module, operation, "succeeded");
            return result;
        } catch (Throwable failure) {
            metrics.record(sample, module, operation, outcome(failure));
            throw failure;
        }
    }

    private static String reportOperation(Object[] args) {
        for (Object arg : args) {
            if (arg instanceof ReportService.ReportFormat format) {
                return "export_" + format.name().toLowerCase(Locale.ROOT);
            }
        }
        return "error";
    }

    private static String outcome(Throwable failure) {
        if (failure instanceof ConflictException) return "conflict";
        if (failure instanceof ForbiddenException || failure instanceof SecurityException) return "denied";
        return "failed";
    }

    private static String snake(String value) {
        return value.replaceAll("([a-z0-9])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
    }
}
