package com.copybot.utils;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonSyntaxException;
import com.google.gson.TypeAdapter;
import com.google.gson.TypeAdapterFactory;
import com.google.gson.reflect.TypeToken;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.util.Map;
import java.util.function.Function;

public class GsonUtil {

    /**
     * Reads the integer types (int, long, short, byte, boxed or not) exactly: Gson reading a JSON tree
     * wraps an out-of-range number around (3000000000 becomes a negative int); it is refused here, like a
     * fraction. A zero fraction ("8.0") and a quoted number ("8") are still accepted, as Gson does.
     */
    public static final TypeAdapterFactory EXACT_INTEGERS = new ExactIntegersFactory();

    static {
        gson = new GsonBuilder()
                .setPrettyPrinting()
                .registerTypeAdapter(Path.class, new PathAdapter())
                .registerTypeAdapterFactory(EXACT_INTEGERS)
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

    private static final class ExactIntegersFactory implements TypeAdapterFactory {

        private record Exact(String name, Function<BigDecimal, Number> value) {
        }

        private static final Map<Class<?>, Exact> TYPES = Map.of(
                int.class, new Exact("an int", BigDecimal::intValueExact),
                Integer.class, new Exact("an int", BigDecimal::intValueExact),
                long.class, new Exact("a long", BigDecimal::longValueExact),
                Long.class, new Exact("a long", BigDecimal::longValueExact),
                short.class, new Exact("a short", BigDecimal::shortValueExact),
                Short.class, new Exact("a short", BigDecimal::shortValueExact),
                byte.class, new Exact("a byte", BigDecimal::byteValueExact),
                Byte.class, new Exact("a byte", BigDecimal::byteValueExact));

        @Override
        public <T> TypeAdapter<T> create(Gson gson, TypeToken<T> type) {
            Exact exact = TYPES.get(type.getRawType());
            if (exact == null) {
                return null;
            }
            @SuppressWarnings("unchecked")
            TypeAdapter<T> adapter = (TypeAdapter<T>) new TypeAdapter<Number>() {
                @Override
                public Number read(JsonReader in) throws IOException {
                    if (in.peek() == JsonToken.NULL) {
                        in.nextNull();
                        return null;
                    }
                    String text = in.nextString(); // a number, or a quoted one
                    try {
                        return exact.value().apply(new BigDecimal(text.strip()));
                    } catch (NumberFormatException | ArithmeticException e) {
                        throw new JsonSyntaxException("Expected " + exact.name() + " but was " + text
                                + " at path " + in.getPreviousPath(), e);
                    }
                }

                @Override
                public void write(JsonWriter out, Number value) throws IOException {
                    out.value(value);
                }
            };
            return adapter;
        }
    }
}
