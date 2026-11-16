package com.copybot.plugin.embedded.actions;

import com.copybot.plugin.api.config.DefaultValue;
import com.copybot.plugin.api.config.DirectoryPath;

/**
 * The "actionConfig" of file.buffer. The annotations describe the fields to the pipeline editor (spec desktop-ui §4).
 *
 * @param folder     where the copies go, on a fast local disk; absent or blank: "copybot-buffer" in the temporary
 *                   folder of the system
 * @param keepFreeGb the free space (GB) the copies leave on the buffer's disk; an item that would go below it is
 *                   not buffered (default 2)
 */
public record FileBufferConfig(
        @DirectoryPath
        String folder,

        @DefaultValue("2")
        Integer keepFreeGb
) {

    public long keepFreeBytes() {
        return (keepFreeGb == null ? 2L : keepFreeGb) * 1_000_000_000L;
    }
}
