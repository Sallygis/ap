package com.ap.helpdesk.managers;

import com.ap.helpdesk.classes.Requester;
import com.ap.helpdesk.classes.User;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.util.List;
import java.util.Optional;

/** Lets an administrator select an account to change its role or reset its password. */
final class ManageAccountsDialog {
    private ManageAccountsDialog() {}

    /** Returns the action chosen for the selected account. */
    static Optional<AccountAction> show(Window owner, List<User> users, long signedInId) {
        TableView<User> table = new TableView<>();
        table.getItems().setAll(users);
        table.setPlaceholder(new Label("No accounts found"));
        table.setColumnResizePolicy(TableView.CONSTRAINED_RESIZE_POLICY_FLEX_LAST_COLUMN);
        table.setPrefSize(590, 300);

        TableColumn<User, String> name = new TableColumn<>("Name");
        name.setPrefWidth(180);
        name.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getFullName()));
        TableColumn<User, String> email = new TableColumn<>("Email");
        email.setPrefWidth(235);
        email.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getEmail()));
        TableColumn<User, String> role = new TableColumn<>("Role");
        role.setMinWidth(120);
        role.setCellValueFactory(cell -> new ReadOnlyStringWrapper(currentRole(cell.getValue()).toString()));
        table.getColumns().add(name);
        table.getColumns().add(email);
        table.getColumns().add(role);

        ComboBox<Choice> choices = new ComboBox<>();
        choices.getItems().setAll(Choice.values());
        Label roleLabel = new Label("New role");
        roleLabel.setLabelFor(choices);

        var selected = table.getSelectionModel().selectedItemProperty();
        var cannotEdit = Bindings.createBooleanBinding(
                () -> selected.get() == null || selected.get().getId() == signedInId, selected);
        choices.disableProperty().bind(cannotEdit);
        selected.addListener((property, previous, account) ->
                choices.setValue(account == null ? null : currentRole(account)));

        Dialog<AccountAction> dialog = new Dialog<>();
        dialog.setTitle("Manage accounts");
        dialog.initOwner(owner);
        dialog.getDialogPane().setContent(new VBox(10, table, roleLabel, choices));
        ButtonType save = new ButtonType("Save role", ButtonBar.ButtonData.OK_DONE);
        ButtonType reset = new ButtonType("Reset password", ButtonBar.ButtonData.OTHER);
        dialog.getDialogPane().getButtonTypes().setAll(save, reset, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(cannotEdit.or(choices.valueProperty().isNull()));
        dialog.getDialogPane().lookupButton(reset).disableProperty().bind(cannotEdit);
        dialog.setResultConverter(button -> {
            if (button == save) {
            	return new RoleChange(selected.get().getId(), choices.getValue().role);
            }
            return button == reset ? new PasswordReset(selected.get()) : null;
        });
        return dialog.showAndWait();
    }

    private static Choice currentRole(User user) {
        return Choice.valueOf(user.getRole().name());
    }

    sealed interface AccountAction permits RoleChange, PasswordReset {}

    record RoleChange(long userId, User.Role role) implements AccountAction {}
    
    record PasswordReset(User account) implements AccountAction {}

    private enum Choice {
        STUDENT("Student", User.Role.STUDENT),
        STAFF("Staff", User.Role.STAFF),
        TECHNICIAN("Technician", User.Role.TECHNICIAN),
        ADMINISTRATOR("Administrator", User.Role.ADMINISTRATOR);

        private final String label;
        private final User.Role role;

        Choice(String label, User.Role role) {
            this.label = label;
            this.role = role;
        }

        @Override
        public String toString() {
            return label;
        }
    }
}
