package com.copybot.plugin.remote.core;

/** Remote paths: names separated by '/', absolute ("/in/a.jpg") or relative ("in/a.jpg", an S3 key). */
public final class RemotePaths {

    private RemotePaths() {
    }

    /** The folder holding the path: "" for a name alone, "/" for a name at the root. */
    public static String parent(String path) {
        int slash = path.lastIndexOf('/');
        return slash < 0 ? "" : slash == 0 ? "/" : path.substring(0, slash);
    }

    /** The last name of the path. */
    public static String name(String path) {
        return path.substring(path.lastIndexOf('/') + 1);
    }

    /** The path of {@code name} in {@code folder} ("" : the name alone). */
    public static String join(String folder, String name) {
        return folder.isEmpty() ? name : folder.endsWith("/") ? folder + name : folder + "/" + name;
    }

    /**
     * A path as written by the user or made by a pattern: '\' read as '/', repeated '/' merged, a trailing '/'
     * dropped (but the root's), "." names dropped.
     *
     * @throws IllegalArgumentException the path holds a ".." name
     */
    public static String normalize(String path) {
        String slashed = path.replace('\\', '/');
        boolean absolute = slashed.startsWith("/");
        StringBuilder normalized = new StringBuilder(absolute ? "/" : "");
        for (String name : slashed.split("/")) {
            if (name.isEmpty() || name.equals(".")) {
                continue;
            }
            if (name.equals("..")) {
                throw new IllegalArgumentException(path);
            }
            if (!normalized.isEmpty() && normalized.charAt(normalized.length() - 1) != '/') {
                normalized.append('/');
            }
            normalized.append(name);
        }
        return normalized.toString();
    }
}
