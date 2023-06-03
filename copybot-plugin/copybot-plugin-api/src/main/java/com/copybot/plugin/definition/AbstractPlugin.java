package com.copybot.plugin.definition;

import java.util.List;
import java.util.ResourceBundle;

public abstract class AbstractPlugin implements IPlugin {

    private ResourceBundle resourceBundle;

    @Override
    public Iterable<String> getI18nBundleNames() {
        return List.of(this.getClass().getPackageName() + ".i18n.pluginBundle");
    }

    @Override
    public ResourceBundle getResourceBundle() {
        return resourceBundle;
    }

    @Override
    public void setResourceBundle(ResourceBundle resourceBundle) {
        this.resourceBundle = resourceBundle;
    }
}
