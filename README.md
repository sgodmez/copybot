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
  `_files` folder or a Takeout JSON to their main file, which then travels,
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
  CLI, Ctrl+C cancels and leaves 5 s for writes in progress to finish.
- **Actions after the copy.** A pipeline can end with an ordered list of
  actions (`runEnd`): launch a program such as synoRight, send an e-mail or a
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
| `extract` | `com.copybot.plugin.metadataextractor` | analyze | Capture date from EXIF, falling back to QuickTime then MP4 (through [metadata-extractor](https://github.com/drewnoakes/metadata-extractor)) |
| `write-date` | `com.copybot.plugin.metadataextractor` | process | Goes just before the write. Rewrites the EXIF date of the JPEGs whose time zone was corrected in the plan to the local date and offset, without recompressing the image (through [Commons Imaging](https://commons.apache.org/proper/commons-imaging/)); every other file passes untouched |
| `check` | `com.copybot.plugin.gpszone` (optional) | analyze | Goes after `extract`. Flags a photo whose camera was badly set: from its GPS position, the time zone of the place (offline boundaries of [Timezone Boundary Builder](https://github.com/evansiroky/timezone-boundary-builder) through [timeshape](https://github.com/RomanIakovlev/timeshape)) gives another offset than the recorded one, the GPS time confirming the capture date. Nothing near a time zone boundary, at sea, or for a video. The plan suggests the zone; you apply it |

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

A Google Takeout plugin is planned (photos and their JSON metadata); it is no longer a preset of the core.

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
matching warning. `file.read` signals its folders.

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

## After the copy (run-end actions)

A pipeline can end with an ordered list of actions, `runEnd`, set in the
editor under *After the copy*. Each entry names an action and when it runs:

```json
"runEnd": [
  { "action": "post.process", "when": ["success", "error"],
    "actionConfig": { "command": "D:\\progs\\autre\\synoRight\\synoRightGui.exe",
                      "args": ["-c", "D:\\progs\\autre\\synoRight", "rights", "--new-folders", "{createdDirs}"] } },
  { "plugin": "archive", "action": "archive.zip", "timeoutSeconds": 0,
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
warnings of the actions: they are shown as their references. A password typed
in clear in a field marked as secret gets a warning and an offer to move it to
the vault.

### notify-email: `email.send`

```json
{ "plugin": "notify-email", "action": "email.send", "when": ["success", "error"],
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
{ "plugin": "notify-webhook", "action": "webhook.send",
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
{ "plugin": "archive", "action": "archive.zip",
  "actionConfig": { "target": "D:\\Archives\\{pipeline}\\{date}-{time}.zip",
                    "content": "zip", "includeSkipped": false, "baseFolder": "D:\\Photos" } }
```

Writes an archive of the files the run copied, with the checksums computed
during the copy (recomputed for a skipped file). `target` replaces `{date}`
(`yyyy-MM-dd`), `{time}` (`HHmmss`) and `{pipeline}`; a relative path is
relative to the pipeline's folder, as is `baseFolder`, the folder the entry
paths are relative to (absent: the common directory of the files; a file
outside it is left out). `content` is `zip` (default: the files and a
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

To write an action, you extend `AbstractActionWithConfig<C>`, where `C` is a
record holding the action's configuration. You implement `IInAction`,
`IAnalyzeAction`, `IProcessAction` or `IOutAction`. Annotations on the
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

The plugin API is what the engine module, `com.copybot.engine`, exports to
every module (`com.copybot.plugin.api.*`, `com.copybot.utils`,
`com.copybot.resources`, `com.copybot.exception`). It has a version of its
own, `X.Y`, distinct from the version of Copybot: the property
`copybot.plugin.api.version` of the root `pom.xml`, which the build makes the
module version of `com.copybot.engine` (`jar --describe-module` shows
`com.copybot.engine@1.0`). A plugin declares the API it was built for without
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

builds the modules and runs the test suite (about 1,170 tests across the
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
`pom.xml` (`1.0.0`, never `-SNAPSHOT`): change the application version
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
major version of the API: give the plugin a new version). The `engine` of an
entry is the plugin API version (`X.Y`) its archive was built for, which the
plugins window compares with the API of the running Copybot (never with the
version of the application). The description
of a plugin, and its licence notice (`gps-zone`: ODbL), come from the
`catalog.json` of its module.

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
first. A dev run of the UI also needs the folders of the run-end plugins in
`-Dcopybot.devPluginPaths`: `copybot-plugin/copybot-plugin-notify-email/target;copybot-plugin/copybot-plugin-notify-webhook/target;copybot-plugin/copybot-plugin-archive/target`
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
| [`copybot-plugin`](copybot-plugin) | Plugins shipped with the project: `metadata-extractor`, the optional `gps-zone`, and the end-of-run actions `notify-email`, `notify-webhook` and `archive` |
| [`copybot-plugin-demo`](copybot-plugin-demo) | Demo plugins exercising module isolation, versions and dependencies |
| [`copybot-dependencies`](copybot-dependencies) | Shared dependency versions |

The design specifications and implementation plans are in
[`docs/superpowers`](docs/superpowers).

## Status

Copybot is under active development. The pipeline
format and the plugin API may still change. These are not built yet:
detecting a card when it is inserted, sidecar files, transcoding, and step
priorities.

## License

[GNU General Public License v3.0](LICENSE).
