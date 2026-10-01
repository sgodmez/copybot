package com.copybot.plugin.api.action;

import com.copybot.plugin.api.config.ConfigField;
import com.copybot.plugin.api.config.Required;
import com.copybot.plugin.api.definition.IPlugin;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/** Where an action's configuration schema comes from (spec desktop-ui §4). */
public class ConfigSchemaActionTest {

    record DemoConfig(@Required String target, Integer retries) {
    }

    static final class RecordConfigAction extends AbstractActionWithConfig<DemoConfig> implements IAnalyzeAction {
        @Override
        protected Class<DemoConfig> getConfigClass() {
            return DemoConfig.class;
        }

        @Override
        public void doAnalyze(WorkItem item) {
        }
    }

    /** A configuration that is a plain class: no introspection. */
    static final class PlainConfig {
        String target;
    }

    static final class PlainConfigAction extends AbstractActionWithConfig<PlainConfig> implements IAnalyzeAction {
        @Override
        protected Class<PlainConfig> getConfigClass() {
            return PlainConfig.class;
        }

        @Override
        public void doAnalyze(WorkItem item) {
        }
    }

    static final class BareAction implements IAnalyzeAction {
        @Override
        public void doAnalyze(WorkItem item) {
        }

        @Override
        public void setStatusWatcher(Consumer<WorkStatus> watcher) {
        }

        @Override
        public void setPlugin(IPlugin plugin) {
        }
    }

    @Test
    public void anActionWithARecordConfigurationDescribesItsFields() {
        List<ConfigField> fields = new RecordConfigAction().configSchema().orElseThrow().fields();

        assertEquals(List.of("target", "retries"), fields.stream().map(ConfigField::name).toList());
        assertTrue(fields.getFirst().required());
    }

    @Test
    public void theSchemaNeedsNoLoadedConfiguration() {
        RecordConfigAction action = new RecordConfigAction();

        assertTrue(action.configSchema().isPresent(), "the catalog asks a fresh instance");
        assertNull(action.getConfig());
    }

    @Test
    public void otherActionsDescribeNothing() {
        assertTrue(new PlainConfigAction().configSchema().isEmpty());
        assertTrue(new BareAction().configSchema().isEmpty());
    }
}
