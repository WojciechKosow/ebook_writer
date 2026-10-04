package com.ebookwriter.SaaS.service.ebook;

import java.util.List;

/**
 * The outcome of the layout check. {@code PASS}: no errors (warnings may remain).
 * {@code REPAIRABLE}: errors, every one of which has an upstream repair.
 * {@code FAILED}: at least one error no deterministic repair can fix.
 */
record LayoutValidationResult(Status status, List<LayoutIssue> issues) {

    enum Status { PASS, REPAIRABLE, FAILED }

    static LayoutValidationResult of(List<LayoutIssue> issues) {
        List<LayoutIssue> errors = issues.stream().filter(i -> i.severity() == LayoutIssue.Severity.ERROR).toList();
        Status status = errors.isEmpty() ? Status.PASS
                : errors.stream().allMatch(LayoutIssue::repairable) ? Status.REPAIRABLE : Status.FAILED;
        return new LayoutValidationResult(status, List.copyOf(issues));
    }

    List<LayoutIssue> errors() {
        return issues.stream().filter(i -> i.severity() == LayoutIssue.Severity.ERROR).toList();
    }

    List<LayoutIssue> warnings() {
        return issues.stream().filter(i -> i.severity() == LayoutIssue.Severity.WARNING).toList();
    }

    boolean passed() {
        return status == Status.PASS;
    }
}
