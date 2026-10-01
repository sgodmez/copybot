package com.copybot.ui.model;

import com.copybot.engine.plugin.CatalogAction;
import com.copybot.ui.model.PipelineDocument.Section;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The header of the plan view (spec desktop-ui §2): the displayed names of the steps, the source path of
 * file.read, the output pattern of file.write and the resume mode. Every label falls back to the raw value
 * (action code, resume mode as written), never to a "%key".
 *
 * @param sourcePath the "path" of the first embedded file.read step, null without one
 * @param outPattern the "outPattern" of an embedded file.write output step, null without one
 * @param resumeMode the effective resume mode as written, null without "resume" block
 */
public record PipelineSummary(List<String> stepNames, String sourcePath, String outPattern, String resumeMode) {

    public PipelineSummary {
        stepNames = List.copyOf(stepNames);
    }

    public static PipelineSummary of(PipelineDocument document, StepCatalog catalog) {
        List<String> names = new ArrayList<>();
        String sourcePath = null;
        String outPattern = null;
        for (Section section : Section.values()) {
            for (JsonObject step : document.steps(section)) {
                Optional<CatalogAction> action = catalog.find(section, step);
                names.add(action.map(CatalogAction::name).filter(n -> !n.isBlank())
                        .orElseGet(() -> actionCode(step)));
                if (action.isPresent() && action.get().isEmbedded()) {
                    String code = action.get().actionCode();
                    if (sourcePath == null && section == Section.IN && code.equals("file.read")) {
                        sourcePath = configString(step, "path");
                    }
                    if (section == Section.OUT && code.equals("file.write")) {
                        outPattern = configString(step, "outPattern");
                    }
                }
            }
        }
        return new PipelineSummary(names, sourcePath, outPattern, document.resumeMode().orElse(null));
    }

    private static String actionCode(JsonObject step) {
        JsonElement action = step.get("action");
        return action != null && action.isJsonPrimitive() ? action.getAsString() : "?";
    }

    private static String configString(JsonObject step, String member) {
        JsonElement config = step.get("actionConfig");
        if (config == null || !config.isJsonObject()) {
            return null;
        }
        JsonElement value = config.getAsJsonObject().get(member);
        return value != null && value.isJsonPrimitive() ? value.getAsString() : null;
    }
}
