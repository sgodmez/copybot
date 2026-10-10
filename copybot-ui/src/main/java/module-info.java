module com.copybot.ui {
    requires javafx.controls;
    requires javafx.fxml;
    requires java.prefs;
    requires jdk.management; // the CPU load shown while a plan runs
    requires com.google.gson;

    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;

    requires com.copybot.engine;

    opens com.copybot.ui to javafx.fxml, javafx.base;
    exports com.copybot.ui to javafx.graphics;

    opens com.copybot.ui.i18n;
}