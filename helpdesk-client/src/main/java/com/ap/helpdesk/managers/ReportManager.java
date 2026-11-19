package com.ap.helpdesk.managers;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.beans.property.ReadOnlyStringWrapper;
import javafx.fxml.FXML;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** Loads reports using the administrator's existing session and displays them in JavaFX. */
final class ReportManager {
    private final Scene scene;
    private final AccountClient session;
    private Runnable onBack;
    private ReportView activeView;

    @FXML private HBox reportFilters;
    @FXML private GridPane reportMetrics;
    @FXML private Label tableTitle;
    @FXML private Label workloadPlaceholder;
    @FXML private Label ticketsPlaceholder;
    @FXML private Button workloadButton;
    @FXML private DatePicker fromDate;
    @FXML private DatePicker toDate;
    @FXML private Label reportMessage;
    @FXML private Label submittedTotal;
    @FXML private Label resolvedTotal;
    @FXML private Label openTotal;
    @FXML private Label averageResolution;
    @FXML private TableView<WorkloadRow> workloadTable;
    @FXML private TableColumn<WorkloadRow, String> technicianColumn;
    @FXML private TableColumn<WorkloadRow, Long> assignmentColumn;
    @FXML private TableView<TicketRow> reportTickets;
    @FXML private TableColumn<TicketRow, Long> ticketIdColumn;
    @FXML private TableColumn<TicketRow, String> subjectColumn;
    @FXML private TableColumn<TicketRow, String> priorityColumn;
    @FXML private TableColumn<TicketRow, String> statusColumn;
    @FXML private TableColumn<TicketRow, Double> resolutionColumn;

    ReportManager(Scene scene, AccountClient session) {
        this.scene = scene;
        this.session = session;
    }

    /** Opens the report screen and loads the all-time summary and current workload. */
    void show(Runnable onBack) {
        this.onBack = onBack;
        FXMLLoader loader = new FXMLLoader(getClass().getResource("reports.fxml"));
        loader.setController(this);
        try {
            scene.setRoot(loader.load());
        } catch (IOException exception) {
            throw new UncheckedIOException("Could not load the reports screen", exception);
        }
        showWorkload();
    }

    @FXML
    private void initialize() {
        reportMessage.visibleProperty().bind(Bindings.isNotEmpty(reportMessage.textProperty()));
        reportMessage.managedProperty().bind(reportMessage.visibleProperty());
        technicianColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().fullName()));
        assignmentColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().openTickets()));
        ticketIdColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().ticketId()));
        subjectColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().subject()));
        priorityColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().priority()));
        statusColumn.setCellValueFactory(cell -> new ReadOnlyStringWrapper(cell.getValue().status()));
        resolutionColumn.setCellValueFactory(cell -> new ReadOnlyObjectWrapper<>(cell.getValue().resolutionSeconds()));
        resolutionColumn.setCellFactory(column -> new TableCell<>() {
            @Override
            protected void updateItem(Double seconds, boolean empty) {
                super.updateItem(seconds, empty);
                setText(empty ? null : formatDuration(seconds));
            }
        });
        workloadTable.managedProperty().bind(workloadTable.visibleProperty());
        reportTickets.managedProperty().bind(reportTickets.visibleProperty());
        workloadButton.managedProperty().bind(workloadButton.visibleProperty());
    }

    @FXML
    private void showSubmitted() {
        selectView(ReportView.SUBMITTED);
    }

    @FXML
    private void showResolved() {
        selectView(ReportView.RESOLVED);
    }

    @FXML
    private void showOpen() {
        selectView(ReportView.OPEN);
    }

    @FXML
    private void showResolutionTimes() {
        selectView(ReportView.RESOLUTION_TIMES);
    }

    @FXML
    private void showWorkload() {
        selectView(ReportView.WORKLOAD);
    }

    /** Swaps the report table in place and loads rows for the current dates. */
    private void selectView(ReportView view) {
        activeView = view;
        tableTitle.setText(view.title);
        boolean workload = view == ReportView.WORKLOAD;
        workloadTable.setVisible(workload);
        reportTickets.setVisible(!workload);
        workloadButton.setVisible(!workload);
        // Only the resolution-time view needs the per-ticket duration column.
        resolutionColumn.setVisible(view == ReportView.RESOLUTION_TIMES);
        refreshReports();
    }

    /** Refreshes the summary and the selected table using the current dates. */
    @FXML
    private void refreshReports() {
        LocalDate from = fromDate.getValue();
        LocalDate to = toDate.getValue();
        ReportView selected = activeView;
        Label placeholder = selected == ReportView.WORKLOAD ? workloadPlaceholder : ticketsPlaceholder;
        if (from != null && to != null && from.isAfter(to)) {
            clearResults();
            placeholder.setText("Choose a valid date range");
            reportMessage.setText("The start date must be before or on the end date.");
            return;
        }

        Parent view = scene.getRoot();
        clearResults();
        placeholder.setText("Loading report…");
        setLoading(true);
        reportMessage.setText("Loading reports…");

        String summaryPath = reportPath("/api/reports/tickets", null, from, to);
        // Technician workload is current. The other views use the selected date range.
        String tablePath = selected == ReportView.WORKLOAD ? "/api/reports/technician-workload"
                : reportPath("/api/reports/tickets/details", selected.metric, from, to);
        var summary = session.get(summaryPath).thenApply(ReportManager::readSummary);
        var rows = session.get(tablePath).thenApply(body -> readTable(body, selected));
        CompletableFuture.allOf(summary, rows).whenComplete((ignored, failure) -> Platform.runLater(() -> {
            // The administrator may have left the screen while the requests were running.
            if (scene.getRoot() != view) {
                return;
            }
            setLoading(false);
            reportMessage.setText("");

            // A failed detail request should leave successful summary totals visible.
            if (summary.isCompletedExceptionally()) {
                reportMessage.setText("Could not load report totals. " + AccountClient.messageFor(summary.exceptionNow()));
            } else {
                displaySummary(summary.resultNow());
            }
            if (rows.isCompletedExceptionally()) {
                placeholder.setText("Could not load this report");
                reportMessage.setText(AccountClient.messageFor(rows.exceptionNow()));
            } else {
                reportTickets.getItems().setAll(rows.resultNow().tickets());
                workloadTable.getItems().setAll(rows.resultNow().workload());
                placeholder.setText(selected == ReportView.WORKLOAD
                        ? "No technician accounts found" : "No tickets match this report");
            }
        }));
    }

    @FXML
    private void clearDates() {
        fromDate.setValue(null);
        toDate.setValue(null);
        refreshReports();
    }

    @FXML
    private void backToTickets() {
        onBack.run();
    }

    private void setLoading(boolean loading) {
        reportFilters.setDisable(loading);
        reportMetrics.setDisable(loading);
        workloadButton.setDisable(loading);
    }

    private void displaySummary(ReportSummary summary) {
        submittedTotal.setText(Long.toString(summary.submitted()));
        resolvedTotal.setText(Long.toString(summary.resolved()));
        openTotal.setText(Long.toString(summary.open()));
        averageResolution.setText(formatDuration(summary.averageSeconds()));
    }

    private void clearResults() {
        submittedTotal.setText("—");
        resolvedTotal.setText("—");
        openTotal.setText("—");
        averageResolution.setText("—");
        workloadTable.getItems().clear();
        reportTickets.getItems().clear();
    }

    private static String reportPath(String path, String metric, LocalDate from, LocalDate to) {
        List<String> parameters = new ArrayList<>();
        if (metric != null) {
            parameters.add("metric=" + metric);
        }
        if (from != null) {
            parameters.add("from=" + from);
        }
        if (to != null) {
            parameters.add("to=" + to);
        }
        return path + (parameters.isEmpty() ? "" : "?" + String.join("&", parameters));
    }

    private static ReportSummary readSummary(String body) {
        JsonObject summary = JsonParser.parseString(body).getAsJsonObject();
        return new ReportSummary(summary.get("submitted").getAsLong(), summary.get("resolved").getAsLong(),
                summary.get("open").getAsLong(), optionalNumber(summary, "averageResolutionSeconds"));
    }

    /** Reads the rows for the selected report table. */
    private static ReportRows readTable(String tableBody, ReportView selected) {
        List<WorkloadRow> workload = new ArrayList<>();
        List<TicketRow> tickets = new ArrayList<>();
        for (JsonElement entry : JsonParser.parseString(tableBody).getAsJsonArray()) {
            JsonObject row = entry.getAsJsonObject();
            if (selected == ReportView.WORKLOAD) {
                workload.add(new WorkloadRow(row.get("fullName").getAsString(), row.get("openTickets").getAsLong()));
            } else {
                tickets.add(new TicketRow(row.get("ticketId").getAsLong(), row.get("subject").getAsString(),
                        row.get("priority").getAsString(), row.get("status").getAsString(),
                        optionalNumber(row, "resolutionSeconds")));
            }
        }
        return new ReportRows(workload, tickets);
    }

    private static Double optionalNumber(JsonObject row, String field) {
        JsonElement value = row.get(field);
        return value == null || value.isJsonNull() ? null : value.getAsDouble();
    }

    private static String formatDuration(Double seconds) {
        if (seconds == null) {
            return "—";
        }
        long elapsed = Math.round(seconds);
        return String.format(Locale.ROOT, "%dh %02dm %02ds", elapsed / 3600, elapsed % 3600 / 60, elapsed % 60);
    }

    private enum ReportView {
        WORKLOAD("Current technician workload", null),
        SUBMITTED("Tickets submitted", "SUBMITTED"),
        RESOLVED("Tickets resolved", "RESOLVED"),
        OPEN("Open tickets", "OPEN"),
        RESOLUTION_TIMES("Resolution time by ticket", "RESOLVED");

        private final String title;
        private final String metric;

        ReportView(String title, String metric) {
            this.title = title;
            this.metric = metric;
        }
    }

    private record WorkloadRow(String fullName, long openTickets) {}

    private record TicketRow(long ticketId, String subject, String priority, String status, Double resolutionSeconds) {}

    private record ReportSummary(long submitted, long resolved, long open, Double averageSeconds) {}

    private record ReportRows(List<WorkloadRow> workload, List<TicketRow> tickets) {}
}
