package com.copybot.plugin.gpszone;

import com.copybot.plugin.api.action.ActionDefinition;
import com.copybot.plugin.api.action.IAnalyzeAction;
import com.copybot.plugin.api.definition.AbstractPlugin;
import com.copybot.plugin.gpszone.actions.CheckGpsZone;

import java.util.List;

public class GpsZonePlugin extends AbstractPlugin {

    @Override
    public String getPluginCode() {
        return "gps-zone";
    }

    @Override
    public List<ActionDefinition<? extends IAnalyzeAction>> getAnalyzeActions() {
        return List.of(
                new ActionDefinition<>("check", CheckGpsZone.class)
        );
    }
}
