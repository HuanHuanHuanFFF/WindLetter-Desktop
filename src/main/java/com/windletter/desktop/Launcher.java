package com.windletter.desktop;

import javafx.application.Application;

/** Classpath-friendly desktop entry point. */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Application.launch(WindLetterApplication.class, args);
    }
}
