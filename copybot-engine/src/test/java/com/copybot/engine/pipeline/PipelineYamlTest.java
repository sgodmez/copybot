package com.copybot.engine.pipeline;

import com.google.gson.JsonElement;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** A pipeline written in YAML: the same tree as its JSON form, and written back as YAML. */
public class PipelineYamlTest {

    @Test
    public void aYamlPipelineReadsAsItsJsonTree() {
        JsonElement tree = PipelineYaml.read("""
                execution: parallel
                inSteps:
                  - action: file.read
                    actionConfig:
                      paths: ['F:\\DCIM']
                      recursive: true
                      maxDepth: 3
                      ratio: 0.5
                      label: null
                outStep:
                  action: file.write
                """);

        assertEquals(JsonParser.parseString("""
                {"execution":"parallel",
                 "inSteps":[{"action":"file.read","actionConfig":{"paths":["F:\\\\DCIM"],"recursive":true,
                   "maxDepth":3,"ratio":0.5,"label":null}}],
                 "outStep":{"action":"file.write"}}"""), tree);
        // JsonPrimitive.equals sees 3 and 3.0 as equal: an integer must stay an integer for the int config fields
        assertTrue(tree.toString().contains("\"maxDepth\":3,"), tree.toString());
    }

    /** YAML 1.2 (Core schema): "no", "on", "yes" are strings, not the booleans of YAML 1.1. */
    @Test
    public void yamlOneOneBooleansAreStrings() {
        JsonElement tree = PipelineYaml.read("a: no\nb: on\nc: yes\nd: true\n");

        assertEquals(JsonParser.parseString("{\"a\":\"no\",\"b\":\"on\",\"c\":\"yes\",\"d\":true}"), tree);
    }

    /** The engine would silently keep one of the values: refused, the author meant something. */
    @Test
    public void aDuplicateKeyIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> PipelineYaml.read("execution: parallel\nexecution: sequential\n"));

        assertTrue(e.getMessage().contains("execution"), e.getMessage());
    }

    @Test
    public void invalidYamlIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PipelineYaml.read("inSteps: [\n  - a: b\n"));
    }

    /** An empty file reads as null, like an empty JSON file. */
    @Test
    public void anEmptyFileReadsAsJsonNull() {
        assertTrue(PipelineYaml.read("").isJsonNull());
        assertTrue(PipelineYaml.read("# only a comment\n").isJsonNull());
    }

    @Test
    public void aNonFiniteNumberIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> PipelineYaml.read("ratio: .inf\n"));
        assertThrows(IllegalArgumentException.class, () -> PipelineYaml.read("ratio: .nan\n"));
    }

    /** Strings that YAML would otherwise read as another type are quoted, so the tree survives a save. */
    @Test
    public void writtenYamlReadsBackAsTheSameTree() {
        JsonElement tree = JsonParser.parseString("""
                {"strings":["true","no","123","1.10","null","","~","a: b","#x","- y","D:\\\\progs\\\\a b.exe",
                   "line1\\nline2","{Y}/{Y-m-D}","été"],
                 "numbers":[0,-7,12345678901234,0.25,1e-3],
                 "flags":[true,false],
                 "nothing":null,
                 "empty":{},"none":[],
                 "nested":{"inSteps":[{"action":"file.read","actionConfig":{"recursive":true}}]}}""");

        String yaml = PipelineYaml.write(tree);

        assertEquals(tree, PipelineYaml.read(yaml), yaml);
    }

    @Test
    public void writtenYamlIsBlockStyle() {
        String yaml = PipelineYaml.write(JsonParser.parseString(
                "{\"execution\":\"parallel\",\"inSteps\":[{\"action\":\"file.read\"}]}"));

        assertEquals("execution: parallel\ninSteps:\n- action: file.read\n", yaml);
    }

    @Test
    public void commentsAreDetected() {
        assertTrue(PipelineYaml.hasComments("# my SD card\nexecution: parallel\n"));
        assertTrue(PipelineYaml.hasComments("execution: parallel # fastest\n"));
        assertFalse(PipelineYaml.hasComments("execution: parallel\nname: 'a # not a comment'\n"));
    }

    @Test
    public void yamlFilesAreRecognisedByTheirExtension() {
        assertTrue(PipelineYaml.isYaml(Path.of("dir", "sd.yaml")));
        assertTrue(PipelineYaml.isYaml(Path.of("sd.YML")));
        assertFalse(PipelineYaml.isYaml(Path.of("sd.json")));
        assertFalse(PipelineYaml.isYaml(Path.of("yaml")));
    }
}
