package com.copybot.plugin.action;

import com.copybot.plugin.exception.ActionErrorException;

/**
 * @param actionCode Action code is used for reference in the pipeline JSON.
 *                   Display name and description are i18n derived from this code as :
 *                   - display name : plugin.pluginCode.actionCode.name
 *                   - description : plugin.pluginCode.actionCode.description
 */
public record ActionDefinition<A extends IAction>(
        String actionCode,
        Class<A> actionClass,
        boolean isParallelizable) {
    public IAction getInstance() throws ActionErrorException {
        try {
            return actionClass.getConstructor().newInstance();
        } catch (Exception e) {
            throw new ActionErrorException(e);
        }
    }
}
