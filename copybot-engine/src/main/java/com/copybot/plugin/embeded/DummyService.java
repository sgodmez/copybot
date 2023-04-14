package com.copybot.plugin.embeded;

import com.copybot.plugin.MyService;

import java.io.File;

public class DummyService implements MyService {

    @Override
    public String getName() {
        return "dummy";
    }

    @Override
    public void doManyThings(File file) {

    }
}