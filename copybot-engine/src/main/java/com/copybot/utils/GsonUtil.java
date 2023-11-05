package com.copybot.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.nio.file.Path;

public class GsonUtil {

    static {
        gson = new GsonBuilder()
                .registerTypeAdapter(Path.class, new PathAdapter())
                .create();
    }

    private static Gson gson;

    public static Gson getGson() {
        return gson;
    }

    private static class PathAdapter extends TypeAdapter<Path> {
        @Override
        public Path read(JsonReader reader) throws IOException {
            return Path.of(reader.nextString());
        }

        @Override
        public void write(JsonWriter writer, Path path) throws IOException {
            writer.jsonValue(path.toString());
        }
    }
}
