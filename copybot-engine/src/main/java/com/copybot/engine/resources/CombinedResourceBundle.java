package com.copybot.engine.resources;

import java.util.*;

public class CombinedResourceBundle extends ResourceBundle {

    private List<String> bundleNames = new ArrayList<>();

    private Map<String, String> dictionary = new HashMap<>();

    public void addBundle(String baseName) {
        bundleNames.add(baseName);
    }

    public void load(Locale locale) {
        dictionary.clear();
        for (String basename : bundleNames) {
            ResourceBundle rb = ResourceBundle.getBundle(basename, locale);
            for (String key : rb.keySet()) {
                dictionary.put(key, rb.getString(key));
            }
        }
    }

    @Override
    protected Object handleGetObject(String key) {
        return dictionary.getOrDefault(key, '#' + key + '#');
    }

    @Override
    public Enumeration<String> getKeys() {
        return Collections.enumeration(dictionary.keySet());
    }

}
