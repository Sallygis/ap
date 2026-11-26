package com.ap.helpdesk.managers;

import com.ap.helpdesk.App;
import com.ap.helpdesk.classes.Requester;
import com.ap.helpdesk.classes.Technician;
import com.ap.helpdesk.classes.Ticket;
import com.ap.helpdesk.classes.Ticket.TicketStatus;
import com.ap.helpdesk.classes.User;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.animation.KeyFrame;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.geometry.Insets;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ChoiceDialog;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;
import javafx.util.Duration;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/** Displays tickets and handles actions for the signed-in account. */
public final class TicketManager {
    private static final Logger LOG = LogManager.getLogger(TicketManager.class);
    private final Scene scene;
    private final AccountClient accounts;
    private final TicketClient ticketClient;
    private final ReportManager reports;
    private final NotificationClient notifications;
    // Group bursts of socket updates into one refresh and poll the technician view as a fallback.
    private final PauseTransition backgroundRefresh = new PauseTransition(Duration.millis(250));
    private final Timeline technicianRefresh = new Timeline(
            new KeyFrame(Duration.seconds(15), event -> backgroundRefresh.playFromStart()));
    private Parent dashboard;
    private long ticketRevision;
    private final Technician demoTechnician;
    private final List<Ticket> demoTickets = new ArrayList<>();

    private User user;
    private boolean demoMode;
    private List<Ticket> tickets;
    private Runnable onLogout;
    private long nextTicketId = 1;

    @FXML private Label dashboardTitle;
    @FXML private Label accountName;
    @FXML private Label accountRole;
    @FXML private Label notificationMessage;
    @FXML private TextField searchField;
    @FXML private TableView<Ticket> ticketTable;
    @FXML private TableColumn<Ticket, Long> idColumn;
    @FXML private TableColumn<Ticket, String> subjectColumn;
    @FXML private TableColumn<Ticket, String> priorityColumn;
    @FXML private TableColumn<Ticket, TicketStatus> statusColumn;
    @FXML private TableColumn<Ticket, String> technicianColumn;
    @FXML private Button ticketAction;
    @FXML private Button openTicketButton;
    @FXML private Button reportsButton;
    @FXML private Button manageAccountsButton;
    @FXML private Button changePasswordButton;

    TicketManager(Scene scene, AccountClient accounts, TicketClient ticketClient, ReportManager reports,
            Requester student, Requester staff, Technician technician) {
        this.scene = scene;
        this.accounts = accounts;
        this.ticketClient = ticketClient;
        this.reports = reports;
        this.notifications = new NotificationClient(accounts);
        backgroundRefresh.setOnFinished(event -> refreshInBackground());
        technicianRefresh.setCycleCount(Timeline.INDEFINITE);
        this.demoTechnician = technician;
        demoTickets.add(new Ticket(nextTicketId++, student.getId(), "Cannot join campus Wi-Fi",
                "My laptop cannot connect in the library.", Instant.now(), "HIGH",
                TicketStatus.NEW, null, null, null));
        demoTickets.add(new Ticket(nextTicketId++, staff.getId(), "Printer unavailable",
                "The printer in the main office is offline.", Instant.now(), "MEDIUM",
                TicketStatus.ASSIGNED, technician.getId(), null, null));
    }

    /** Opens the dashboard and loads the tickets for this account. */
    void showDashboard(User user, Runnable onLogout, boolean demoMode) {
        stopUpdates();
        this.user = user;
        this.onLogout = onLogout;
        this.demoMode = demoMode;
        this.tickets = demoMode ? demoTickets : new ArrayList<>();

        FXMLLoader loader = new FXMLLoader(getClass().getResource("tickets.fxml"));
        loader.setController(this);
        try {
            scene.setRoot(loader.load());
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load the ticket dashboard", exception);
        }

        dashboard = scene.getRoot();
        if (scene.getWindow() instanceof Stage stage) {
            stage.setTitle(App.WINDOW_TITLE + " - " + accountRole.getText());
        }
        refreshTickets();
        if (!demoMode) {
            Parent current = dashboard;
            notifications.start(message -> Platform.runLater(() -> {
                if (dashboard != current) {
                    return;
                }
                // Some socket events only refresh the list and leave the latest banner in place.
                if (!message.equals("REFRESH")) {
                    notificationMessage.setText(message);
                }
                backgroundRefresh.playFromStart();
            }));
            if (user.getRole() == User.Role.TECHNICIAN) {
                technicianRefresh.play();
            }
        }
    }

    /** Sets up the columns, role action, and local search field. */
    @FXML
    private void initialize() {
        accountName.setText(user.getFullName());
        accountRole.setText(switch (user.getRole()) {
            case ADMINISTRATOR -> "Administrator";
            case TECHNICIAN -> "Technician";
            case STUDENT -> "Student";
            case STAFF -> "Staff";
        });
        notificationMessage.visibleProperty().bind(Bindings.isNotEmpty(notificationMessage.textProperty()));
        notificationMessage.managedProperty().bind(notificationMessage.visibleProperty());
        idColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().getTicketId()));
        subjectColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getSubject()));
        priorityColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().getPriority()));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().getStatus()));

        switch (user.getRole()) {
            case STUDENT, STAFF -> {
                dashboardTitle.setText("My tickets");
                ticketAction.setText("New ticket");
            }
            case TECHNICIAN -> {
                dashboardTitle.setText("Assigned tickets");
                ticketAction.setText("Update status");
            }
            case ADMINISTRATOR -> {
                dashboardTitle.setText("All tickets");
                ticketAction.setText("Assign ticket");
            }
        }

        boolean administrator = user.getRole() == User.Role.ADMINISTRATOR;
        technicianColumn.setVisible(administrator);
        technicianColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(technicianLabel(cell.getValue())));

        boolean liveAdministrator = !demoMode && administrator;
        reportsButton.setVisible(liveAdministrator);
        reportsButton.setManaged(liveAdministrator);
        manageAccountsButton.setVisible(liveAdministrator);
        manageAccountsButton.setManaged(liveAdministrator);
        changePasswordButton.setVisible(!demoMode);
        changePasswordButton.setManaged(!demoMode);

        var noSelection = ticketTable.getSelectionModel().selectedItemProperty().isNull();
        openTicketButton.disableProperty().bind(noSelection);
        if (!(user instanceof Requester)) {
            ticketAction.disableProperty().bind(noSelection);
        }
        searchField.textProperty().addListener((field, oldText, newText) -> applySearch());
    }

    /** Replaces the live list with tickets returned by the server. */
    @FXML
    private void refreshTickets() {
        if (demoMode) {
            applySearch();
            return;
        }
        runRequest(ticketClient.listTickets(), this::replaceTickets);
    }

    private void replaceTickets(List<Ticket> loaded) {
        ticketRevision++;
        tickets.clear();
        tickets.addAll(loaded);
        applySearch();
    }

    /** Refreshes after socket messages and periodically for technicians. */
    private void refreshInBackground() {
        Parent current = dashboard;
        if (current == null || scene.getRoot() != current) {
            return;
        }
        if (current.isDisabled()) {
            backgroundRefresh.playFromStart();
            return;
        }

        long revision = ++ticketRevision;
        ticketClient.listTickets().whenComplete((loaded, failure) -> Platform.runLater(() -> {
            // A newer refresh, saved change or sign-out supersedes this response.
            if (failure == null && dashboard == current && scene.getRoot() == current && ticketRevision == revision) {
                if (current.isDisabled()) {
                    backgroundRefresh.playFromStart();
                } else {
                    replaceTickets(loaded);
                }
            }
        }));
    }

    /** Stops the socket and refresh timers when the user leaves the dashboard. */
    void stopUpdates() {
        dashboard = null;
        notifications.stop();
        technicianRefresh.stop();
        backgroundRefresh.stop();
    }

    /** Filters already-loaded rows as the user types. */
    private void applySearch() {
        String query = searchField.getText().strip().toLowerCase(Locale.ROOT);
        Ticket selected = ticketTable.getSelectionModel().getSelectedItem();
        List<Ticket> matchingTickets = tickets.stream()
                .filter(ticket -> !demoMode || canViewDemoTicket(ticket))
                .filter(ticket -> ticket.getSubject().toLowerCase(Locale.ROOT).contains(query)
                        || Long.toString(ticket.getTicketId()).equals(query))
                .toList();
        ticketTable.getItems().setAll(matchingTickets);
        if (selected != null) {
            matchingTickets.stream()
                    .filter(ticket -> ticket.getTicketId() == selected.getTicketId())
                    .findFirst()
                    .ifPresent(ticketTable.getSelectionModel()::select);
        }
    }

    private boolean canViewDemoTicket(Ticket ticket) {
        return switch (user.getRole()) {
            case STUDENT, STAFF -> ticket.getRequesterId() == user.getId();
            case TECHNICIAN -> Long.valueOf(user.getId()).equals(ticket.getAssignedTechnicianId());
            case ADMINISTRATOR -> true;
        };
    }

    /** Opens the action available to the current role. */
    @FXML
    private void handleTicketAction() {
        switch (user.getRole()) {
            case STUDENT, STAFF -> showTicketForm(null);
            case TECHNICIAN -> updateSelectedStatus();
            case ADMINISTRATOR -> assignSelectedTicket();
        }
    }

    /** Opens the selected ticket and fetches fresh details for a real account. */
    @FXML
    private void openSelectedTicket() {
        Ticket selected = ticketTable.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        if (demoMode) {
            showTicketDetails(selected);
        } else {
            runRequest(ticketClient.getTicket(selected.getTicketId()), this::showTicketDetails);
        }
    }

    /** Shows the ticket details and actions available from the detail view. */
    private void showTicketDetails(Ticket ticket) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(scene.getWindow());
        dialog.setTitle("Ticket #" + ticket.getTicketId());
        dialog.setHeaderText(ticket.getSubject());
        TextArea description = new TextArea(ticket.getDescription());
        description.setEditable(false);
        description.setWrapText(true);
        description.setPrefRowCount(4);
        VBox content = new VBox(8,
                new Label("Status  " + ticket.getStatus()),
                new Label("Priority  " + ticket.getPriority()));
        if (user.getRole() == User.Role.ADMINISTRATOR) {
            content.getChildren().add(new Label("Technician  " + technicianLabel(ticket)));
        }
        content.getChildren().addAll(new Label("Description"), description);
        if (ticket.getResolutionNotes() != null && !ticket.getResolutionNotes().isBlank()) {
            TextArea notes = new TextArea(ticket.getResolutionNotes() == null ? "" : ticket.getResolutionNotes());
            notes.setEditable(false);
            notes.setWrapText(true);
            notes.setPrefRowCount(3);
            content.getChildren().addAll(new Label("Resolution notes"), notes);
        }

        dialog.getDialogPane().setContent(content);
        ButtonType edit = new ButtonType("Edit ticket");
        if (canEditDetails(ticket)) {
            dialog.getDialogPane().getButtonTypes().add(edit);
        }
        ButtonType comment = new ButtonType("Add comment");
        boolean reopen = ticket.getStatus() == TicketStatus.CLOSED;
        ButtonType changeStatus = new ButtonType(reopen ? "Reopen ticket" : "Close ticket");
        if (!demoMode) {
            dialog.getDialogPane().getButtonTypes().add(comment);
            if (user instanceof Requester) {
                dialog.getDialogPane().getButtonTypes().add(changeStatus);
            }
        }
        dialog.getDialogPane().getButtonTypes().add(ButtonType.CLOSE);
        dialog.showAndWait().ifPresent(action -> {
            if (action == edit) {
                showTicketForm(ticket);
            } else if (action == comment) {
                showCommentForm(ticket.getTicketId());
            } else if (action == changeStatus) {
                TicketStatus status = reopen ? TicketStatus.REOPENED : TicketStatus.CLOSED;
                runRequest(ticketClient.updateStatus(ticket.getTicketId(), status, null), this::showSavedTicket);
            }
        });
    }

    /** Uses the same assignee label in the admin table and detail dialog. */
    private String technicianLabel(Ticket ticket) {
        Long id = ticket.getAssignedTechnicianId();
        if (id == null) {
            return "Unassigned";
        }
        String name = ticket.getAssignedTechnicianName();
        if (name == null && demoMode && id == demoTechnician.getId()) {
            name = demoTechnician.getFullName();
        }
        return name == null ? "Technician #" + id : name;
    }

    /** Shows the edit action only for the owner of a new ticket. */
    private boolean canEditDetails(Ticket ticket) {
        return user instanceof Requester && ticket.getRequesterId() == user.getId()
                && ticket.getStatus() == TicketStatus.NEW;
    }

    /** Reuses the request form when the requester edits a new ticket. */
    private void showTicketForm(Ticket ticket) {
        boolean editing = ticket != null;
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(scene.getWindow());
        dialog.setTitle(editing ? "Edit ticket" : "New ticket");

        TextField subject = new TextField(editing ? ticket.getSubject() : "");
        TextArea description = new TextArea(editing ? ticket.getDescription() : "");
        description.setPrefRowCount(4);

        GridPane form = new GridPane();
        form.setPadding(new Insets(12));
        form.setHgap(12);
        form.setVgap(10);
        Label subjectLabel = new Label("Subject");
        subjectLabel.setLabelFor(subject);
        Label descriptionLabel = new Label("Description");
        descriptionLabel.setLabelFor(description);
        form.addRow(0, subjectLabel, subject);
        form.addRow(1, descriptionLabel, description);

        ComboBox<String> priority = new ComboBox<>();
        if (!editing) {
            priority.getItems().setAll("LOW", "MEDIUM", "HIGH", "URGENT");
            priority.setValue("MEDIUM");
            Label priorityLabel = new Label("Priority");
            priorityLabel.setLabelFor(priority);
            form.addRow(2, priorityLabel, priority);
        }

        ButtonType save = editing ? new ButtonType("Save changes", ButtonBar.ButtonData.OK_DONE) : ButtonType.OK;
        dialog.getDialogPane().setContent(form);
        dialog.getDialogPane().getButtonTypes().setAll(save, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(save).disableProperty().bind(
                Bindings.createBooleanBinding(
                        () -> subject.getText().isBlank() || description.getText().isBlank(),
                        subject.textProperty(), description.textProperty()));

        dialog.showAndWait().filter(save::equals).ifPresent(result -> {
            String ticketSubject = subject.getText().strip();
            String ticketDescription = description.getText().strip();
            if (editing) {
                saveTicketEdit(ticket, ticketSubject, ticketDescription);
            } else if (demoMode) {
                tickets.add(new Ticket(nextTicketId++, user.getId(), ticketSubject, ticketDescription, Instant.now(),
                        priority.getValue(), TicketStatus.NEW, null, null, null));
                applySearch();
            } else {
                runRequest(ticketClient.createTicket(ticketSubject, ticketDescription, priority.getValue()),
                        this::showSavedTicket);
            }
        });
    }

    private void saveTicketEdit(Ticket ticket, String subject, String description) {
        if (demoMode) {
            showSavedTicket(new Ticket(ticket.getTicketId(), ticket.getRequesterId(), subject, description,
                    ticket.getCreatedAt(), ticket.getPriority(), ticket.getStatus(), ticket.getAssignedTechnicianId(),
                    ticket.getAssignedTechnicianName(), ticket.getResolutionNotes(), ticket.getResolvedAt()));
        } else {
            runRequest(ticketClient.editDetails(ticket.getTicketId(), subject, description), this::showSavedTicket);
        }
    }

    /** Saves a comment from the selected ticket's detail dialog. */
    private void showCommentForm(long ticketId) {
        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(scene.getWindow());
        dialog.setTitle("Add comment");
        TextArea comment = new TextArea();
        comment.setAccessibleText("Comment");
        comment.setPrefRowCount(4);
        dialog.getDialogPane().setContent(comment);
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);
        dialog.getDialogPane().lookupButton(ButtonType.OK).disableProperty()
                .bind(Bindings.createBooleanBinding(() -> comment.getText().isBlank(), comment.textProperty()));
        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(result ->
                runRequest(ticketClient.addComment(ticketId, comment.getText().strip()),
                        ignored -> showAlert(Alert.AlertType.INFORMATION, "Comment added.")));
    }

    /** Lets a technician change the status and record resolution notes. */
    private void updateSelectedStatus() {
        Ticket ticket = ticketTable.getSelectionModel().getSelectedItem();
        if (ticket == null) {
            return;
        }

        Dialog<ButtonType> dialog = new Dialog<>();
        dialog.initOwner(scene.getWindow());
        dialog.setTitle("Update status");
        dialog.setHeaderText(ticket.getSubject());
        ComboBox<TicketStatus> status = new ComboBox<>();
        status.getItems().setAll(TicketStatus.IN_PROGRESS, TicketStatus.PENDING, TicketStatus.RESOLVED);
        status.setValue(status.getItems().contains(ticket.getStatus()) ? ticket.getStatus() : TicketStatus.IN_PROGRESS);

        TextArea notes = new TextArea(ticket.getResolutionNotes() == null ? "" : ticket.getResolutionNotes());
        notes.setPrefRowCount(4);
        dialog.getDialogPane().setContent(new VBox(8, new Label("Status"), status, new Label("Resolution notes"), notes));
        dialog.getDialogPane().getButtonTypes().setAll(ButtonType.OK, ButtonType.CANCEL);

        dialog.showAndWait().filter(ButtonType.OK::equals).ifPresent(result -> {
            String resolutionNotes = notes.getText().strip();
            if (demoMode) {
                Instant resolvedAt = ticket.getResolvedAt();
                if (status.getValue() == TicketStatus.RESOLVED && ticket.getStatus() != TicketStatus.RESOLVED) {
                    resolvedAt = Instant.now();
                }
                showSavedTicket(new Ticket(ticket.getTicketId(), ticket.getRequesterId(), ticket.getSubject(),
                        ticket.getDescription(), ticket.getCreatedAt(), ticket.getPriority(), status.getValue(),
                        ticket.getAssignedTechnicianId(), resolutionNotes, resolvedAt));
            } else {
                runRequest(ticketClient.updateStatus(ticket.getTicketId(), status.getValue(), resolutionNotes),
                        this::showSavedTicket);
            }
        });
    }

    /** Gets the technician choices before assigning a new or reopened ticket. */
    private void assignSelectedTicket() {
        Ticket ticket = ticketTable.getSelectionModel().getSelectedItem();
        if (ticket == null) {
            return;
        }
        if (ticket.getStatus() != TicketStatus.NEW && ticket.getStatus() != TicketStatus.REOPENED) {
            showAlert(Alert.AlertType.INFORMATION, "Select a new or reopened ticket to assign.");
            return;
        }

        if (demoMode) {
            showAssignmentForm(ticket, Map.of(demoTechnician.getFullName(), demoTechnician.getId()));
        } else {
            runRequest(ticketClient.listTechnicians(), choices -> showAssignmentForm(ticket, choices));
        }
    }

    /** Assigns the ticket to the technician picked from the directory. */
    private void showAssignmentForm(Ticket ticket, Map<String, Long> choices) {
        if (choices.isEmpty()) {
            showAlert(Alert.AlertType.INFORMATION, "No technician accounts are available yet.");
            return;
        }

        ChoiceDialog<String> dialog = new ChoiceDialog<>(choices.keySet().iterator().next(), choices.keySet());
        dialog.initOwner(scene.getWindow());
        dialog.setTitle("Assign ticket");
        dialog.setHeaderText(ticket.getSubject());
        dialog.setContentText("Technician");
        dialog.showAndWait().ifPresent(name -> {
            long technicianId = choices.get(name);
            if (demoMode) {
                showSavedTicket(new Ticket(ticket.getTicketId(), ticket.getRequesterId(), ticket.getSubject(),
                        ticket.getDescription(), ticket.getCreatedAt(), ticket.getPriority(), TicketStatus.ASSIGNED,
                        technicianId, ticket.getResolutionNotes(), ticket.getResolvedAt()));
            } else {
                runRequest(ticketClient.assignTicket(ticket.getTicketId(), technicianId), this::showSavedTicket);
            }
        });
    }

    /** Shows the saved ticket returned by the server or changed in the demo. */
    private void showSavedTicket(Ticket saved) {
        LOG.info("Saved ticket {} with status {}", saved.getTicketId(), saved.getStatus());
        ticketRevision++;
        for (int i = 0; i < tickets.size(); i++) {
            if (tickets.get(i).getTicketId() == saved.getTicketId()) {
                tickets.set(i, saved);
                applySearch();
                return;
            }
        }
        tickets.add(0, saved);
        applySearch();
    }

    /** Handles the asynchronous result on the JavaFX thread. */
    private <T> void runRequest(CompletableFuture<T> request, Consumer<T> onSuccess) {
        Parent dashboard = scene.getRoot();
        dashboard.setDisable(true);
        request.whenComplete((result, failure) -> Platform.runLater(() -> {
            dashboard.setDisable(false);
            if (failure == null) {
                onSuccess.accept(result);
            } else {
                showAlert(Alert.AlertType.ERROR, AccountClient.messageFor(failure));
            }
        }));
    }

    private void showAlert(Alert.AlertType type, String message) {
        Alert alert = new Alert(type, message);
        alert.initOwner(scene.getWindow());
        alert.showAndWait();
    }

    /** Lets the signed-in user change their own password. */
    @FXML
    private void changePassword() {
        PasswordDialog.show(scene.getWindow(), null)
                .ifPresent(change -> runRequest(accounts.changePassword(change.currentPassword(), change.newPassword()),
                        ignored -> showAlert(Alert.AlertType.INFORMATION, "Password changed.")));
    }

    /** Loads the account directory and performs the chosen administrator action. */
    @FXML
    private void manageAccounts() {
        runRequest(accounts.listUsers(), users -> ManageAccountsDialog.show(scene.getWindow(), users, user.getId())
                .ifPresent(action -> {
                    switch (action) {
                        case ManageAccountsDialog.RoleChange change ->
                            runRequest(accounts.changeRole(change.userId(), change.role()),
                                    saved -> showAlert(Alert.AlertType.INFORMATION,
                                            "Account role saved for " + saved.getFullName() + "."));
                        case ManageAccountsDialog.PasswordReset reset -> resetPassword(reset.account());
                    }
                }));
    }

    private void resetPassword(User account) {
        PasswordDialog.show(scene.getWindow(), account.getFullName())
                .ifPresent(change -> runRequest(accounts.resetPassword(account.getId(), change.newPassword()),
                        ignored -> showAlert(Alert.AlertType.INFORMATION, "Password reset for " + account.getFullName() + ".")));
    }

    @FXML
    private void openReports() {
        Parent dashboard = scene.getRoot();
        reports.show(() -> {
            scene.setRoot(dashboard);
            backgroundRefresh.playFromStart();
        });
    }

    @FXML
    private void logout() {
        onLogout.run();
    }
}
