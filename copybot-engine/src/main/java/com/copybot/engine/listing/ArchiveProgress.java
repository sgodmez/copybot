package com.copybot.engine.listing;

/** Reports the unfolding of the archives to the preparation ("archives unfolded / to download"). */
public interface ArchiveProgress {

    /** Once per archive successfully unfolded. */
    void unfolded();

    /** Once per archive copied locally because it is not a local file, with its size. */
    void toDownload(long bytes);
}
