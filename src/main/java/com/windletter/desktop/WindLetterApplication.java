package com.windletter.desktop;

import com.windletter.desktop.ui.VaultDesktopView;
import com.windletter.desktop.vault.DesktopVault;
import javafx.application.Application;
import javafx.stage.Stage;

/** WindLetter Desktop JavaFX entry point. */
public final class WindLetterApplication extends Application {

    public static final String WINDOW_TITLE = "風笺 · WindLetter";

    private VaultDesktopView view;

    @Override
    public void start(Stage stage) {
        view = new VaultDesktopView(stage, new DesktopVault());
        view.show();
    }

    @Override
    public void stop() {
        if (view != null) {
            view.close();
        }
    }
}
