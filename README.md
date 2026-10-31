<h1 align="center">
  <img src="copybot-ui/src/main/resources/com/copybot/ui/Copybot.png" width="128" alt=""><br>
  Copybot
</h1>

<p align="center"><em>A file-copying robot that sorts, checks and resumes — built so that a photo import never loses a file and never imports one twice.</em></p>

<p align="center">
  <img src="https://img.shields.io/badge/Java-25-ed8b00?logo=openjdk&logoColor=white" alt="Java 25">
  <img src="https://img.shields.io/badge/UI-JavaFX%2025-4b7bec" alt="JavaFX 25">
  <img src="https://img.shields.io/badge/plugins-JPMS%20modules-6c5ce7" alt="Plugins as JPMS modules">
  <img src="https://img.shields.io/badge/i18n-en%20%7C%20fr%20%7C%20it-blue" alt="English, French, Italian">
  <a href="LICENSE"><img src="https://img.shields.io/badge/license-GPL--3.0-green" alt="GPL-3.0"></a>
</p>

You put a memory card in the reader and you want its photos on the NAS,
sorted by capture date, and only the ones you have not imported yet.
Copybot does this. You describe the job once as a **pipeline**: where the files
come from, what to learn about each one, how to process it, and where it goes.
Copybot then lists the files, shows you the **plan**, and copies only after you
confirm. It writes every file safely, checks it, and remembers how far it got.

There is a desktop application to edit and run pipelines and a command line
to script them. Both use the same engine. Everything the engine does not
build in, such as reading EXIF dates, comes from **plugins**: Java modules
dropped into a folder, each one isolated with its own versions of its
libraries.

## Download

<p align="center">
  <a href="https://github.com/sgodmez/copybot/releases/latest"><img src="https://img.shields.io/github/v/release/sgodmez/copybot?label=download&logo=github" alt="Download the latest release"></a>
</p>

**[Download the latest release](https://github.com/sgodmez/copybot/releases/latest)**:
`copybot-<version>-setup.exe` (Windows installer), `copybot-<version>-windows-x64.zip`
(Windows, portable) or `copybot-<version>-linux-x64.tar.gz` (Linux). The
application updates itself on Windows and installs its plugins from
*Edit > Plugins…*. More in [Quick start](#quick-start).

### Plugins

| Plugin | What it brings | Details |
|---|---|---|
| `metadata-extractor` | Capture date of photos and videos (EXIF, QuickTime, MP4), so files are sorted by when they were taken; rewrites the EXIF date of JPEGs whose time zone was corrected | [How it works](#how-it-works) |
| `gps-zone` | Checks the camera's time zone against the photo's GPS position and offers a correction in the plan (optional, 36 MB of time zone data) | [How it works](#how-it-works) |
| `exec` | Runs any program on a file (`{in}` → `{out}`); what it writes replaces the content | [Programs](#programs-the-exec-plugin) |
| `ffmpeg` | Video transcoding to H.265 / H.264 with GPU encoders, HEIC → JPEG, thumbnails; ffmpeg downloaded on demand. Needs `exec` (installed with it) | [ffmpeg](#ffmpeg-the-ffmpeg-plugin) |
| `remote` | SFTP, FTP / FTPS and S3 (AWS, MinIO…) as sources and targets, with trust on first use of servers' keys and certificates | [Remote servers](#remote-servers-sftp-ftp-and-s3-the-remote-plugin) |
| `notify-email` | Sends a report e-mail after the run (SMTP, STARTTLS / SSL), password kept in the local secrets vault | [notify-email](#notify-email-emailsend) |
| `notify-webhook` | Posts the run report to a webhook: JSON, ntfy, Discord, Slack or Telegram | [notify-webhook](#notify-webhook-webhooksend) |
| `archive` | Zips the files the run copied, with a `SHA256SUMS` manifest | [archive](#archive-archivezip) |

Built into the application, no plugin needed: reading and writing local
folders, [companion files and archive unfolding](#companion-files-and-archives),
[secondary outputs](#secondary-outputs) and [running a program after the copy](#postprocess).

## Highlights

- **Plan first, then copy.** By default a run lists and analyses the files
  without writing anything. It then shows each file with its target path and
  what will happen to it: copy, skip, conflict or error. You check, filter and
  adjust the plan, then start the copy. You can also run **auto** (prepare and
  copy in one go) or **streaming** (each file is copied as soon as it is
  listed, which suits a card with 100,000 files).
- **Safe writes.** A file is written under a hidden temporary name, checked,
  then renamed. A crash never leaves a half-written file under the real name,
  and temporary files left by a crashed run are cleaned up on the next run.
  The check is the size by default, or a full read-back. Copybot computes a
  SHA-256 while copying and keeps the source's modification date. **Move**
  mode deletes the source only after it has checked the copy and forced it to
  disk.
- **Conflicts handled explicitly.** When the target already exists, Copybot
  compares the two files by size, by size and date, by a partial hash (the
  default) or by a full hash. For an identical target and for a different
  one, you choose what happens: skip, rename (`name (1).ext`), overwrite, or
  report an error. Copybot never overwrites unless the pipeline says so, and
  never treats a file as a conflict with itself. The plan already tells which
  files to copy have a target that exists, with a counter and a filter:
  `conflictCheck` is `quick` by default (existence and size, one access per
  file), `full` (the copy's own comparison, so the plan says exactly what will
  be skipped, renamed or overwritten) or `none`.
- **Resume where the last import stopped.** A cursor (capture date and file
  name) is saved next to the pipeline after each run. It marks the end of the
  longest run of successful files. Copybot can also look at what is already
  at the destination: from the end for a large tree (after the last file
  found there, whatever was deleted there before it), or file by file.
  The detected resume point is only a proposal: you can resume from a
  chosen file or day, or copy everything (`--from-file`, `--from-date`,
  `--all`), even before preparing: a chosen point acts like a cursor there.
  Files skipped this way are not even analysed, unless you bring them back.
- **Destination patterns.** `{captureDate.Y}/{captureDate.m}/{name}` with
  fallbacks (`{captureDate.Y|lastModified.Y|'unknown'}`) and a policy for a
  missing key. The editor's **pattern helper** reads a real sample from your
  source: it shows the available keys with example values and the path each
  file would get.
- **Filters and ignores.** You choose recursion and the `include` / `exclude`
  globs. Hidden and system files are skipped by default, including
  `System Volume Information` and `$RECYCLE.BIN`. In the plan you can ignore
  a file for this copy only, or always ignore it: that adds an exclude to the
  pipeline.
- **Companion files and archives.** Rules attach an `.xmp`, a `.AAE`, a
  `_files` folder or a JSON sidecar to their main file, which then travels,
  is sorted and is written with them, as one plan row. A ZIP, even split in
  parts, can be unfolded at the listing and its files treated like any other.
  See *Companion files and archives* below.
- **Parallel, but polite to your disks.** Each file is processed on a virtual
  thread. Named resources (`cpu`, `gpu`, `disk:<disk>` and your own) cap how
  many actions run at once. Resources are acquired all at once or not at all,
  so actions do not deadlock and none waits forever. Copybot finds the
  physical disk behind each folder, on Windows and Linux, so two drive letters
  or partitions of one disk share one budget. That budget follows the kind of
  disk: an NVMe or SSD disk takes more actions at once than a hard disk.
  While a plan is prepared or run, the plan view shows the CPU load (the
  whole machine, and the part of Copybot) and each resource the pipeline
  uses: how many actions hold it, and how many wait for it. The gauges sit on
  the line of the progress bar, or in a panel on the right (a preference).
- **Pause, resume, stop.** You can pause, resume or stop a run at any time.
  A stopped run does not move the cursor. A preparation stopped after its
  listing lets you pick where to resume from its rows, then prepare again. In the
  CLI, Ctrl+C cancels and leaves 5 s for writes in progress to finish; when
  the pipeline has end-of-run actions for `cancelled`, it then waits for them,
  within a bound computed from their time limits (shown on stderr).
- **Remote sources and targets.** The `remote` plugin reads from and writes
  to SFTP, FTP or FTPS servers and S3 buckets (AWS, MinIO). Writes go through a
  temporary name and a size check, and keep the dates; an existing target is
  compared by size, or size and date, never by content, and companion files
  are not written to a server. A server's key or self-signed
  certificate is trusted on first use, after you confirm it. See
  [Remote servers](#remote-servers-sftp-ftp-and-s3-the-remote-plugin).
- **Actions after the copy.** A pipeline can end with an ordered list of
  actions (`runEnd`): launch a program (to fix the rights of the new folders on
  a NAS, for example), send an e-mail or a
  webhook message (ntfy, Discord, Slack, Telegram), write a ZIP of the copied
  files with their checksums. Passwords stay in a per-user vault. See
  [After the copy](#after-the-copy-run-end-actions).
- **Desktop application.** The home screen lists recent pipelines and how
  their last run went. The plan view has filters and a context menu: resume
  from here, ignore, see the planned processing, analyse. The pipeline editor
  builds its forms from each action's configuration schema, and keeps intact
  any JSON it does not know. There is a plugins diagnostics window. The
  interface is in English, French and Italian.
- **Command line.** One command runs a pipeline headless, with a dry-run mode
  and exit codes meant for scripts.

## Screenshots

The plan of a memory card, before anything is written. The September photos
are already on the NAS, so the plan resumes after the last one it found
there. Nine photos were taken in Tokyo with the camera still on Paris time:
their GPS tells, and the plan offers to fix their time zone.

![The plan view: the resume point found on the destination, the photos of a badly set camera, and the files to copy with their target](docs/captures/plan.png)

The same plan once copied: what was copied, what was skipped, and the
summary of the run.

![The plan view after the copy: 32 copied, 12 skipped](docs/captures/plan-copied.png)

<table>
  <tr>
    <td width="50%"><img src="docs/captures/home.png" alt="The home screen: the recent pipelines and how their last run went"></td>
    <td width="50%"><img src="docs/captures/plugins.png" alt="The plugins window: each plugin, its modules, and its actions"></td>
  </tr>
  <tr>
    <td>The home screen lists the recent pipelines and how their last run went.</td>
    <td>The plugins window shows each plugin, its modules and its actions.</td>
  </tr>
</table>

The pipeline editor, on the out step. The pattern helper reads a sample of
the source and shows the path each file gets.

![The pipeline editor: the output pattern, the paths the sample files get, and the conflict policy](docs/captures/editor.png)

*The screenshots use made-up photos and folders.*

## How it works

```mermaid
flowchart LR
    src["Source<br/>(SD card, folder, share)"] --> in
    subgraph pipeline["Pipeline (JSON)"]
        in["IN<br/>list files"] --> analyse["ANALYZE<br/>read metadata"]
        analyse --> process["PROCESS<br/>transform, fork, filter"]
        process --> out["OUT<br/>write to target"]
    end
    state[("x.state.json<br/>resume cursor")] <-.-> pipeline
    out --> dst["Destination<br/>(NAS, disk)"]
```

A pipeline is a JSON file with four kinds of steps. **In** steps list files.
**Analyze** steps add metadata such as the capture date. **Process** steps
transform an item, and can turn it into several items or into none. One
**out** step writes the result. Each step names a plugin and one of its
actions, and gives that action its own configuration:

```json
{
  "execution": "plan",
  "inSteps": [
    { "action": "file.read",
      "actionConfig": { "path": "E:\\DCIM", "include": ["**/*.nef", "**/*.jpg", "**/*.mp4"] } }
  ],
  "analyseSteps": [
    { "plugin": "com.copybot.plugin.metadataextractor", "action": "extract" }
  ],
  "outStep": {
    "action": "file.write",
    "actionConfig": {
      "outPattern": "\\\\nas\\photos\\{captureDate.Y|lastModified.Y}\\{captureDate.m|lastModified.m}\\{name}",
      "verify": "readBack",
      "onConflict": { "compare": "partialHash", "ifIdentical": "skip", "ifDifferent": "rename" }
    }
  },
  "resume": { "mode": "stateThenDestination" }
}
```

A step without `plugin` uses the actions built into the engine. Each step can
also set `maxConcurrency`, extra `resources`, or the plugin `version` to use
when several versions are installed.

| Action | Plugin | Kind | What it does |
|---|---|---|---|
| `file.read` | built in | in | Lists a folder, recursively or not, with include/exclude globs and the hidden-file policy |
| `file.write` | built in | out | Safe write to a pattern-built path: temporary file and rename, verification, conflict policy, optional move |
| `sftp.read`, `ftp.read`, `s3.read` | `com.copybot.plugin.remote` | in | Lists a folder of an SFTP or FTP / FTPS server, or a prefix of an S3 bucket. See *Remote servers* |
| `sftp.write`, `ftp.write`, `s3.write` | `com.copybot.plugin.remote` | out | Writes to an SFTP or FTP / FTPS server or an S3 bucket: temporary name and rename (S3: atomic put), size check, conflict policy, optional move |
| `extract` | `com.copybot.plugin.metadataextractor` | analyze | Capture date from EXIF, falling back to QuickTime then MP4 (through [metadata-extractor](https://github.com/drewnoakes/metadata-extractor)) |
| `write-date` | `com.copybot.plugin.metadataextractor` | process | Goes just before the write. Rewrites the EXIF date of the JPEGs whose time zone was corrected in the plan to the local date and offset, without recompressing the image (through [Commons Imaging](https://commons.apache.org/proper/commons-imaging/)); every other file passes untouched |
| `check` | `com.copybot.plugin.gpszone` (optional) | analyze | Goes after `extract`. Flags a photo whose camera was badly set: from its GPS position, the time zone of the place (offline boundaries of [Timezone Boundary Builder](https://github.com/evansiroky/timezone-boundary-builder) through [timeshape](https://github.com/RomanIakovlev/timeshape)) gives another offset than the recorded one, the GPS time confirming the capture date. Nothing near a time zone boundary, at sea, or for a video. The plan suggests the zone; you apply it |
| `exec.process` | `com.copybot.plugin.exec` | process | Runs any program on the file (`{in}`, `{out}`, `{name}`, `{ext}` in its arguments); what it writes replaces the content. See *Programs* |
| `ffmpeg.video` | `com.copybot.plugin.ffmpeg` | process | Compresses videos to MP4 in H.265 or H.264, on the processor or a GPU encoder; ffmpeg is found, or downloaded on Windows. See *ffmpeg* |
| `ffmpeg.image` | `com.copybot.plugin.ffmpeg` | process | HEIC (or chosen images) to JPEG, with quality and maximum size |
| `ffmpeg.thumbnail` | `com.copybot.plugin.ffmpeg` | process | A small JPEG of each image or video; other files are left out |

### Companion files and archives

Two optional blocks of the pipeline work right after the listing, on every
source. Without them nothing changes, and an older Copybot ignores them.

```json
"grouping": {
  "rules": [
    { "preset": "raw-jpeg-xmp" },
    { "type": "sameBase", "main": ["cr3", "nef", "jpg"], "companions": { "xmp": "copy", "thm": "copy" } },
    { "type": "doubleExtension", "companions": { "xmp": "copy" } },
    { "type": "folder", "main": ["html", "htm"], "folder": "{base}_files", "role": "copy" },
    { "type": "regex", "main": "(.+\\.(?:jpe?g|heic|mp4))",
      "companion": "\\1(?:\\.supplemental-metadata)?\\.json", "role": "read", "link": "name" }
  ]
},
"archives": { "unfold": true, "nested": false, "mergeFolder": false,
              "include": ["Takeout/**"], "exclude": ["**/*.html"] }
```

**Grouping.** A rule attaches companion files to a main file, which carries
them through the pipeline: analysis, processing and the write see the main
file, and the plan has one row per group, with a *Companions* column (count,
and names and roles in the tooltip). Names and extensions are compared without
case; a file whose target would be its main file's own (`IMG_1.JPG` next to
`IMG_1.jpg`) is not attached, and a group whose members would share a target
fails before anything is written. Rules apply in order, a file already attached is no longer a candidate,
and a companion without a main file is an ordinary file. A group has one
level.

| Rule | Attaches | Main file |
|---|---|---|
| `sameBase` | same folder, same name without extension (`IMG_1.CR3`, `IMG_1.xmp`) | the first present in `main` (priority) |
| `doubleExtension` | `IMG_1.CR3.xmp` to `IMG_1.CR3` | the file whose name is the companion's without its last extension |
| `folder` | everything under `<base>_files/`, recursively, to `<base>.html` | the file in `main` |
| `regex` | the `companion` expression, with references to the groups of the main one | the file the `main` expression matches; `link` is `name` (default) or `base` |

A role says what happens to a companion: `copy` (written next to the main
file, under its final name, also after a rename or a conversion), `read` (the
analysis can read it, it is never written) or `ignore` (left out: no copy, no
plan row). On `folder` and `regex` rules `role` defaults to `copy` when
absent; `sameBase` and `doubleExtension` companions take their roles from the
`companions` map. Presets, expanded by the core:

| Preset | Main file | Companions |
|---|---|---|
| `raw-jpeg-xmp` | RAW (CR3, CR2, NEF, ARW, RAF, ORF, RW2, DNG, PEF, SRW), then JPG, JPEG, HEIC | JPG, JPEG, HEIC and XMP copied; `IMG_1.CR3.xmp` copied too |
| `iphone` | HEIC, JPG, JPEG | AAE and MOV (Live Photo) copied |
| `action-cam` | MP4 | LRV and THM copied |
| `sony-xml` | MP4 | `<base>M01.XML` read |
| `web-page` | HTML, HTM | `<base>_files/` copied |

A plugin can bring presets of its own (see [Plugins](#plugins)). The pipeline
cites one by its code and the module name of the plugin, without version (the
loaded version of the plugin is used):

```json
{ "preset": "takeout", "plugin": "com.copybot.plugin.takeout" }
```

The editor's *Add a preset* menu lists the core presets, then those of the
loaded plugins (*name — plugin*), and its warnings take their rules into
account. When the plugin is not loaded, the rule shows *code (plugin missing)*
and the editor offers to install it, as for a step. A run refuses such a
pipeline before listing anything: the preset of a missing plugin, an unknown
code, and a preset the plugin declared with invalid rules (left out when the
plugin is loaded, with a warning in the log) are each reported by name. A
Google Takeout plugin is planned (photos and their JSON metadata); it is no
longer a preset of the core.

A group is **solidary** for conflicts. Each `copy` member is compared with its
target and the group takes the most severe result. The pipeline's rules apply
to the whole group: skip skips all of it, overwrite overwrites all, error
fails the group, and rename takes the first `(n)` that is free for every
member (`IMG_1 (1).CR3` and `IMG_1 (1).xmp`). The plan says which member
causes the conflict. A companion that is missing at the destination is always
written, even if the main file is skipped as identical, so a companion that
failed on a previous run is caught up. In move mode no source of the group is
deleted before the main file and all its `copy` companions are written and
verified, or the whole group is already at the destination and proven
identical with `fullHash`; a group in error deletes nothing, and `read`
companions are never deleted. The output action must write companions: `file.write` does, and a
pipeline whose output cannot is refused when validated.

The input filters apply before grouping: an `include` of `*.jpg` drops the
`.xmp` files, which can no longer be attached. For `file.read` the editor
warns when a rule aims at an extension its filters exclude, and a button adds
the companions' extensions. A `folder` rule needs a recursive listing.
Grouping works by folder and waits for the end of each folder. In streaming
mode with grouping rules, the files are therefore copied folder by folder, as
each folder is listed. A source that does not signal the end of its folders
would make grouping wait for the end of its whole listing: with such a source,
a streaming pipeline with grouping rules runs in auto mode instead (prepared,
then copied without confirmation), and a warning says so. The editor shows the
matching warning. `file.read`, `sftp.read` and `ftp.read` signal their folders; `s3.read` (no real folders) does not.

**Archives.** With `unfold`, a listed ZIP is replaced by the files it
contains, which are then grouped, analysed, planned and checked like any other
file; the plan shows their location (`a.zip!/DCIM`). The input filters must
let `*.zip` through. `include` and `exclude` are globs on the path inside the
archive.

- `nested` unfolds archives found inside archives, to a depth of 5 (the listed
  archive is 1); deeper ones are copied as they are, with a warning. Off by
  default, nested ZIPs are ordinary files.
- `mergeFolder` makes the archives of one folder a single tree, so that a
  photo in `takeout-001.zip` and its JSON in `takeout-002.zip` meet. Split
  Takeout exports need it (a dedicated Takeout plugin is planned).
- A ZIP that is not a local file (a remote source, or inside an archive) is
  first copied once into a workspace of the run (`copybot-archives` in the
  temporary folder), after a free space check; the preparation shows how many
  archives will be downloaded and their size. The workspace is deleted at the
  end of the copy, on cancellation, or when the plan is abandoned, and the
  folders a crash left behind are removed at the next start.
- Guards against zip bombs: entries whose data overlap refuse the archive, and
  an entry above 1 MB whose compression ratio goes over 100 is in error,
  checked on the bytes actually read. There is no limit on the number or the
  size of files, so large videos and Takeout exports pass. Names stored in
  CP437 are read as such when the archive does not say UTF-8.
- In move mode an archive is deleted only when **every** one of its entries was
  written, or found identical at the destination, during this run. If an entry
  was not copied (excluded, `read` or `ignore`, filtered, skipped by the resume
  point, in error), the archive is kept and the run gets an *archive kept*
  warning. A resumed run deletes an archive only if it copied all of it.

Known limits: the links inside a saved HTML page are not rewritten when its
main file is renamed (the `_files` folder follows); only ZIP is
supported (no 7z or tar.gz yet).

## Secondary outputs

A file is read once but can leave for several destinations, each with its own
processing: the original copied as is into the photo library, and a
thumbnail (or, with a transcoding plugin, a converted video) somewhere else.
The optional `secondaryOutputs` list holds the extra branches; the main
branch (`actionSteps` and `outStep`) is unchanged, and a pipeline without the
list behaves exactly as before.

```json
{
  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "E:\\DCIM", "include": ["**/*.jpg"] } } ],
  "analyseSteps": [ { "plugin": "com.copybot.plugin.metadataextractor", "action": "extract" } ],
  "outStep": {
    "action": "file.write",
    "actionConfig": { "outPattern": "\\\\nas\\photos\\{captureDate.Y|lastModified.Y}\\{name}" }
  },
  "secondaryOutputs": [
    {
      "name": "Thumbnails",
      "onError": "warn",
      "actionSteps": [ { "plugin": "com.acme.thumbnails", "action": "make", "actionConfig": { "size": 320 } } ],
      "outStep": {
        "action": "file.write",
        "actionConfig": { "outPattern": "\\\\nas\\thumbs\\{name}" }
      }
    }
  ]
}
```

- **Fields.** `name` is required, not empty and unique in the pipeline (it
  appears in messages, in the item detail and in the editor). `outStep` is
  required; `actionSteps` holds 0 to n process steps. `onError` is `fail`
  (default) or `warn`. At most 8 secondary outputs. Reading the pipeline checks
  this structure; the plugins and actions of the branch steps are resolved when
  the run starts, like the other steps.
- **Order.** After the last analysis, each secondary output runs in order,
  then the main branch runs **last**. Each branch gets its own copy of the
  analysed item (same source, independent metadata) and **no companion files**:
  only the main branch writes groups. A process step that produces several
  items (several thumbnail sizes) sends each one to the branch output; a step
  that filters writes nothing for the item, which is not an error.
- **The source is kept.** An item handed to a secondary output never loses
  its source: `file.write` and the remote writes (`sftp.write`, `ftp.write`, `s3.write`) ignore `deleteSource` there (logged once per
  run, and shown as a warning in the editor), so the main branch, which runs last, may still
  move the file.
- **`onError`.** With `fail`, an error in a branch puts the item in error, the
  main branch is not run for it and neither are the next branches; the item is
  not counted as done and is retried at the next run (the outputs already
  written apply their own conflict rule, typically "skip if identical"). With
  `warn` the error becomes a warning of the run and the item can succeed, but
  it will not be retried for that branch. A cancellation stops the item
  whatever the branch.
- **Result and report.** The outcome of an item is the main branch's, except
  the error of a `fail` branch. The files written by the branches appear in
  the run report with the others (`writtenFiles`); the item detail lists each
  branch: the projected target in the plan, then its real result after the
  copy. The plan's target, conflict check and per-item output pattern concern
  the main output only.
- **Editor.** Under *Output*, a *Secondary outputs* node lists the branches,
  each with its processing steps and its output, using the same forms as the
  main branch. Add, remove, rename and reorder them; the form of a branch sets
  its name and what an error does.

Limits: there is one resume cursor per pipeline, not one per branch, so a
branch added to a pipeline does not replay on files already copied (run again
with "all" if needed); the "already at the destination" check looks at the main
output only. Not supported: a branch inside a branch, branches in parallel for
one item, conflict check of the branches in the plan, companion files in
branches, conditional branches (a filter at the head of the branch does the
job: every step accepts `filterCondition`).

## Programs (the exec plugin)

The `exec` plugin has one process action, `exec.process`: it runs any program
on the file (`program`, `arguments` with `{in}`, `{out}`, `{name}` and `{ext}`,
`outputExtension`, `successCodes`, `timeoutSeconds`); what the program writes
replaces the content of the item. A batch file (`.bat`, `.cmd`) is refused: run
the program it calls. `${secret:name}` and `${env:NAME}` in the arguments are
replaced, and never logged. Its `process` package (finding a program, running
it with a time limit, the working files of a step) is what other plugins build
on: the `ffmpeg` plugin needs it.

## ffmpeg (the ffmpeg plugin)

The `ffmpeg` plugin needs the `exec` plugin (it runs ffmpeg through it):
*Edit > Plugins…* installs `exec` along with it. Until version 0.8.0-beta.1
these actions were in the `exec` plugin 1.0.0; a pipeline written then names
`com.copybot.plugin.exec` for them: Copybot rewrites such a step to
`com.copybot.plugin.ffmpeg` when it reads the pipeline (and drops its
`version`, which was the one of `exec`), so it keeps working once saved again.
Updating `exec` 1.0.0 to 2.0.0 removes these actions from it: install the
`ffmpeg` plugin from *Edit > Plugins…* to keep them. Its three actions build the ffmpeg command themselves:

| Action | What it does |
|---|---|
| `ffmpeg.video` | Videos to MP4 in H.265 (default) or H.264: `quality` `high` / `medium` / `small`, `maxHeight`, `audio` `aac` or `copy`, `encoder`. Other files pass unchanged |
| `ffmpeg.image` | HEIC (or the extensions of `onlyExtensions`) to JPEG: `quality` 1 to 100 (90), `maxSize` for the long side. Other files pass unchanged |
| `ffmpeg.thumbnail` | A small JPEG (`size`, 320 by default) of each image (`photo-thumb.jpg`) or video (`clip-video-thumb.jpg`, its frame at a tenth of its length, 10 s at most). Other files are left out |

A typical pipeline copies the originals with the main output and makes two
extra branches (see *Secondary outputs*):

```json
{
  "inSteps": [ { "action": "file.read", "actionConfig": { "path": "E:\\DCIM" } } ],
  "analyseSteps": [ { "plugin": "com.copybot.plugin.metadataextractor", "action": "extract" } ],
  "outStep": {
    "action": "file.write",
    "actionConfig": { "outPattern": "\\\\nas\\photos\\{captureDate.Y|lastModified.Y}\\{name}" }
  },
  "secondaryOutputs": [
    {
      "name": "Compressed videos",
      "onError": "warn",
      "actionSteps": [ { "plugin": "com.copybot.plugin.ffmpeg", "action": "ffmpeg.video",
                         "actionConfig": { "codec": "h265", "quality": "medium", "maxHeight": 1080,
                                           "downloadFfmpeg": true } } ],
      "outStep": { "action": "file.write",
                   "actionConfig": { "outPattern": "\\\\nas\\small\\{captureDate.Y|lastModified.Y}\\{name}" } }
    },
    {
      "name": "Thumbnails",
      "onError": "warn",
      "actionSteps": [ { "plugin": "com.copybot.plugin.ffmpeg", "action": "ffmpeg.thumbnail",
                         "actionConfig": { "size": 320, "downloadFfmpeg": true } } ],
      "outStep": { "action": "file.write",
                   "actionConfig": { "outPattern": "\\\\nas\\thumbs\\{captureDate.Y|lastModified.Y}\\{name}" } }
    }
  ]
}
```

To also turn HEIC photos into JPEG, put `{ "plugin": "com.copybot.plugin.ffmpeg", "action": "ffmpeg.image",
"actionConfig": { "quality": 90, "maxSize": 4096, "downloadFfmpeg": true } }` in the `actionSteps` of a
branch, or of the main branch if the JPEG should replace the HEIC. Each ffmpeg
action takes an optional `ffmpegPath`, a `timeoutSeconds` per file (3600 for
video, 600 otherwise; `0`: none) and a `downloadFfmpeg` box. Tick
`downloadFfmpeg` on **each** ffmpeg step: a step without it never downloads,
and fails its files until another step has installed the copy (it then finds
it).

**Finding ffmpeg.** In this order: `ffmpegPath`, the copy the plugin downloaded
(in the `plugin-data/ffmpeg` folder of the Copybot data folder), then `ffmpeg` in
the `PATH`. ffmpeg is looked for and its version read once per phase of the run
(preparation, execution). HEIC needs ffmpeg 7.1 or later:
an older one gives a clear error on each HEIC file. A copy downloaded by the
0.8.0-beta.1 `exec` plugin (in `plugin-data/exec`) is not reused: it is
downloaded again, and that folder can be deleted.

**Downloading ffmpeg** (Windows x64 only). With `downloadFfmpeg` checked, a
missing ffmpeg is downloaded when the **first file that needs it** comes up
(not at the start of the run): the GPL build of
[BtbN/FFmpeg-Builds](https://github.com/BtbN/FFmpeg-Builds) for the highest
stable `nX.Y` branch available, about 200 MB, checked against the SHA-256 file
of the same release, then installed in one move. That file waits for the
download; it can be cancelled. Copybot does not ship ffmpeg (GPL: see the plugin
notice). The plan warns before the run when ffmpeg is missing. On Linux and
macOS nothing is downloaded: install it (`apt install ffmpeg`, `brew install
ffmpeg`) or set `ffmpegPath`.

**GPU encoders.** `ffmpeg.video` `encoder` is `auto` (default), `cpu`, `nvenc`,
`qsv`, `amf`, `videotoolbox` or `vaapi`. `auto` tries each hardware encoder
once per phase with a one-frame test encode, in that order, and takes the first
that works, else the processor. An explicit encoder that fails the test falls
back to the processor, with a warning in the log only. A hardware encoder holds the `gpu` resource and the
processor holds `cpu`, so the number of simultaneous encodings is set in
`config.json`:

```json
{ "resources": { "gpu": 1, "cpu": 2 } }
```

Limits:

- The EXIF data of a HEIC is **not** copied into the JPEG (ffmpeg does not
  write it). The JPEG gets the capture date as its file date when it is known,
  else the source's; the original still goes through the main output.
- The rotation of a HEIC is applied from ffmpeg 7.1; the EXIF orientation of a
  JPEG, PNG or TIFF depends on the ffmpeg build. Neither has been verified on real
  photos yet.
- H.265 is always 10-bit (Main10): an old TV that reads only Main profile cannot
  play it. H.264 is 8-bit.
- Apple metadata tags are kept as `mdta` keys: the title and comment of an
  iPhone video may not show in Explorer-like tools.
- An explicit encoder that fails is only logged, not shown in the run warnings.
- No download button in the editor (the plugin API has none): tick
  `downloadFfmpeg`.
- A video without a video stream passes unchanged (`ffmpeg.video`) or is left
  out (`ffmpeg.thumbnail`); this is detected from ffmpeg's English message.

## Remote servers: SFTP, FTP and S3 (the remote plugin)

The `remote` plugin (`com.copybot.plugin.remote`) reads files from a server
and writes files to one, with SFTP, FTP or FTPS, or an S3 bucket (AWS, MinIO
and other S3-compatible stores):

| Action | Kind | Library |
|---|---|---|
| `sftp.read` / `sftp.write` | in / out | [JSch](https://github.com/mwiede/jsch) (the maintained fork) |
| `ftp.read` / `ftp.write` | in / out | [Commons Net](https://commons.apache.org/proper/commons-net/): FTP, FTPS explicit or implicit |
| `s3.read` / `s3.write` | in / out | the JDK only (its HTTP client, its own SigV4 signature) |

A NAS reached by SFTP as a source, and an S3 bucket as the target:

```json
{
  "inSteps": [
    { "plugin": "com.copybot.plugin.remote", "action": "sftp.read",
      "actionConfig": { "host": "nas.local", "user": "photos", "privateKey": "C:\\Users\\me\\.ssh\\id_ed25519",
                        "passphrase": "${secret:nas-key}", "folder": "/volume1/upload",
                        "include": ["**/*.jpg", "**/*.nef"] } }
  ],
  "analyseSteps": [ { "plugin": "com.copybot.plugin.metadataextractor", "action": "extract" } ],
  "outStep": {
    "plugin": "com.copybot.plugin.remote", "action": "s3.write",
    "actionConfig": { "region": "eu-west-3", "bucket": "my-photos",
                      "accessKey": "AKIAIOSFODNN7EXAMPLE", "secretKey": "${secret:aws-secret}",
                      "outPattern": "{captureDate.Y|lastModified.Y}/{captureDate.m|lastModified.m}/{name}",
                      "onConflict": { "compare": "sizeAndDate" } }
  }
}
```

An FTPS server as the target (the `outStep`):

```json
{ "plugin": "com.copybot.plugin.remote", "action": "ftp.write",
  "actionConfig": { "host": "ftp.example.org", "user": "copybot", "password": "${secret:ftp-password}",
                    "security": "explicit", "outPattern": "photos/{captureDate.Y|lastModified.Y}/{name}" } }
```

A MinIO bucket as a source (an entry of `inSteps`):

```json
{ "plugin": "com.copybot.plugin.remote", "action": "s3.read",
  "actionConfig": { "endpoint": "https://minio.local:9000", "region": "us-east-1", "bucket": "camera",
                    "pathStyle": true, "accessKey": "copybot", "secretKey": "${secret:minio-secret}",
                    "folder": "dcim" } }
```

Put passwords, passphrases and secret keys in the vault (see *Secrets*): they
never appear in a log, a message, a plan or a warning.

**Connection fields.**

| Protocol | Fields |
|---|---|
| SFTP | `host` (required), `port` (22), `user` (required), `password`, `privateKey` (a key file), `passphrase` |
| FTP | `host` (required), `port` (21, 990 with `implicit`), `user` (required), `password`, `security` `explicit` (default: TLS after `AUTH TLS`), `implicit` (TLS from the start) or `none`, `passive` (true) |
| S3 | `region` (required), `bucket` (required), `endpoint` (absent: `https://s3.<region>.amazonaws.com`), `accessKey` and `secretKey` (neither: anonymous requests, for a public bucket), `pathStyle` (false; true for MinIO or an endpoint given by its address) |

**Input fields** (`*.read`): `folder` (required; an S3 prefix, `/` for the
whole bucket), `recursive` (true), `include` / `exclude` (globs relative to the
folder, case-insensitive, as `file.read`), `minSize` (bytes), `maxConnections`
(4). Names starting with `.` (files and folders) are never listed. Each file
keeps its date: SFTP mtime, FTP `MLSD` (else `LIST` and `MDTM`), S3
`LastModified` (the date of the upload).

**Output fields** (`*.write`): `outPattern` (as `file.write`, `/` between the
names; relative to the starting folder of the user on SFTP and FTP, a key in
S3), `onMissingKey`, `onConflict` (`compare` `size` (default) or `sizeAndDate`
(within 2 s); `ifIdentical` `skip` (default), `ifDifferent` `rename` (default,
`name (1).ext`), each also `overwrite` or `error`), `deleteSource` (false: true
deletes the source once its copy is verified, also a remote source),
`maxConnections` (4). Nothing compares contents on a server: a file of the
same size counts as identical with `size`. On SFTP and FTP a file is written
under a hidden temporary name (`.<name>.<run>-<n>.copybot-tmp`), its size
checked, then renamed; folders are created as needed. On S3 an object appears
whole or not at all, so it is written under its final key. The date of the
source is given to the file (SFTP, FTP with `MFMT` or `SITE UTIME`, S3 as
`x-amz-meta-mtime` metadata, as rclone does), so `sizeAndDate` finds it again
on the next run. A server that cannot set dates (vsftpd has neither `MFMT` nor
`SITE UTIME`) makes `sizeAndDate` compare sizes only, with a warning in the log.

**Connections and resources.** A step opens at most `maxConnections`
connections to its server and closes them at the end of the run (an idle one
also after 30 s). The listing of an input borrows one for each folder it lists
(for the whole listing on S3) and gives it back before handing on the files,
so a listed file can be read at once (an archive unfolded) even with
`maxConnections` 1. On S3 the files are therefore handed on only once the
whole listing has ended, even in streaming mode.
The listing of an input and each file written by an output hold the resource
`net:<protocol>:<host>` (`net:sftp:nas.local`, `net:ftp:ftp.example.org`,
`net:s3:s3.eu-west-3.amazonaws.com` for every bucket of that endpoint), whose
capacity is **1** unless `config.json` says otherwise: a remote output writes
one file at a time, and a pipeline reading and writing on the same host (or two
buckets of the same endpoint) starts no write before its listing ends. Reading
the content of a listed file takes no resource, only one of the input's
`maxConnections`. Raise the capacity with:

```json
{ "resources": { "net:*": 4 } }
```

Connecting gives up after 20 s; a transfer that makes no progress for 60 s
fails (SFTP notices a dead link through keep-alives, within about 80 s).

### SFTP: the server's key

The first connection to a server shows its key fingerprint
(`SHA256:…`, as `ssh-keygen -lf` prints it) and asks whether to trust it,
before any password is sent. In the application this is a dialog; the editor's
sample never asks (start a run to answer). From the **command line** the answer
is yes, with a line on stderr naming the fingerprint: a first headless run
trusts the key it sees, so check that line, or run once from the application.
A trusted key is written to the plugin's `known_hosts` file
(`plugin-data/remote/known_hosts` in the Copybot data folder, OpenSSH format;
`[host]:port` when the port is not 22). A key that differs from the recorded one
is **refused** without a question, the message giving both fingerprints and
the file to edit if the server really changed its key. A key of another type
than the recorded ones also counts as changed: remove the server's line to
trust it again. A no is remembered until the end of the run. Only modern
algorithms are accepted (Ed25519, ECDSA, RSA with SHA-2); a server offering only
`ssh-rsa` (SHA-1) is refused.

### FTPS: the server's certificate

A certificate is first checked as a browser would (a trusted authority of the
Java runtime, and the host name). A NAS with a self-signed certificate fails
that check: Copybot then asks once whether to trust it, showing the SHA-256
fingerprint of its public key, its subject, issuer and validity, and the names
it holds when they are not the host's. Yes records the fingerprint in
`plugin-data/remote/ftps_trust` (one `host:port SHA256:…` line per server); that
certificate is then accepted even once expired or renewed with the same key.
Another key is refused, with both fingerprints and the file. As for SFTP, the
command line answers yes with a warning on stderr. With `security: none` and a
password, the password travels in clear and the step gets a warning.

### S3: endpoint, permissions and multipart

- **Address.** By default the bucket goes into the host name
  (`https://my-photos.s3.eu-west-3.amazonaws.com/key`), which needs a bucket
  name made of lower case letters, digits, dots and hyphens (3 to 63
  characters; a name with dots fails the certificate check of AWS over
  `https`); for a dotted or any other name, or a server given by its address, set
  `pathStyle: true` (`https://endpoint/bucket/key`), as MinIO usually needs. A
  bucket of another region is named in the error with its region. An `http://`
  endpoint gets a warning: the files travel in clear (the secret key is never
  sent, only signatures).
- **Permissions.** `s3:ListBucket` on the bucket (listing, and checking a
  target: without it, S3 answers 403 instead of 404 for a missing key, and the
  write fails as *access denied*), `s3:GetObject` (reading, and the `HEAD` that
  checks a target), `s3:PutObject` (writing, multipart included),
  `s3:AbortMultipartUpload`, and `s3:DeleteObject` on a source bucket whose
  objects are moved (`deleteSource`) and on the target bucket (an object whose
  check failed is removed).
- **Large files.** Up to 16 MiB a file is sent in one `PUT`; above, in parts of
  16 MiB (larger when 10,000 parts would not hold the file). Each connection
  keeps one 16 MiB buffer while it lives: 64 MiB of memory for a step with the
  default 4 connections. A failed or cancelled upload is aborted, so no part
  is left; when even the abort fails (network down), the parts stay billed in
  the bucket. Add a lifecycle rule *Delete expired object delete markers or
  incomplete multipart uploads* (`AbortIncompleteMultipartUpload` after 1 to
  7 days) to clean them up.
- A listing gives the date of the upload, not of the photo: an `s3.read`
  source has the date the object was written (Copybot's own objects keep the
  original date in their metadata, read back when they are checked as targets).

### Limits

- The plan shows **unknown target** for a remote output: the target is
  computed during the copy. The resume mode `destination` is refused for a
  remote output; `stateThenDestination` (the default) uses the saved cursor and,
  without one, takes every file with a warning.
- Remote targets are not in the run's list of written files (no SHA-256 in the
  run report), so `archive.zip` leaves them out.
- Companion files to copy (grouping with `copy`) are refused for a remote
  output: the pipeline does not load.
- Hidden names (starting with `.`) are never listed, with no option.
- No WebDAV or SMB (use a mounted share with `file.read` / `file.write`), no SSH
  agent, no IAM role or AWS profile (keys only), no retry of a failed transfer
  within the run (S3 `503 SlowDown` included).

## After the copy (run-end actions)

A pipeline can end with an ordered list of actions, `runEnd`, set in the
editor under *After the copy*. Each entry names an action and when it runs:

```json
"runEnd": [
  { "action": "post.process", "when": ["success", "error"],
    "actionConfig": { "command": "C:\\Tools\\fix-rights.exe",
                      "args": ["--new-folders", "{createdDirs}"] } },
  { "plugin": "com.copybot.plugin.archive", "action": "archive.zip", "timeoutSeconds": 0,
    "actionConfig": { "target": "D:\\Archives\\{pipeline}-{date}.zip" } }
]
```

| Field | Meaning |
|---|---|
| `plugin` | The plugin of the action; absent for the embedded `post.process` |
| `action` | The action code |
| `when` | Among `success`, `error` and `cancelled`; absent: `success` and `error` (an empty list or another value is refused) |
| `timeoutSeconds` | How long the action may take; `0` means no limit. Absent: 30 s for `post.process`, 60 s for e-mail and webhook, no limit for the archive. A negative value is refused |
| `actionConfig` | The configuration of the action |

**When actions run.** After every run that was started to copy, including a
run whose preparation failed, and never after a plan-mode preparation alone.
The actions run in list order, only for the outcomes in their `when`, and the
status of the run is published once they are over (the action in progress is
shown). An action that fails or times out gives a warning and does not stop the
next ones. Cancelling skips the actions that remain. An action can hand values
to the next ones: the archive publishes `archive.path`, which the e-mail and
the webhook can put in their message as `{output:archive.path}`.

**From `postProcess`.** A pipeline with the former `postProcess` block is
converted when it is read (and in the editor): the block becomes a first
`post.process` entry running on success and error, with the same command and
arguments, unless the list already has one. The file is rewritten in the new
shape the next time you save it.

### post.process

Starts a program and does not wait for it. `command` is the program and `args`
its arguments; an argument `{createdDirs}` becomes one argument per directory
the copy created, and if the copy created none the program does not start.
If the program cannot start, the run gets a warning.

### Secrets

Anywhere in the configuration of an action, a text can hold `${secret:name}`
(a secret of the vault) or `${env:NAME}` (an environment variable), several per
value. They are replaced just before the action loads its configuration; the
pipeline file keeps only the references, so it can be shared. `$${` stands for
a literal `${` (for example PowerShell's `${env:PATH}` in the arguments of a
program). Names are made of letters, digits, `.`, `_` and `-`.

The vault is a per-user file: on Windows `%APPDATA%\Copybot\secrets.json`, the
values protected with DPAPI (readable by your account on this machine only);
elsewhere `$XDG_CONFIG_HOME/copybot/secrets.json` when that variable is an
absolute path, else `~/.config/copybot/secrets.json`, readable by its owner only. Manage
it from **Edit > Secrets…** in the application, or from the command line:

```
copybot secret set smtp-password     # the value is read without echo (or from stdin in a script)
copybot secret list                  # names only
copybot secret delete smtp-password
```

A reference to an undefined secret or variable refuses the step, naming the
reference. An unknown secret name in the editor shows a warning (a pipeline may
come from another machine). Secret values never appear in logs, messages or
warnings of the actions: they are shown as their references. That holds for
every failure of a step, including a failed listing, the plan's targets, a
dry run, the editor's sample and the actions after the copy. A password typed
in clear in a field marked as secret gets a warning and an offer to move it to
the vault.

### notify-email: `email.send`

```json
{ "plugin": "com.copybot.plugin.notifyemail", "action": "email.send", "when": ["success", "error"],
  "actionConfig": {
    "host": "smtp.example.org", "port": 587, "security": "starttls",
    "user": "bot@example.org", "password": "${secret:smtp-password}",
    "from": "copybot@example.org", "to": ["me@example.org"],
    "subject": "Copybot \u2014 {pipeline} : {outcome}", "details": "errors" } }
```

`security` is `starttls` (default, required, not merely tried), `ssl` (TLS from
the start, usually port 465) or `none`; `port` defaults to 465 with `ssl`, 587
otherwise. With no `user`
there is no authentication. With `none` and a user, the password travels in
clear, and the run gets a warning. The subject accepts `{pipeline}`,
`{outcome}`, `{written}`, `{skipped}`, `{errors}`, `{duration}` and
`{output:key}`. `details` lists in the message `none`, the items in `errors`
(default) or `all`. Connection, read and write timeouts of the SMTP socket are
fixed at 20 s.

### notify-webhook: `webhook.send`

```json
{ "plugin": "com.copybot.plugin.notifywebhook", "action": "webhook.send",
  "actionConfig": { "url": "https://ntfy.sh/${secret:ntfy-topic}", "preset": "ntfy" } }
```

`preset` shapes the message: `json` (default; the summary of the run with the
items in error, the `outputs` of the previous actions and the
`createdDirectories`, without the list of written files), `ntfy` (plain text, a title, high priority on
error), `discord`, `slack` or `telegram`. The Telegram preset posts to
`api.telegram.org` with `"chat_id": "CHAT_ID"`: replace it through `body`. `headers`
is a list of `Name: value` lines that replace the preset's header of the same
name; `body` replaces the message with a template using `{pipeline}`,
`{outcome}`, `{written}`, `{skipped}`, `{errors}`, `{duration}`, `{summary}` and
`{output:key}`. Redirects are not followed (a 3xx is a failure). The URL, which
may embed a token, and the header values are never written to a message or a log.

### archive: `archive.zip`

```json
{ "plugin": "com.copybot.plugin.archive", "action": "archive.zip",
  "actionConfig": { "target": "D:\\Archives\\{pipeline}\\{date}-{time}.zip",
                    "content": "zip", "includeSkipped": false, "baseFolder": "D:\\Photos" } }
```

Writes an archive of the files the run copied, with the checksums computed
during the copy (recomputed for a skipped file). `target` replaces `{date}`
(`yyyy-MM-dd`), `{time}` (`HHmmss`) and `{pipeline}`; a relative path is
relative to the pipeline's folder, as is `baseFolder`, the folder the entry
paths are relative to (a file outside it is left out, with a warning). Without
`baseFolder`, the entry paths are relative to the common directory of the
files; when they lie on several drives or shares (a secondary output to another
NAS), each file keeps its full path under a top folder named after its drive or
share (`C/…`, `D/…`, `nas_photos/…`), so none is left out. `content` is `zip` (default: the files and a
`SHA256SUMS` manifest) or `manifest` (the `SHA256SUMS` file alone).
`includeSkipped` also archives the targets of skipped items. Media entries
(jpg, heic, raw, mp4, mov…) are not compressed (deflate level 0), the others
are. The archive is written under a temporary name then renamed, and never
overwrites a file: an existing target gets a ` (n)` suffix, and the real path
is published as `archive.path`. If nothing was written, or the action is
interrupted, nothing is produced. A file named `SHA256SUMS` at the root of your
files is left out of the archive, with a warning.

## Plugins

A plugin is a **JPMS module** that `provides com.copybot.plugin.api.definition.IPlugin`.
You drop it, with its dependencies, into a sub-folder of the `plugins` folder
of the application folder. Each plugin is loaded in a **module layer of its
own**, so two plugins can use different versions of the same library. A
plugin can also depend on another plugin. When two copies of a plugin are
installed, the newer revision of a version wins, and different minor versions
can live side by side. A plugin that fails to load is reported in the plugins
window, and the other plugins still load.

**Plugins that need other plugins** (`ffmpeg` runs ffmpeg through the process
package of `exec`). The needed plugin exports to every module only the
packages it shares (`exec` exports `com.copybot.plugin.exec.process`; its
plugin class is exported to the engine alone and its actions are opened to the
engine and Gson only); keep them small, documented, and raise its major
version when they break. The plugin that needs it declares
`requires <its main module>;` in its `module-info.java` and depends on it in
its `pom.xml` with `<scope>provided</scope>`, so that the needed plugin is not
copied into its `lib/` nor into its archive; `javac` records the version built
against in the `requires`. **The required version is the version of that
dependency** (`ffmpeg` depends on `exec` `2.0.0`): when you raise the version
of a needed plugin, update that dependency in the plugins that need it (the
build fails otherwise, and the release script refuses a published entry whose
requirement no longer matches). A new major
version breaks them until they are adapted and given a new version. The engine
loads a plugin over the layer of the needed plugin, which must be installed in
the same major version, at least as recent; otherwise the plugin is shown
failed with the plugin it misses. The release catalogue reads that `provided`
dependency and writes `"requires": [ { "code": "exec", "version": "2.0.0" } ]`
in the entry; it fails when a requirement is not met by the current version of
the needed plugin, including for an entry it keeps from an earlier release.
*Edit > Plugins…* installs or updates the needed plugins along with it (in the
same restart, listed in the confirmation); uninstalling a plugin that others
need names them before anything is done, and installing a plugin while
uninstalling one it needs is refused. The Updates window ticks the update of a
needed plugin with the plugin that needs it (only when that update meets the
requirement), installs a needed plugin that is not installed along with it
(also with an update of Copybot), and leaves unticked a plugin whose needed
plugin cannot come, saying why (for instance when that plugin needs a newer
Copybot).

To write an action, you extend `AbstractActionWithConfig<C>`, where `C` is a
record holding the action's configuration. You implement `IInAction`,
`IAnalyzeAction`, `IProcessAction`, `IOutAction` or `IRunEndAction`. Annotations on the
record's fields (`@Required`, `@DefaultValue`, `@AllowedValues`,
`@DirectoryPath`, `@PatternField`…) give the editor what it needs to build
the form. An action can also declare the resources it needs, report
configuration warnings, and describe what it would do in a dry run. One
instance of an action serves every file of its step, concurrently: it
reports the progress of the file it works on with `updateStatus` /
`updatePercent` (an out action through the `progress()` of its
`WriteContext`), shown in the plan view, and gets `beforeAll` / `afterAll`
once per phase (preparation, execution) and `beforeEach` / `afterEach`
around each file (see the `IAction` javadoc).

The rest of the API, by need (see each javadoc):

- **Run-end actions.** `IRunEndAction.runEnded(RunReport, RunEndContext)`,
  listed by `IPlugin.getRunEndActions()`: runs once after the run with the
  report of what it did; `RunEndContext.publish` hands values to the next
  actions, `warn` adds a run warning, and `defaultTimeout()` sets its time
  limit (60 s by default, `Duration.ZERO` for none).
- **Listing by folder.** An input lists into a `ListingSink` (`root`, `item`,
  `folderDone`); with `signalsFolders()` true it promises each `folderDone`,
  so grouping and a streaming run settle folder by folder.
- **Items that are not local files.** `WorkItem.remote(...)` builds an item
  whose content is read from a stream (an archive entry, a remote file), with a
  `SourceDeleter` that deletes its source once copied (null: never deleted).
  `getContentFile()` gives the local file holding exactly the content, when
  there is one, for a tool that reads a path.
- **Secondary outputs.** `isSourceKept()` marks the copy a secondary output
  receives (`withSourceKept()` makes one): an out action never deletes its
  source. `IOutAction.writesCompanions()` says the action writes the item's
  companions itself.
- **Questions to the user.** `UserConfirmation.ask(question)` blocks until the
  user answers (trusting a host seen for the first time, for example); ask it
  from `listFiles`, `beforeAll` or a step of an item, never from `afterAll`.
- **Plugin data.** `PluginFolders.dataDirectory(code)` is a folder kept across
  runs (a tool the plugin downloaded).
- **Secrets.** `@Secret` on a configuration field (`FieldHint.SECRET` in the
  schema) makes the editor offer a secret of the vault instead of a typed
  value; `${secret:name}` references are resolved before `loadConfig`.

A plugin can also bring **grouping presets** (see
[Companion files and archives](#companion-files-and-archives)): it returns
them from `IPlugin.getGroupingPresets()`, built with the factories of
`com.copybot.plugin.api.grouping.GroupingRule` (one per rule type, with the
same meaning and the same checks as the rules written in a pipeline):

```java
@Override
public List<GroupingPreset> getGroupingPresets() {
    return List.of(new GroupingPreset("takeout",
            GroupingRule.regex("(.+\\.(?:jpe?g|heic|mp4))", "\\1(?:\\.supplemental-metadata)?\\.json",
                    GroupingRole.READ, GroupingLink.NAME),
            GroupingRule.sameBase(List.of("heic", "jpg"), Map.of("mov", GroupingRole.COPY))));
}
```

A preset code is made of `a-z`, `0-9` and `-`, and has at least one rule. Its
name and description in the editor come from the plugin's bundle, under
`plugin.<plugin code>.preset.<preset code>.name` and `.description` (the code
is shown when there is no name):

```properties
plugin.takeout.preset.takeout.name=Google Takeout (photos and their JSON)
plugin.takeout.preset.takeout.description=Attaches each JSON metadata file to its photo or video, for the analysis.
```

A preset with invalid rules (a regular expression that does not compile, a
`folder` without `{base}`…) is left out when the plugin is loaded, with a
warning in the log; a plugin whose `getGroupingPresets()` fails (a blank
companion extension refused by `GroupingRule`, for example) has none and is
shown as failed in the editor; a run citing one of its presets is refused with
a message that points to the log.

The plugin API is what the engine module, `com.copybot.engine`, exports to
every module (`com.copybot.plugin.api.*`, `com.copybot.utils`,
`com.copybot.resources`, `com.copybot.exception`). It has a version of its
own, `X.Y`, distinct from the version of Copybot: the property
`copybot.plugin.api.version` of the root `pom.xml`, which the build makes the
module version of `com.copybot.engine` (`jar --describe-module` shows
`com.copybot.engine@1.2` for the current API, 1.2). A plugin declares the API it was built for without
doing anything, since `javac` records the version of the engine module it
compiles against in the plugin's `requires com.copybot.engine`. A plugin runs
on every Copybot whose API has the same major version and a minor version at
least the one it was built for; one built for another major version, or for a
newer minor version, is not loaded: the plugins window says which API it
needs. Rebuild it against this version of the engine. The About window shows
the plugin API version. The built-in actions (`file.read`, `file.write`) are
reported with the API version too: a `version` pinned on an embedded step of
a pipeline is an API version.

**When to raise the API version:** the question is whether an existing plugin
still works untouched.

- **Minor** (`1.0` → `1.1`): the API gains something **optional**, which
  existing plugins may ignore. Examples: a new type, a field, a constructor, a
  concrete or `default` method, an annotation element with a default value.
  Only a plugin that uses the novelty needs the new API, and it records it.
- **Major** (`1.4` → `2.0`): the API **removes or changes** something, or adds
  something **mandatory** for plugins. Mandatory means something an existing
  plugin does not provide: a type that plugins implement or extend gains an
  abstract member (an interface method without `default`, an abstract method),
  an annotation gains an element without a default value, a `default` method
  becomes abstract. Turning an abstract method `default` is not breaking.

You cannot forget: the `PluginApiFingerprintTest` of `copybot-engine`
compares the API with its committed fingerprint,
`copybot-engine/src/test/resources/plugin-api.txt` (`api X.Y`, then one sorted
line per public or protected type, field, constructor and method, with its
signature), and fails the build with what changed and the version to set
(the release workflow runs it before publishing). Raise the property, then
regenerate the fingerprint, commit it, and rebuild everything so that the
plugins record the new version in their `requires`:

```sh
mvn clean verify -pl copybot-engine -Dplugin.api.update=true
mvn clean verify
```

(`clean`: the compiler does not notice a new module version by itself.) The
update prints the lines removed (`-`) and added (`+`). It refuses to record a
change whose version is not raised enough, and never recreates a deleted
file; a version raised without any change is allowed, and only needs the file
regenerated. `-Dplugin.api.update=force` rewrites the file whatever the
comparison says (and creates it when missing): use it only after reading the
diff it prints, when the change is not one plugins can see, such as a JDK
that prints signatures differently, interfaces listed in another order, or an
abstract method made `default`.

The fingerprint only sees signatures. Raise the version **by hand**, with the
same rule, for what it does not see:

- **Any new obligation for plugins that signatures do not show: major.** For
  example, the engine now requires a plugin to declare a key, to call a
  method, or to follow a contract written only in the javadoc.
- A change of behaviour behind an unchanged signature: major if existing
  plugins can break, minor otherwise.
- The value of a `static final` constant (javac copies it into the plugins).
- The `@Retention` or `@Target` of an annotation (its elements and their
  default values are in the fingerprint).
- A `sealed` type's `permits`.
- An upgrade of Gson (the engine `requires transitive com.google.gson`, and
  `JsonElement` is in signatures).

The [`copybot-plugin-demo`](copybot-plugin-demo) modules are worked examples:
a standard module plugin, the same plugin built on an older library, a
plugin that depends on two others, and a plain (non-module) jar. The library
they embed is a demo library of the project, `copybot-plugin-demo-lib`, in two
versions with incompatible APIs: 2.0 is a module, 1.0 an old style jar (an
automatic module named after its file). Each plugin prints which version
answered and from which jar.

## Quick start

You need **JDK 25** and Maven (3.8.8 or later).

```sh
mvn verify
```

builds the modules and runs the test suite (about 2,350 tests across the
engine, the UI and the plugins). To build the desktop application as well, add
the `dist` profile:

```sh
mvn -Pdist package
```

It produces:

- `copybot-ui/target/dist/Copybot/` — the desktop application (`Copybot.exe` on Windows, `bin/Copybot` on Linux), as a jpackage app image;
- `copybot-ui/target/copybot/` — the same as a jlink runtime image, also zipped as `copybot.zip`;
- `copybot-plugin/copybot-plugin-metadata-extractor/target/` — the metadata plugin. Copy its jar and its `lib/` folder into `plugins/metadata-extractor/` next to the application.
- `copybot-plugin/copybot-plugin-gps-zone/target/` — the optional GPS time zone check. Copy its jar and its `lib/` folder (about 36 MB, mostly the time zone boundaries) into `plugins/gps-zone/` next to the application. It loads the boundaries at the first photo whose GPS can tell (about 1 s and 200 MB of memory), never for an import without GPS. Its data is under the ODbL: see [NOTICE](NOTICE).
- `copybot-plugin/copybot-plugin-notify-email/target/` — the end-of-run e-mail. Copy its jar and its `lib/` folder (Jakarta Mail) into `plugins/notify-email/` next to the application.
- `copybot-plugin/copybot-plugin-notify-webhook/target/` — the end-of-run webhook (ntfy, Discord, Slack, Telegram, JSON). Copy its jar into `plugins/notify-webhook/` next to the application.
- `copybot-plugin/copybot-plugin-archive/target/` — the end-of-run ZIP archive of the copied files. Copy its jar into `plugins/archive/` next to the application.
- `copybot-plugin/copybot-plugin-exec/target/` — `exec.process`, any program on each file. Copy its jar into `plugins/exec/` next to the application. See *Programs*.
- `copybot-plugin/copybot-plugin-ffmpeg/target/` — the ffmpeg actions (compressed videos, HEIC to JPEG, thumbnails). Copy its jar into `plugins/ffmpeg/` next to the application, with `exec` installed (it needs it). It does not ship ffmpeg: see *ffmpeg*.
- `copybot-plugin/copybot-plugin-remote/target/` — SFTP, FTP / FTPS and S3 sources and targets. Copy its jar and its `lib/` folder (JSch, Commons Net, Commons IO) into `plugins/remote/` next to the application. See *Remote servers*.

**Where Copybot keeps its files:** the code (the application and its
`plugins` folder) lives in the application folder. The data (`config.json`,
`prefs.json` for the UI preferences, and `logs`) lives beside it when that
folder is writable, else in `%APPDATA%\Copybot` (`~/.copybot` without
`APPDATA`). `--config-file=<file>` puts the data in the folder of that file.
Unzip the application outside `Program Files` to get a self-contained folder.

**Installing a release:** a release offers `copybot-<version>-setup.exe`, an
installer that asks whether to install for you alone (no administrator rights,
the default) or for all users, and `copybot-<version>-windows-x64.zip`, the
portable version (unzip it anywhere and run `Copybot\Copybot.exe`). A new
installer installs over the previous version and keeps your data and plugins.
It also offers `copybot-<version>-linux-x64.tar.gz`, the Linux version, not
tested on a real Linux yet: extract it anywhere and run `Copybot/bin/Copybot`.
The video and sound preview needs GStreamer and ffmpeg (libavcodec), and
updating from the application is Windows only.

**Making a release:** on `develop`, set the version of the application to
`X.Y.Z` (drop `-SNAPSHOT`) in the poms, commit and push. Then go through a
pull request from `develop` to `master` titled `Release X.Y.Z`, merge it, and
tag the merge commit on `master`:

```sh
gh pr create --base master --head develop --title "Release X.Y.Z" --body "Copybot X.Y.Z"
gh pr merge --merge --subject "Release X.Y.Z"
git fetch origin master
git tag -a vX.Y.Z -m "Copybot X.Y.Z" origin/master
git push origin vX.Y.Z
```

`master` holds the published versions. The pull request is what the generated
release notes list: whoever opens it is named in the notes and among the
contributors of the release (commits pushed straight to a branch are not).
The official
plugins (`metadata-extractor`, `gps-zone`) have their own version, in their own
`pom.xml` (`1.0.0`, never `-SNAPSHOT`; raising `exec`, which `ffmpeg` builds
against, also means updating `ffmpeg`'s dependency, see *Plugins that need
other plugins*): change the application version
without overwriting theirs (only the `<parent>` of a plugin follows the
application; a bulk replace of the version, such as `mvn versions:set`, would
also overwrite the plugin versions: restore them afterwards). **Raise the version
of a plugin that changed** (any file of its module, its `catalog.json`
included) before the release; leave it alone otherwise. The `release` workflow
checks that the tag matches the pom version, builds the application, the
installer and the zip, the Linux archive on a Linux runner, and attaches them to a **draft** release. Publish the
draft once you have read it, then set the poms of `develop` to the next `-SNAPSHOT`.

Every release also carries a `catalogue.json` listing all the official
plugins, each with the release that hosts its archive
(`copybot-plugin-<code>-<version>.zip`, one `<code>/` folder holding the jar
and its `lib/`, made by `mvn package` in the plugin's `target/`). The workflow
(`.github/scripts/plugin-catalogue.ps1`) reads the `catalogue.json` of the
latest **published** release (a draft does not count; with none, the catalogue
starts empty): a plugin whose version did not change keeps its entry and its
archive is not published again, a new version gets its zip on the current
release. The release **fails** when a plugin kept its version although its
module changed since the release of its entry (version not raised), or when a
kept plugin was built for a plugin API that is not compatible any more (new
major version of the API: give the plugin a new version), or when a plugin,
kept or new, requires a version of another plugin that its current version no
longer meets (a new major version of `exec`: adapt `ffmpeg` and give it a new
version). The `engine` of an
entry is the plugin API version (`X.Y`) its archive was built for, which the
plugins window compares with the API of the running Copybot (never with the
version of the application). The description
of a plugin, and its licence notice (`gps-zone`: ODbL), come from the
`catalog.json` of its module.

**Making a beta release:** set the version of the application to
`X.Y.Z-beta.N` (`beta.1`, `beta.2`, ...; `alpha`, `rc` work too:
`alpha < beta < rc < X.Y.Z`, and `beta.10` comes after `beta.9`) in the poms,
and tag `vX.Y.Z-beta.N` as for a release (the workflow runs for any `v*`
tag, whatever its branch). The workflow accepts a pom version `X.Y.Z` or
`X.Y.Z-<qualifier>` and still requires the tag to be `v<pom version>`; a tag
with a `-` makes the draft a **pre-release** (the installer's file version is
`X.Y.Z`, the qualifier stays in its name and in the application). Read the
draft and publish it: only the users with **Receive beta versions** are offered
it (`releases/latest`, which the others ask, never returns a pre-release). Its
`catalogue.json` is built against the latest published **stable** release
(pre-releases do not count): it republishes the plugins changed since then,
and so does the next beta and the next stable release, so each release carries
the archives of its new plugin versions. Raise the version of a changed plugin
for the beta as for a stable release.

To run a pipeline from the command line with the jlink image:

```sh
copybot-ui/target/copybot/bin/java --enable-native-access=com.copybot.engine \
    -m com.copybot.engine/com.copybot.Copybot \
    -p photos.json -c config.json --dry-run
```

The metadata plugin needs the `java.desktop` module. The runtime image holds
it; with a custom image or `--limit-modules`, add `--add-modules java.desktop`.
The gps-zone plugin decompresses its data with a native library (zstd-jni):
the engine grants native access to the modules of the plugins when it has it
itself (`--enable-native-access=com.copybot.engine`, as above); without, the
JVM warns, which does not stop it. At the first photo with GPS, its protobuf
library also prints a JVM warning about a terminally deprecated
`sun.misc.Unsafe` method on stderr: harmless, it does not stop the run.

| Exit code | Meaning |
|---|---|
| `0` | Everything was copied or skipped as planned |
| `1` | Some files failed, or a listing failed |
| `2` | The configuration or the pipeline is invalid, or a plugin could not be loaded |
| `130` | Cancelled (Ctrl+C) |

`config.json` is optional (without it, the defaults apply). It sets the
resource capacities:

```json
{
  "resources": { "disk:D:\\": 1, "gpu": 1 },
  "resourceGroups": [["disk:D:\\", "disk:\\\\nas\\photos\\"]]
}
```

A disk resource is named after the physical disk: `disk:PhysicalDrive1` on
Windows, `disk:sda` or `disk:nvme0n1` on Linux. In the configuration you can
name it by any folder or drive on it instead (`disk:D:\`, `disk:D:`,
`disk:/home`; in JSON, `"disk:D:\\"`). If two volumes of one disk get different capacities, the
smaller one is kept. When no physical disk can be found, the resource is the
volume itself (`disk:\\server\share\`, `disk:/mnt/nas`). That happens for
network shares, for volumes spread over several disks (RAID, spanned volumes,
LVM over several disks, Storage Spaces), and on other systems. Put such
volumes in a `resourceGroups` entry to make them share one budget. The default
capacity is 8 for an NVMe disk, 4 for an SSD, and 2 for a hard disk or a disk
of unknown kind. A `disk:*` entry replaces these defaults.

You do not have to write these by hand: **Edit › Resources** lists the
volumes of the machine with their physical disk, kind and capacity. There you
can set the capacity of a disk, add a share, and group it with a disk. The
changes apply from the next plan, without a restart. When a pipeline is open,
its disks are marked with a blue dot and listed even when they are not there
(a card not inserted, a NAS switched off), so you can set them beforehand;
they are written only once you set them. A drive letter mapped to a share and
the share itself (`Z:\` and `\\nas\photos\`) are one resource.

**Development:** run `com.copybot.ui.CopybotMainUi` with the argument
`--config-file=./copybot-ui/src/dev/config.json` and the repository root as
working directory (the shared IntelliJ run configuration `CopybotMainUiDev`
does that). That run configuration loads the metadata and gps-zone plugins
straight from their `target/` folders through the `copybot.devPluginPaths`
system property (folders separated by the platform path separator, `;` on
Windows; ignored in the packaged application), so package these plugins once
first. A dev run of the UI also needs the folders of the run-end plugins (and of `exec` and `ffmpeg`, to run programs and ffmpeg, and `remote`, for servers and buckets) in
`-Dcopybot.devPluginPaths`: `copybot-plugin/copybot-plugin-notify-email/target;copybot-plugin/copybot-plugin-notify-webhook/target;copybot-plugin/copybot-plugin-archive/target;copybot-plugin/copybot-plugin-exec/target;copybot-plugin/copybot-plugin-ffmpeg/target;copybot-plugin/copybot-plugin-remote/target`
(add them to the run configuration yourself). The data folder is then `copybot-ui/src/dev/`, where `prefs.json` and
`logs/` are written (git-ignored).

## Why Copybot?

Importing photos looks easy until a run stops halfway: the card is pulled
out, the share disconnects, the laptop goes to sleep. You are then left with
a truncated file you cannot see, duplicates named `IMG_0001 (2).jpg`, and no
idea where to start again. Copybot is built around a few rules:

- **Never lose a file.** Nothing is written under its final name until it is
  complete. Nothing is overwritten unless the pipeline asks for it. Nothing
  is deleted before its copy has been checked.
- **Never skip silently.** A file with no date is an error, not a file left
  out. If the resume state cannot be read, the run stops with an error
  instead of starting the whole card over. An unknown value in a pipeline is
  rejected; Copybot never quietly uses a default instead.
- **Show before doing.** The plan, the target paths and the resume point are
  all shown before anything is written, and you can change all of them.
- **Light.** There is no database and no service. A pipeline is a JSON file
  you can read, and its state is another JSON file next to it.

## Project layout

| Module | Contents |
|---|---|
| [`copybot-engine`](copybot-engine) | The engine, the CLI, the plugin API (`com.copybot.plugin.api.*`) and the built-in `file.read` / `file.write` actions |
| [`copybot-ui`](copybot-ui) | The JavaFX desktop application and its packaging |
| [`copybot-plugin`](copybot-plugin) | Plugins shipped with the project: `metadata-extractor`, the optional `gps-zone`, and the end-of-run actions `notify-email`, `notify-webhook` and `archive`, `exec` (any program), `ffmpeg` (video, HEIC and thumbnails, over `exec`), and `remote` (SFTP, FTP / FTPS and S3 sources and targets) |
| [`copybot-plugin-demo`](copybot-plugin-demo) | Demo plugins exercising module isolation, versions and dependencies |
| [`copybot-dependencies`](copybot-dependencies) | Shared dependency versions |

The design specifications and implementation plans are in
[`docs/superpowers`](docs/superpowers).

## Status

Copybot is under active development. The pipeline format and the plugin API
(1.2 today) may still change. Built: the plan, auto and streaming modes, safe
writes and conflict policies, resume, companion files and archive unfolding,
secondary outputs, run-end actions (program, e-mail, webhook, ZIP with
checksums) and a secrets vault, external programs and ffmpeg (video, HEIC to
JPEG, thumbnails), and SFTP, FTP / FTPS and S3 sources and targets. Not built
yet: a Google Takeout plugin, phones over MTP, detecting a card when it is
inserted, step priorities, WebDAV and SMB (use a mounted share), and retrying
a failed transfer within a run.

## License

[GNU General Public License v3.0](LICENSE).
