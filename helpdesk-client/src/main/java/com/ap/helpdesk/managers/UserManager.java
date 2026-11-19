package com.ap.helpdesk.managers;

import com.ap.helpdesk.App;
import com.ap.helpdesk.classes.Administrator;
import com.ap.helpdesk.classes.Requester;
import com.ap.helpdesk.classes.Technician;
import com.ap.helpdesk.classes.User;
import javafx.application.Platform;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.stage.Stage;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** Controls sign-in, registration, and dashboard navigation. */
public final class UserManager {
    private static final Logger LOG = LogManager.getLogger(UserManager.class);
    private static final Pattern EMAIL_FORMAT = Pattern.compile("[^\\s@]+@[^\\s@]+");

    private final Scene scene;
    private final TicketManager ticketManager;
    private final AccountClient accounts = new AccountClient();
    private final Map<String, User> demoAccounts = new LinkedHashMap<>();

    @FXML
    private TextField emailField;
    @FXML
    private PasswordField passwordField;
    @FXML
    private Label formMessage;
    @FXML
    private TextField fullNameField;
    @FXML
    private ComboBox<String> requesterKindSelector;

    public UserManager(Scene scene) {
        this.scene = scene;
        Requester student = new Requester(1, "Demo Student", "student@example.com", Requester.Kind.STUDENT);
        Requester staff = new Requester(2, "Demo Staff", "staff@example.com", Requester.Kind.STAFF);
        Technician technician = new Technician(3, "Demo Technician", "technician@example.com");

        // Demo accounts are local examples and have no server session.
        demoAccounts.put("Student", student);
        demoAccounts.put("Staff", staff);
        demoAccounts.put("Technician", technician);
        demoAccounts.put("Administrator", new Administrator(4, "Demo Administrator", "admin@example.com"));
        ticketManager = new TicketManager(scene, accounts, new TicketClient(accounts), new ReportManager(scene, accounts),
                student, staff, technician);
    }

    /** Returns to the sign-in form. */
    @FXML
    public void showLogin() {
        ticketManager.stopUpdates();
        showScreen("login.fxml");
        if (scene.getWindow() instanceof Stage stage) {
            stage.setTitle(App.WINDOW_TITLE);
        }
        fullNameField = null;
        requesterKindSelector = null;
    }

    /** Opens public registration for students and staff. */
    @FXML
    private void showRegistration() {
        showScreen("register.fxml");
        requesterKindSelector.getItems().setAll("Student", "Staff");
    }

    /** Loads a form and connects its controls to this manager. */
    private void showScreen(String resource) {
        if (passwordField != null) {
            passwordField.clear();
        }

        FXMLLoader loader = new FXMLLoader(getClass().getResource(resource));
        loader.setController(this);
        try {
            scene.setRoot(loader.load());
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load " + resource, exception);
        }
    }

    /** Validates the email and password fields shared by both forms. */
    private boolean validateCredentials() {
        String email = emailField.getText().strip();
        if (email.length() > 254 || !EMAIL_FORMAT.matcher(email).matches()) {
            formMessage.setText("Enter a valid email address.");
            return false;
        }
        if (passwordField.getText().isBlank()) {
            formMessage.setText("Enter your password.");
            return false;
        }
        if (passwordField.getText().length() > 256) {
            formMessage.setText("Use a password with at most 256 characters.");
            return false;
        }
        return true;
    }

    /** Submits credentials to the server after checking the form. */
    @FXML
    private void signIn() {
        if (validateCredentials()) {
            openAccount(accounts.signIn(emailField.getText().strip(), passwordField.getText()), "Signing in…");
        }
    }

    /** Registers a student or staff account and opens its dashboard. */
    @FXML
    private void register() {
        String name = fullNameField.getText().strip();
        if (name.isEmpty() || name.length() > 120) {
            formMessage.setText("Enter your full name using at most 120 characters.");
            return;
        }
        if (!validateCredentials()) {
            return;
        }
        String password = passwordField.getText();
        if (password.length() < 8) {
            formMessage.setText("Use at least 8 characters for your password.");
            return;
        }
        String selectedKind = requesterKindSelector.getValue();
        if (selectedKind == null) {
            formMessage.setText("Select Student or Staff.");
            return;
        }

        Requester.Kind kind = Requester.Kind.valueOf(selectedKind.toUpperCase(Locale.ROOT));
        openAccount(accounts.register(name, emailField.getText().strip(), password, kind), "Creating account…");
    }

    /** Keeps the form responsive while the HTTP request is running. */
    private void openAccount(CompletableFuture<User> result, String pendingMessage) {
        Parent form = scene.getRoot();
        passwordField.clear();
        formMessage.setText(pendingMessage);
        form.setDisable(true);

        // HTTP completes on a background thread. Only the JavaFX thread changes the screen.
        result.whenComplete((user, failure) -> Platform.runLater(() -> {
            form.setDisable(false);
            if (failure != null) {
                formMessage.setText(AccountClient.messageFor(failure));
                return;
            }
            LOG.info("Opened the {} dashboard for user {}", user.getRole(), user.getId());
            // The role returned by the server decides which dashboard opens.
            ticketManager.showDashboard(user, this::signOut, false);
        }));
    }

    /** Ends the current server session and returns to sign-in. */
    private void signOut() {
        Parent dashboard = scene.getRoot();
        dashboard.setDisable(true);
        accounts.signOut().whenComplete((ignored, failure) -> Platform.runLater(() -> {
            dashboard.setDisable(false);
            if (failure != null) {
                Alert alert = new Alert(Alert.AlertType.ERROR, AccountClient.messageFor(failure));
                alert.initOwner(scene.getWindow());
                alert.showAndWait();
                return;
            }
            LOG.info("Signed out of the help desk client");
            showLogin();
        }));
    }

    /** Opens the in-memory ticket dashboard for a sample account. */
    @FXML
    private void showDemo() {
        ChoiceDialog<String> dialog = new ChoiceDialog<>("Student", demoAccounts.keySet());
        dialog.initOwner(scene.getWindow());
        dialog.setTitle("Try the demo");
        dialog.setHeaderText("Choose a demo account");
        dialog.setContentText("Account type");
        dialog.showAndWait().ifPresent(account -> {
            passwordField.clear();
            ticketManager.showDashboard(demoAccounts.get(account), this::showLogin, true);
        });
    }
}
