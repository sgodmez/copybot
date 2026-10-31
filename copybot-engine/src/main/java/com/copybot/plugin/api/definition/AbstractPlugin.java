package com.copybot.plugin.api.definition;

import com.copybot.resources.CombinedResourceBundle;

import java.util.List;

public abstract class AbstractPlugin implements IPlugin {

    private CombinedResourceBundle resourceBundle;

    @Override
    public Iterable<String> getI18nBundleNames() {
        return List.of(this.getClass().getPackageName() + ".i18n.pluginBundle");
    }

    @Override
    public CombinedResourceBundle getResourceBundle() {
        return resourceBundle;
    }

    @Override
    public void setResourceBundle(CombinedResourceBundle resourceBundle) {
        this.resourceBundle = resourceBundle;
    }
}
