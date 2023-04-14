module com.copybot.ui {
    requires javafx.controls;
    requires javafx.fxml;

    requires org.controlsfx.controls;
    requires org.kordamp.ikonli.javafx;
    requires org.kordamp.bootstrapfx.core;

    requires com.copybot.engine;
    requires com.copybot.plugin;

    opens com.copybot.ui to javafx.fxml, javafx.base;
    exports com.copybot.ui to javafx.graphics;

}