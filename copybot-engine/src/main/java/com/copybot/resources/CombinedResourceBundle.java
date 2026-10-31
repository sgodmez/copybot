package com.copybot.resources;

import java.text.MessageFormat;
import java.util.*;

public class CombinedResourceBundle extends ResourceBundle {

    private final CombinedResourceBundle parent;
    private final Module context;

    private List<String> bundleNames = new ArrayList<>();

    private Map<String, String> dictionary = new HashMap<>();

    public CombinedResourceBundle() {
        context = null;
        parent = null;
    }

    public CombinedResourceBundle(Module context, CombinedResourceBundle parent) {
        this.context = context;
        this.parent = parent;
    }

    public void addBundle(String baseName) {
        bundleNames.add(baseName);
        loadBundle(Locale.getDefault(), baseName);
    }

    public void load(Locale locale) {
        dictionary.clear();
        for (String basename : bundleNames) {
            loadBundle(locale, basename);
        }
    }

    private void loadBundle(Locale locale, String basename) {
        ResourceBundle rb = context == null ?
                ResourceBundle.getBundle(basename, locale) :
                ResourceBundle.getBundle(basename, locale, context);
        for (String key : rb.keySet()) {
            dictionary.put(key, rb.getString(key));
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

    public String getString(String key, Object... args) {
        return MessageFormat.format(getString(key), args);
    }
}
