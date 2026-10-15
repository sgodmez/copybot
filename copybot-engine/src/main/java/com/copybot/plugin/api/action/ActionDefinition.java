package com.copybot.plugin.api.action;

import com.copybot.exception.CopybotException;

/**
 * @param actionCode Action code is used for reference in the pipeline JSON.
 *                   Display name and description are i18n derived from this code as :
 *                   - display name : plugin.pluginCode.actionCode.name
 *                   - description : plugin.pluginCode.actionCode.description
 * @param actionClass the action, with a public no-argument constructor
 */
public record ActionDefinition<A extends IAction>(
        String actionCode,
        Class<A> actionClass) {

    /** A new instance of the action. */
    public A getInstance() {
        try {
            return actionClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw CopybotException.wrapIfNeeded(e);
        }
    }
}
