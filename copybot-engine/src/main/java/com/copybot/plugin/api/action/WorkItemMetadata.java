package com.copybot.plugin.api.action;

import com.copybot.utils.FileUtil;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

public record WorkItemMetadata(
        Map<String, Object> raw,
        Map<String, String> display
) {

    public WorkItemMetadata() {
        this(new HashMap<>(), new HashMap<>());
    }

    public void setSize(long size) {
        raw.put("size", size);
        display.put("size", String.valueOf(size));
        display.put("sizeHr", FileUtil.toAutoUnitSize(size, 2));
    }

    public Long getSize() {
        return (Long) raw.getOrDefault("size", null);
    }

    public String getSizeHr() {
        return display.getOrDefault("sizeHr", "?");
    }

    public void setTime(String key, Instant time) {
        raw.put("key", time);

        var localDateTime = LocalDateTime.ofInstant(time, ZoneOffset.systemDefault());
        display.put(key + ".Y", String.format("%04d", localDateTime.getYear()));
        display.put(key + ".y", String.format("%02d", localDateTime.getYear() % 100));
        display.put(key + ".m", String.format("%02d", localDateTime.getMonthValue()));
        display.put(key + ".D", String.format("%02d", localDateTime.getDayOfMonth()));
        // TODO : more !
    }
}
