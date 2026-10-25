package com.copybot.config;

import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.annotations.JsonAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record CopybotConfig(
        Path pluginPath,

        /**
         * Development plugin directories (a target/ folder holding classes/ and lib/): a list, or a single one
         * written as a string.
         */
        @JsonAdapter(PathsAdapter.class)
        List<Path> devPluginPaths,

        /**
         * Resource capacities. Exact name ("disk:sda", or a folder of the disk: "disk:C:\\") or prefix
         * pattern ("disk:*").
         */
        Map<String, Integer> resources,

        /**
         * Groups of resource names that alias to a single resource
         * (e.g. volumes spread over several disks, which are not joined to a physical disk).
         */
        List<List<String>> resourceGroups
) {

    /** Reads a list of paths, or a single path written as a string; writes a list. */
    public static final class PathsAdapter extends TypeAdapter<List<Path>> {

        @Override
        public List<Path> read(JsonReader reader) throws IOException {
            if (reader.peek() == JsonToken.STRING) {
                return List.of(Path.of(reader.nextString()));
            }
            if (reader.peek() != JsonToken.BEGIN_ARRAY) {
                throw new JsonSyntaxException("Expected a path or a list of paths at path " + reader.getPath());
            }
            List<Path> paths = new ArrayList<>();
            reader.beginArray();
            while (reader.hasNext()) {
                if (reader.peek() != JsonToken.STRING) {
                    throw new JsonSyntaxException("Expected a path at path " + reader.getPath());
                }
                paths.add(Path.of(reader.nextString()));
            }
            reader.endArray();
            return List.copyOf(paths);
        }

        @Override
        public void write(JsonWriter writer, List<Path> paths) throws IOException {
            writer.beginArray();
            for (Path path : paths) {
                writer.value(path.toString());
            }
            writer.endArray();
        }
    }
}
