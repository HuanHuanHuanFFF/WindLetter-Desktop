package com.windletter.desktop.ui;

import javafx.beans.property.BooleanProperty;
import javafx.beans.property.SimpleBooleanProperty;
import javafx.beans.property.StringProperty;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Pos;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleButton;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.StackPane;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;
import javafx.scene.shape.Line;
import javafx.scene.shape.SVGPath;

/** Password input that stays masked by default and can be revealed in place. */
final class RevealablePasswordField extends HBox {

    private static final String SHOW_PASSWORD = "显示密码";
    private static final String HIDE_PASSWORD = "隐藏密码";

    private final PasswordField maskedField = new PasswordField();
    private final TextField visibleField = new TextField();
    private final ToggleButton revealButton = new ToggleButton();
    private final BooleanProperty passwordVisible =
        new SimpleBooleanProperty(false);

    RevealablePasswordField(String prompt) {
        getStyleClass().add("revealable-password-field");
        setAlignment(Pos.CENTER_LEFT);
        setMaxWidth(Double.MAX_VALUE);

        maskedField.setPromptText(prompt);
        maskedField.setMaxWidth(Double.MAX_VALUE);
        visibleField.setPromptText(prompt);
        visibleField.setMaxWidth(Double.MAX_VALUE);
        visibleField.getStyleClass().add("revealed-password-field");
        visibleField.textProperty().bindBidirectional(maskedField.textProperty());

        maskedField.visibleProperty().bind(passwordVisible.not());
        maskedField.managedProperty().bind(passwordVisible.not());
        visibleField.visibleProperty().bind(passwordVisible);
        visibleField.managedProperty().bind(passwordVisible);

        StackPane fields = new StackPane(maskedField, visibleField);
        fields.setMaxWidth(Double.MAX_VALUE);
        HBox.setHgrow(fields, Priority.ALWAYS);

        revealButton.getStyleClass().add("password-reveal-button");
        revealButton.setGraphic(eyeGraphic());
        revealButton.setFocusTraversable(true);
        revealButton.setAccessibleText(SHOW_PASSWORD);
        revealButton.setTooltip(new Tooltip(SHOW_PASSWORD));
        revealButton.selectedProperty().bindBidirectional(passwordVisible);
        revealButton.setOnAction(event -> focusActiveField());
        passwordVisible.addListener((observable, wasVisible, isVisible) ->
            updateRevealButton(isVisible)
        );

        getChildren().addAll(fields, revealButton);
    }

    StringProperty textProperty() {
        return maskedField.textProperty();
    }

    void setOnAction(EventHandler<ActionEvent> handler) {
        maskedField.setOnAction(handler);
        visibleField.setOnAction(handler);
    }

    boolean isPasswordVisible() {
        return passwordVisible.get();
    }

    void setPasswordVisible(boolean visible) {
        passwordVisible.set(visible);
    }

    char[] takePassword() {
        char[] password = maskedField.getText().toCharArray();
        clear();
        return password;
    }

    void clear() {
        maskedField.clear();
    }

    private void updateRevealButton(boolean visible) {
        String description = visible ? HIDE_PASSWORD : SHOW_PASSWORD;
        revealButton.setAccessibleText(description);
        revealButton.getTooltip().setText(description);
    }

    private void focusActiveField() {
        TextField activeField = isPasswordVisible()
            ? visibleField
            : maskedField;
        activeField.requestFocus();
        activeField.positionCaret(activeField.getLength());
    }

    private StackPane eyeGraphic() {
        SVGPath outline = new SVGPath();
        outline.setContent(
            "M1 8 C4 3 8 1 12 1 C16 1 20 3 23 8 "
                + "C20 13 16 15 12 15 C8 15 4 13 1 8 Z"
        );
        outline.setFill(Color.TRANSPARENT);
        outline.setStroke(Color.web("#43544a"));
        outline.setStrokeWidth(1.5);

        Circle pupil = new Circle(2.6, Color.web("#43544a"));
        Line slash = new Line(-8, -6, 8, 6);
        slash.setStroke(Color.web("#43544a"));
        slash.setStrokeWidth(1.8);

        StackPane graphic = new StackPane(outline, pupil, slash);
        graphic.setPrefSize(24, 16);
        slash.visibleProperty().bind(passwordVisible.not());
        return graphic;
    }
}
