package com.windletter.desktop.vault;

import java.util.Objects;

public final class DesktopVaultException extends Exception {

    private final DesktopVaultProblem problem;

    DesktopVaultException(DesktopVaultProblem problem) {
        super(Objects.requireNonNull(problem, "problem").userMessage());
        this.problem = problem;
    }

    public DesktopVaultProblem problem() {
        return problem;
    }
}
