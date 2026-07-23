package com.windletter.desktop.ui;

/** User-facing, non-sensitive state for the phase 1 self-test panel. */
public record SelfTestViewState(
    String title,
    String summary,
    String authentication,
    String transport,
    String payload,
    String negativeChecks,
    String timing,
    boolean successful,
    boolean running
) {
}
