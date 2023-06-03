package com.copybot.engine.resources;

import com.copybot.plugin.definition.IPlugin;

import java.util.*;

public class CombinedResourceBundle extends ResourceBundle {

    private final CombinedResourceBundle parent;
    private final IPlugin context;

    private List<String> bundleNames = new ArrayList<>();

    private Map<String, String> dictionary = new HashMap<>();

    public CombinedResourceBundle() {
        context = null;
        parent = null;
    }

    public CombinedResourceBundle(IPlugin context, CombinedResourceBundle parent) {
        this.context = context;
        this.parent = parent;
    }

    public void addBundle(String baseName) {
        bundleNames.add(baseName);
    }

    public void load(Locale locale) {
        dictionary.clear();
        for (String basename : bundleNames) {
            ResourceBundle rb = context == null ?
                    ResourceBundle.getBundle(basename, locale) :
                    ResourceBundle.getBundle(basename, locale, context.getClass().getModule());
            for (String key : rb.keySet()) {
                dictionary.put(key, rb.getString(key));
            }
        }
    }

    @Override
    protected Object handleGetObject(String key) {
        var value = dictionary.get(key);
        return value != null ?
                value :
                parent != null ?
                        parent.handleGetObject(key) :
                        '%' + key;
    }

    @Override
    public Enumeration<String> getKeys() {
        return Collections.enumeration(dictionary.keySet());
    }

    @Override
    public boolean containsKey(String key) {
        return true; // no exceptions, outputs %key if not found
    }
}
