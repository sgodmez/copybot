package com.copybot.ui;

import com.copybot.resources.ResourcesEngine;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The texts the form shows for the values of a choice (the JSON keeps the values). */
public class ConfigFormLabelsTest {

    @BeforeAll
    public static void registerUiBundle() {
        ResourcesEngine.registerBundle("com.copybot.ui.i18n.uiBundle");
    }

    @Test
    public void aBooleanShowsYesOrNoAnyOtherValueAsWritten() {
        assertEquals(ResourcesEngine.getString("editor.value.true"), ConfigForm.booleanLabel("true"));
        assertEquals(ResourcesEngine.getString("editor.value.false"), ConfigForm.booleanLabel("false"));
        assertNotEquals("true", ConfigForm.booleanLabel("true"), "translated");
        assertEquals("maybe", ConfigForm.booleanLabel("maybe"), "a value kept from the file");
    }
}
