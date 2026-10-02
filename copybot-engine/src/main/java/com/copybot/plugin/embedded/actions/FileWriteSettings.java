package com.copybot.plugin.embedded.actions;

import com.copybot.exception.CopybotException;
import com.copybot.plugin.api.pattern.OutPattern;
import com.copybot.resources.ResourcesEngine;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

/** The validated file.write configuration, defaults applied (spec safe-write §1 to §5). */
record FileWriteSettings(
        OutPattern outPattern,
        MissingKey onMissingKey,
        Compare compare,
        Policy ifIdentical,
        Policy ifDifferent,
        WriteMode writeMode,
        Verify verify,
        boolean deleteSource) {

    /** A configuration value, as written in the pipeline. */
    interface Named {
        String jsonName();
    }

    /** How an existing target is recognised as identical to the item; the size is always compared first. */
    enum Compare implements Named {
        SIZE("size"), SIZE_AND_DATE("sizeAndDate"), PARTIAL_HASH("partialHash"), FULL_HASH("fullHash");

        private final String jsonName;

        Compare(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    /** What to do when an expression of outPattern has no value for an item (spec pattern-helper §2). */
    enum MissingKey implements Named {
        ERROR("error"), SKIP("skip"), LITERAL("literal");

        private final String jsonName;

        MissingKey(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    /** What to do with an item whose target already exists. */
    enum Policy implements Named {
        SKIP("skip"), RENAME("rename"), OVERWRITE("overwrite"), ERROR("error");

        private final String jsonName;

        Policy(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    enum WriteMode implements Named {
        /** Written to a hidden temporary file of the target directory, then moved to the target. */
        TEMP_AND_RENAME("tempAndRename"),
        /** Written under the final name; with "overwrite" the original is lost as soon as the write starts. */
        DIRECT("direct");

        private final String jsonName;

        WriteMode(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    enum Verify implements Named {
        /** Bytes written = expected source size. */
        NONE("none"),
        /** Size of the written file, read back from the destination. */
        SIZE("size"),
        /** Whole written file read back and compared to the SHA-256 computed during the copy. */
        READ_BACK("readBack");

        private final String jsonName;

        Verify(String jsonName) {
            this.jsonName = jsonName;
        }

        @Override
        public String jsonName() {
            return jsonName;
        }
    }

    /**
     * @throws CopybotException write.config.no-out-pattern, pattern.syntax, write.config.unknown-value
     */
    static FileWriteSettings of(FileWriteConfig config) {
        if (config == null || config.outPattern() == null || config.outPattern().isBlank()) {
            throw CopybotException.ofResource("write.config.no-out-pattern");
        }
        OutPattern pattern = OutPattern.parse(config.outPattern());
        MissingKey missingKey = parse("onMissingKey", config.onMissingKey(), MissingKey.ERROR, MissingKey.class);
        FileWriteConfig.OnConflict onConflict = config.onConflict();
        Compare compare = Compare.PARTIAL_HASH;
        Policy ifIdentical = Policy.SKIP;
        Policy ifDifferent = Policy.RENAME;
        if (onConflict != null) {
            compare = parse("onConflict.compare", onConflict.compare(), Compare.PARTIAL_HASH, Compare.class);
            ifIdentical = parse("onConflict.ifIdentical", onConflict.ifIdentical(), Policy.SKIP, Policy.class);
            ifDifferent = parse("onConflict.ifDifferent", onConflict.ifDifferent(), Policy.RENAME, Policy.class);
        }
        return new FileWriteSettings(pattern, missingKey, compare, ifIdentical, ifDifferent,
                parse("writeMode", config.writeMode(), WriteMode.TEMP_AND_RENAME, WriteMode.class),
                parse("verify", config.verify(), Verify.SIZE, Verify.class),
                Boolean.TRUE.equals(config.deleteSource()));
    }

    /**
     * Allowed but warned: deleting the sources after a verification lighter than readBack (spec safe-write
     * §5), and the direct write mode, where a crash or a cancel can leave a partial file under the final name.
     */
    List<String> warnings() {
        List<String> warnings = new ArrayList<>();
        if (deleteSource && verify != Verify.READ_BACK) {
            warnings.add(ResourcesEngine.getString("write.warn.delete-without-read-back", verify.jsonName()));
        }
        if (writeMode == WriteMode.DIRECT) {
            warnings.add(ResourcesEngine.getString("write.warn.direct-mode"));
        }
        return List.copyOf(warnings);
    }

    private static <E extends Enum<E> & Named> E parse(String field, String value, E defaultValue, Class<E> type) {
        if (value == null) {
            return defaultValue;
        }
        for (E constant : type.getEnumConstants()) {
            if (constant.jsonName().equals(value)) {
                return constant;
            }
        }
        String expected = Arrays.stream(type.getEnumConstants()).map(Named::jsonName).collect(Collectors.joining(", "));
        throw CopybotException.ofResource("write.config.unknown-value", field, value, expected);
    }
}
