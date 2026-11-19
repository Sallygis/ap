package com.ap.helpdesk.managers;

import javafx.beans.binding.Bindings;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.layout.GridPane;
import javafx.stage.Window;

import java.util.Optional;

/** Collects a new password for a self-service change or administrator reset. */
final class PasswordDialog {
    private PasswordDialog() {}

    static Optional<PasswordChange> show(Window owner, String resetAccountName) {
        boolean resetting = resetAccountName != null;
        Dialog<PasswordChange> dialog = new Dialog<>();
        dialog.initOwner(owner);
        dialog.setTitle(resetting ? "Reset password" : "Change password");
        if (resetting) {
            dialog.setHeaderText(resetAccountName);
        }

        GridPane form = new GridPane();
        form.setHgap(10);
        form.setVgap(10);
        PasswordField current = new PasswordField();
        PasswordField replacement = new PasswordField();
        PasswordField confirmation = new PasswordField();
        int row = 0;
        if (!resetting) {
            addField(form, row++, "Current password", current);
        }
        addField(form, row++, "New password", replacement);
        addField(form, row++, "Confirm new password", confirmation);
        Label hint = new Label("Use 8 to 256 characters. Both entries must match.");
        form.add(hint, 0, row, 2, 1);

        ButtonType save = new ButtonType(resetting ? "Reset password" : "Change password", ButtonBar.ButtonData.OK_DONE);
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(Bindings.createBooleanBinding(() -> {
            String password = replacement.getText();
            return (!resetting && (current.getText().isBlank() || current.getLength() > 256))
                    || password.isBlank() || password.length() < 8 || password.length() > 256
                    || !password.equals(confirmation.getText());
        }, current.textProperty(), replacement.textProperty(), confirmation.textProperty()));
        dialog.setResultConverter(button -> button == save
                ? new PasswordChange(current.getText(), replacement.getText()) : null);
        try {
            return dialog.showAndWait();
        } finally {
            current.clear();
            replacement.clear();
            confirmation.clear();
        }
    }

    private static void addField(GridPane form, int row, String text, PasswordField field) {
        Label label = new Label(text);
        label.setLabelFor(field);
        form.addRow(row, label, field);
    }

    record PasswordChange(String currentPassword, String newPassword) {}
}
