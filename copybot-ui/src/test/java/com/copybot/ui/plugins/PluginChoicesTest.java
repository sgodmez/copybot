package com.copybot.ui.plugins;

import com.copybot.ui.plugins.PluginChoices.Action;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** The actions chosen in the Plugins window before they are applied. */
public class PluginChoicesTest {

    private static PluginRow row(String code, PluginState state, Path... folders) {
        PluginCatalog.Entry entry = new PluginCatalog.Entry("com.copybot.plugin." + code, code, "1.0.0", "1.0", "v1",
                code + ".zip", Map.of(), Map.of(), null);
        return new PluginRow(entry, state == PluginState.NOT_INSTALLED || state == PluginState.INCOMPATIBLE ? null
                : "0.9.0", List.of(folders), state);
    }

    private static final PluginRow NEW = row("new", PluginState.NOT_INSTALLED);
    private static final PluginRow OLD = row("old", PluginState.UPDATE_AVAILABLE, Path.of("plugins/old"));
    private static final PluginRow OK = row("ok", PluginState.UP_TO_DATE, Path.of("plugins/ok"));
    private static final PluginRow DEV = row("dev", PluginState.UP_TO_DATE); // installed outside the plugins folder
    private static final PluginRow BAD = row("bad", PluginState.INSTALLED_INCOMPATIBLE, Path.of("plugins/bad"));
    private static final PluginRow OTHER = row("other", PluginState.INCOMPATIBLE);
    private static final List<PluginRow> ROWS = List.of(NEW, OLD, OK, DEV, BAD, OTHER);

    @Test
    public void eachStateOffersItsActions() {
        assertEquals(List.of(Action.INSTALL), PluginChoices.offered(NEW));
        assertEquals(List.of(Action.UPDATE, Action.REMOVE), PluginChoices.offered(OLD));
        assertEquals(List.of(Action.REMOVE), PluginChoices.offered(OK));
        assertEquals(List.of(), PluginChoices.offered(DEV));
        assertEquals(List.of(Action.REMOVE), PluginChoices.offered(BAD));
        assertEquals(List.of(), PluginChoices.offered(OTHER));
    }

    @Test
    public void choicesAccumulateAndCanBeCancelled() {
        PluginChoices choices = new PluginChoices();
        assertTrue(choices.isEmpty());

        choices.choose(NEW, Action.INSTALL);
        choices.choose(OK, Action.REMOVE);
        choices.choose(OLD, Action.UPDATE);
        assertEquals(Optional.of(Action.REMOVE), choices.chosen(OK));
        assertEquals(List.of(NEW, OLD), choices.toInstallOrUpdate(ROWS));
        assertEquals(List.of(OK), choices.toRemove(ROWS));

        choices.cancel(OK);
        assertEquals(List.of(), choices.toRemove(ROWS));
        assertThrows(IllegalArgumentException.class, () -> choices.choose(OTHER, Action.INSTALL));
    }

    @Test
    public void updateAllChoosesEveryUpdate() {
        PluginChoices choices = new PluginChoices();
        choices.choose(OLD, Action.REMOVE);

        assertEquals(1, choices.updateAll(ROWS));
        assertEquals(List.of(OLD), choices.toInstallOrUpdate(ROWS));
        assertEquals(List.of(), choices.toRemove(ROWS));
    }

    @Test
    public void aPreselectionInstallsOrUpdatesOnlyWhatItCan() {
        PluginChoices choices = new PluginChoices();

        choices.preselect(ROWS, Set.of(NEW.entry().module(), OLD.entry().module(), OK.entry().module(),
                BAD.entry().module(), OTHER.entry().module())); // BAD: installed, refused, no update to offer

        assertEquals(List.of(NEW, OLD), choices.toInstallOrUpdate(ROWS));
    }

    /** An editor reopens the Plugins window with the choices of the old one, then asks for its missing plugin. */
    @Test
    public void aPreselectionIsAddedToTheChoicesAlreadyMade() {
        PluginChoices choices = new PluginChoices();
        choices.choose(OK, Action.REMOVE);
        choices.choose(OLD, Action.REMOVE);

        choices.retain(ROWS); // the rows read again by the new window
        choices.preselect(ROWS, Set.of(NEW.entry().module(), OLD.entry().module())); // OLD keeps its own choice

        assertEquals(List.of(NEW), choices.toInstallOrUpdate(ROWS));
        assertEquals(List.of(OLD, OK), choices.toRemove(ROWS));
    }

    @Test
    public void aChoiceNoLongerOfferedIsDropped() {
        PluginChoices choices = new PluginChoices();
        choices.choose(NEW, Action.INSTALL);
        choices.choose(OLD, Action.UPDATE);

        choices.retain(List.of(row("new", PluginState.UP_TO_DATE, Path.of("plugins/new")), OLD));

        assertEquals(Optional.empty(), choices.chosen(NEW));
        assertEquals(Optional.of(Action.UPDATE), choices.chosen(OLD));
    }
}
