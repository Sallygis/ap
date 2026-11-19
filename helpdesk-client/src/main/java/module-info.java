/** Declares the modules used by the JavaFX client. */
module com.ap.helpdesk {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.net.http;
    requires com.google.gson;
    requires org.apache.logging.log4j;
    requires org.apache.logging.log4j.core;

    opens com.ap.helpdesk to javafx.fxml;
    // FXMLLoader needs access to the controllers' private @FXML fields and methods.
    opens com.ap.helpdesk.managers to javafx.fxml;
    exports com.ap.helpdesk;
}
