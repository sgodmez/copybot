# Media preview from the plan table (design)

## Goal

See a photo, a video or a sound of the plan without leaving Copybot: double click on a row (or "Preview" in its
context menu). Best effort: what JavaFX reads by itself, nothing exotic; RAW and HEIC are not decoded.

## Behaviour

- **Opening**: double click (primary button) on a row with a file of its own on this computer. Rows without one
  (an entry of an archive, a remote item) do nothing; "Preview" is disabled for them.
- **Window**: a single one, reused (a new double click shows its file there, brought to the front), not modal,
  owned by the main window, 70 % x 75 % of the screen, dark background. Bottom bar:
  `◀ n / N ▶`, the full path, "Open with the default application" (HostServices).
- **Navigation**: the rows of the table in its current order and filter, keeping only the ones shown here (so a
  photo's sidecar `.xmp` is skipped) plus the clicked row in any case. Left / right arrows, Escape closes, Space
  plays / pauses.
- **By extension** (`MediaPreview.kindOf`):
  - image: jpg, jpeg, jpe, jfif, png, gif, bmp. Decoded at most at the size of the screen (memory), turned upright
    from the EXIF orientation of a JPEG (JavaFX ignores it: phone photos would lie on their side);
  - video: mp4, m4v, mov (H.264/AAC; mov often plays); audio: mp3, m4a, wav, aif, aiff. Play / pause, a position
    slider, the time;
  - anything else: a message and the button opening it with the application of the system.
- **Failures**: a missing file says so; a file JavaFX cannot decode (codec, CMYK JPEG, damaged) says why and offers
  the application of the system.

## Files kept open

A JavaFX `Media` opens its file with a stream only the garbage collector closes; disposing the player is not
enough, and a `MediaView` still attached to a disposed player keeps it too. While open, Windows refuses to move or
delete the file, which a copy in "move" mode needs. So: the view is detached, the player disposed, then a
collection is requested a moment later, whenever a media is dropped (other file, window closed, or read in the
background after the user moved on). Collections are coalesced. Tested: the file can be moved again within
seconds.

While the engine prepares or copies, no video nor sound is opened (a message says why, without the button: an
application would keep the file open too), and when it starts, the one playing stops and its file is released.
Residual risk: the release takes a fraction of a second; a copy in "move" mode reaching that very file at once
fails to delete its source (the copy itself is done, the item says so, nothing is lost).

## Out of scope

RAW / HEIC / WebP / TIFF decoding, thumbnails in the table, zoom and pan, preview of archive entries.
