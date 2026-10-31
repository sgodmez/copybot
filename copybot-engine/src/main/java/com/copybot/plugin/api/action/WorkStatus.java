package com.copybot.plugin.api.action;

/**
 * The progress of one item in its current step ({@link ItemProgress}).
 *
 * @param actionName    what the action is doing, for the user; null in a report: the one of the previous report
 * @param actionPercent from 0 to 100; negative: running, progress unknown
 */
public record WorkStatus(
        String actionName,
        int actionPercent) {

}
