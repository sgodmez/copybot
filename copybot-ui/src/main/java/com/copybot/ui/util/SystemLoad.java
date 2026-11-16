package com.copybot.ui.util;

import java.lang.management.ManagementFactory;

/** The CPU load of the machine and of Copybot, 0 to 1, negative when the system does not tell it. */
public final class SystemLoad {

    private static final java.lang.management.OperatingSystemMXBean OS = ManagementFactory.getOperatingSystemMXBean();

    private SystemLoad() {
    }

    public static double machine() {
        return OS instanceof com.sun.management.OperatingSystemMXBean os ? os.getCpuLoad() : -1;
    }

    public static double process() {
        return OS instanceof com.sun.management.OperatingSystemMXBean os ? os.getProcessCpuLoad() : -1;
    }
}
