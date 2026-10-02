package com.copybot.ui.model;

import com.copybot.engine.plugin.CatalogAction;
import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.FieldKind;
import com.copybot.ui.model.PipelineDocument.Section;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The header of the plan view (spec desktop-ui §2): the steps with their configured settings, the source
 * path of file.read, the output pattern of file.write and the resume mode. Every label falls back to the raw
 * value (action code, resume mode as written), never to a "%key".
 *
 * @param steps      the steps in pipeline order (input, analyse, process, output)
 * @param sourcePath the "path" of the first embedded file.read step, null without one
 * @param outPattern the "outPattern" of an embedded file.write output step, null without one
 * @param resumeMode the effective resume mode as written, null without "resume" block
 */
public record PipelineSummary(List<Step> steps, String sourcePath, String outPattern, String resumeMode) {

    /** The shown value of a source or destination the pipeline does not describe. */
    public static final String NONE = "—";

    /**
     * One step of the pipeline.
     *
     * @param name     the localized action name, the action code of a missing plugin
     * @param settings the configured fields (empty ones left out), in schema order
     */
    public record Step(Section section, String name, List<Setting> settings) {
        public Step {
            settings = List.copyOf(settings);
        }
    }

    /** One configured field of a step: its localized label and its text. */
    public record Setting(String label, String value) {
    }

    public PipelineSummary {
        steps = List.copyOf(steps);
    }

    /** The localized names of the steps, in pipeline order. */
    public List<String> stepNames() {
        return steps.stream().map(Step::name).toList();
    }

    /** The source path, else the name of the first input step, else "—". */
    public String sourceText() {
        return sourcePath != null ? sourcePath : firstName(Section.IN);
    }

    /** The output pattern, else the name of the output step, else "—". */
    public String destinationText() {
        return outPattern != null ? outPattern : firstName(Section.OUT);
    }

    private String firstName(Section section) {
        return steps.stream().filter(step -> step.section() == section).findFirst().map(Step::name).orElse(NONE);
    }

    public static PipelineSummary of(PipelineDocument document, StepCatalog catalog) {
        List<Step> steps = new ArrayList<>();
        String sourcePath = null;
        String outPattern = null;
        for (Section section : Section.values()) {
            for (JsonObject step : document.steps(section)) {
                Optional<CatalogAction> action = catalog.find(section, step);
                String name = action.map(CatalogAction::name).filter(n -> !n.isBlank())
                        .orElseGet(() -> actionCode(step));
                steps.add(new Step(section, name, settings(document, step, action.orElse(null))));
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
        return new PipelineSummary(steps, sourcePath, outPattern, document.resumeMode().orElse(null));
    }

    /** The leaf fields of the schema that have a value; the raw actionConfig members without schema. */
    private static List<Setting> settings(PipelineDocument document, JsonObject step, CatalogAction action) {
        List<Setting> settings = new ArrayList<>();
        if (action != null && action.configSchema().isPresent()) {
            for (ConfigField field : action.configSchema().get().allFields()) {
                if (field.kind() == FieldKind.RECORD) {
                    continue; // shown through its leaf fields
                }
                String value = document.configText(step, field).strip().replaceAll("\\s*\\R\\s*", ", ");
                if (!value.isEmpty()) {
                    settings.add(new Setting(action.label(field), value));
                }
            }
            return settings;
        }
        JsonElement config = step.get("actionConfig");
        if (config != null && config.isJsonObject()) {
            for (Map.Entry<String, JsonElement> member : config.getAsJsonObject().entrySet()) {
                JsonElement value = member.getValue();
                String text = value == null || value.isJsonNull() ? ""
                        : value.isJsonPrimitive() ? value.getAsString() : value.toString();
                if (!text.isBlank()) {
                    settings.add(new Setting(member.getKey(), text));
                }
            }
        }
        return settings;
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
