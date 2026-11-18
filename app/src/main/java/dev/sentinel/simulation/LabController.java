package dev.sentinel.simulation;

import java.util.Arrays;
import java.util.Map;

import jakarta.validation.Valid;

import dev.sentinel.identity.CurrentUserProvider;
import dev.sentinel.security.SecurityAuditLog;
import dev.sentinel.security.SecurityEventType;
import dev.sentinel.shared.web.Problems;

import org.springframework.boot.autoconfigure.condition.ConditionalOnBooleanProperty;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fault injection control plane. Defence in depth: the bean only exists when {@code sentinel.lab.enabled=true},
 * the URL rule and the method rule both require {@code LAB_OPERATOR}, and every change is an audit event.
 */
@RestController
@RequestMapping("/api/lab")
@ConditionalOnBooleanProperty("sentinel.lab.enabled")
@PreAuthorize("hasRole('LAB_OPERATOR')")
public class LabController {

    private final FaultInjector faults;
    private final SecurityAuditLog auditLog;
    private final CurrentUserProvider currentUser;

    public LabController(FaultInjector faults, SecurityAuditLog auditLog, CurrentUserProvider currentUser) {
        this.faults = faults;
        this.auditLog = auditLog;
        this.currentUser = currentUser;
    }

    @GetMapping("/faults")
    public Map<FaultTarget, FaultProfile> faults() {
        return faults.all();
    }

    @PutMapping("/faults/{target}")
    public Map<FaultTarget, FaultProfile> apply(@PathVariable FaultTarget target, @Valid @RequestBody FaultProfile profile) {
        faults.apply(target, profile);
        audit("custom", target.name());
        return faults.all();
    }

    @PostMapping("/scenarios/{scenario}")
    public ResponseEntity<?> start(@PathVariable String scenario) {
        return Arrays.stream(LabScenario.values())
                .filter(candidate -> candidate.slug().equals(scenario))
                .findFirst()
                .<ResponseEntity<?>>map(found -> {
                    faults.reset();
                    found.profiles().forEach(faults::apply);
                    audit(found.slug(), String.join(",", found.profiles().keySet().stream().map(Enum::name).toList()));
                    return ResponseEntity.ok(faults.all());
                })
                .orElseGet(() -> {
                    ProblemDetail problem = Problems.of(HttpStatus.NOT_FOUND, "unknown-scenario", "Unknown scenario",
                            "Available scenarios: " + Arrays.stream(LabScenario.values()).map(LabScenario::slug).toList());
                    return ResponseEntity.of(problem).build();
                });
    }

    @DeleteMapping("/faults")
    public Map<FaultTarget, FaultProfile> reset() {
        faults.reset();
        audit("reset", "ALL");
        return faults.all();
    }

    private void audit(String scenario, String targets) {
        auditLog.recordForCurrentRequest(SecurityEventType.LAB_FAULT_CHANGED, currentUser.current().username(),
                Map.of("lab.scenario", scenario, "lab.targets", targets));
    }
}
