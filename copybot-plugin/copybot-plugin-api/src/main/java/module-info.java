module com.copybot.plugin.api {
    requires com.google.gson;

    requires static transitive javafx.controls;
    requires static transitive javafx.fxml;

    exports com.copybot.plugin.action;
    exports com.copybot.plugin.definition;
    exports com.copybot.plugin.exception;
}