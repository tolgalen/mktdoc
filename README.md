# mktdoc

Android app (10 and later) to **browse the MikroTik RouterOS manual offline**, in any of its published
versions. It is the mobile counterpart of the desktop tool `mktdoc-downloader`: it reads
<https://manual.mikrotik.com>, keeps a local HTML copy of the version you choose, tells you what changed
online, and can export the documentation to a ZIP or a PDF.

<p align="center">
  <img src="docs/screenshots/home.png" width="230" alt="Versions list">
  <img src="docs/screenshots/version.png" width="230" alt="Version screen">
  <img src="docs/screenshots/reader_page.png" width="230" alt="Offline reader">
</p>

## Contents

- [Features](#features)
- [Screenshots](#screenshots)
- [How it works (user view)](#how-it-works-user-view)
- [Architecture](#architecture)
  - [Overview](#overview) · [Packages](#packages) · [Data on disk](#data-on-disk)
  - [Discovery](#discovery-of-versions-and-structure) · [Download](#download-pipeline) · [Offline site](#offline-html-site)
  - [Checking for changes](#checking-for-changes-and-updating) · [Retired versions](#retired-versions)
  - [Changelog](#the-documentation-changelog) · [ZIP export](#zip-export) · [PDF export](#pdf-export)
  - [Hardware guides](#the-hardware-guides) · [Background work](#background-work-and-alerts) · [App updates](#app-updates)
  - [Long operations](#long-operations-and-notifications) · [UI, state and theming](#ui-state-and-theming)
- [Build and test](#build-and-test)
- [Design notes and limitations](#design-notes-and-limitations)
- [License](#license)

## Features

- **Versions**: detects every published version (7.26, 7.25, 7.24…) and lets you pick one. The last known list
  is kept, so the app also starts without a connection.
- **Partial and resumable downloads**: "Download sections…" lets you pick only the top-level sections you need
  (a *Partial copy*; later downloads add to it). An interrupted download keeps the pages already fetched
  (checkpoint every 50 pages) and resumes where it stopped.
- **Wi-Fi only** option and a storage screen (space used per version, free space, delete). Downloads refuse to
  start with less than 400 MB free.
- **Offline HTML copy**: pages, images and CSS are downloaded and turned into a navigable site with an index,
  **full-text search**, previous/next navigation and light/dark mode.
- **Native search** across every downloaded version (words match by prefix, title matches rank first), plus
  **bookmarks** and a "continue reading" shortcut on the home screen. A bookmark icon in the
  top bar (with the number of bookmarks) opens a bottom sheet with all of them, grouped by version, newest first,
  with a text filter and removal with undo.
- **In-app reader** (WebView) that follows the app theme and has a **font size** selector (80 – 175 %).
- **Check differences with the website**: reads the online version again and compares it with your copy
  (new, modified and removed pages, with the changed lines). Nothing is applied until you confirm.
- **Retired versions**: if a version you downloaded is no longer published, the app warns you (banner and
  notification, with an optional daily background check) and keeps your copy.
- **Documentation changelog** (<https://manual.mikrotik.com/changelog>) as one more downloadable item.
- **Hardware guides** (<https://manual.mikrotik.com/hardware/>: ports, buttons, mounting, powering and specifications
  of every MikroTik product, ~250 pages) as another downloadable item, with the same reader, search, bookmarks,
  section download, difference check, ZIP and PDF export.
- **ZIP export** of the offline copy (a self-contained site, `index.html` at the root).
- **PDF export** (from your offline copy, without network, or from the website) with the same options as the desktop app: sections and subsections,
  one file or one per section, cover title and image, image quality, text size and page breaks. The PDF has a
  cover, a contents page with page numbers (each line is a link), bookmarks, a footer with the section and page
  number, and links between manual pages that jump inside the PDF, to the section named in the link when it is known.
- **Compare two versions** you have downloaded (e.g. 7.25 → 7.26): new, modified and removed pages, with the
  changed lines. Partial copies are compared over the sections both hold.
- **Reading tools**: *find in page* (with match count), the scroll position of every page is remembered, a *Copy*
  button on every code block, *Open on the website* and *Share link* for the current page, and the words you
  searched for are highlighted when you open a result. The hardware category pages have a product filter.
- **Page translation**: translate the open page from the reader menu into any of the ~58 languages of ML Kit. By
  default it runs **on the device** (ML Kit Translation): the language model, about 30 MB per language, is
  downloaded once from Google's servers and after that no text leaves the phone. Code blocks, inline code,
  commands and paths are never translated. Optionally, an **online server compatible with the LibreTranslate API**
  (https only, your own or a trusted one) can be configured and allowed in the settings; the text of the pages you
  translate with it is sent to that server. The language stays active while you navigate, *Original* brings the
  English text back, and finished translations are cached.
- **Alerts** (all optional): a RouterOS version that was not published before, a newer release of the app on
  GitHub, and a weekly background check (Wi-Fi only) that tells you when a downloaded copy differs from the website
  (nothing is applied automatically).
- **Bookmarks backup**: export them to a JSON file and import them (merging, without duplicates) on another phone.
- **App updates from GitHub** (About): shows the installed version, checks the repository's releases, lists the
  version history with each release's notes, and downloads and installs a newer release. The APK is verified
  (SHA-256 published with the release and the same signing certificate as the installed app) before the system
  installer is opened.
- **Themes** like the rest of my apps: System, Dark, Dark blue, Black (OLED) and Light, plus 8 color palettes
  (mktdoc, Material Design, Ocean, Forest, Violet, Amber, Rose, Slate) and Material You dynamic colors.
- Interface in **English, Spanish, French, German and Portuguese** (per-app language), settings grouped by
  category, open-source license screen. The generated offline site, the PDF texts and the progress messages
  exist in English and Spanish; with another language they use English.
- **Accessibility**: radio buttons, check boxes and switches are single, labelled elements for TalkBack (touch
  targets of at least 48 dp), section titles are headings, palette swatches and the bookmark star have
  descriptions, and the result of an operation is announced.
- **Tablets and large screens**: content is a centered column (max. 720 dp); the reader shows the table of
  contents of the copy as a side panel (screens of 840 dp or more) that highlights and reveals the current page.
- Polite to the server: honors `robots.txt`, caps requests per second (adjustable) and retries 429/5xx.

## Screenshots

### Versions and downloading

| Versions | Version screen | Downloading |
|---|---|---|
| <img src="docs/screenshots/home.png" width="230"> | <img src="docs/screenshots/version.png" width="230"> | <img src="docs/screenshots/download_progress.png" width="230"> |

The list shows the online versions, the documentation changelog and the hardware guides; each card says whether an offline copy
exists. The version screen is a grid of actions. Long operations run in a foreground service and show a
progress dialog (it can be hidden or cancelled).

### Reading offline

| Index and search | Page | Options menu |
|---|---|---|
| <img src="docs/screenshots/reader_search.png" width="230"> | <img src="docs/screenshots/reader_page.png" width="230"> | <img src="docs/screenshots/reader_menu.png" width="230"> |

| Opened from a search (words highlighted) | Find in page | Hardware category |
|---|---|---|
| <img src="docs/screenshots/reader_highlight.png" width="230"> | <img src="docs/screenshots/reader_find.png" width="230"> | <img src="docs/screenshots/hardware_category.png" width="230"> |

The options menu has *Index*, *Open on the website*, *Share link* and the font size. A hardware category opens a
page listing all its products, with a filter.

### Search, bookmarks and comparing versions

| Search | Bookmarks | Compare two versions |
|---|---|---|
| <img src="docs/screenshots/search_results.png" width="230"> | <img src="docs/screenshots/bookmarks_sheet.png" width="230"> | <img src="docs/screenshots/compare_versions.png" width="230"> |

### Checking for changes

| Result | Changes report |
|---|---|
| <img src="docs/screenshots/check_result.png" width="230"> | <img src="docs/screenshots/changes.png" width="230"> |

The report lists new, modified and removed pages. A modified page expands to the lines that changed
(red = removed, green = added). You can apply the changes (optionally deleting pages that no longer exist
online) or discard them.

> The "Changes report" screenshot was taken with deliberately altered local data (one page edited, one removed
> and one deleted from the copy) so that the report would show all three kinds of change.

### Documentation changelog

| Changelog | Entries grouped by month |
|---|---|
| <img src="docs/screenshots/changelog_version.png" width="230"> | <img src="docs/screenshots/changelog_index.png" width="230"> |

### PDF export

| Options | Sections | Done |
|---|---|---|
| <img src="docs/screenshots/pdf_options.png" width="230"> | <img src="docs/screenshots/pdf_sections.png" width="230"> | <img src="docs/screenshots/pdf_done.png" width="230"> |

Cover, contents with page numbers, and content pages with the section name and page number in the footer
(generated by the app on the emulator, "Container" section of 7.26):

![PDF pages](docs/screenshots/pdf_pages.png)

### Settings, themes and licenses

| Settings | Appearance | Reading and bookmarks backup |
|---|---|---|
| <img src="docs/screenshots/settings.png" width="230"> | <img src="docs/screenshots/settings_appearance.png" width="230"> | <img src="docs/screenshots/settings_reading.png" width="230"> |

| Alerts | About: updates and version history |
|---|---|
| <img src="docs/screenshots/settings_alerts.png" width="230"> | <img src="docs/screenshots/settings_about.png" width="230"> |

| Licenses | mktdoc license text |
|---|---|
| <img src="docs/screenshots/licenses.png" width="230"> | <img src="docs/screenshots/license_text.png" width="230"> |

Dark, Dark blue and Light (with the Ocean palette):

![Themes](docs/screenshots/themes.png)

## How it works (user view)

1. **Open the app.** It lists the versions published on the website. Tap the refresh icon to reload the list.
   Without a connection it shows the last known list.
2. **Pick a version** and press **Download as HTML**. The app reads the manual structure, downloads every page
   with its images and builds the offline site. A foreground notification shows the progress.
3. **Open documentation** to read it in the app. The **index** page has a search box (full text, all words must
   match) and the whole table of contents. Inside a page you have breadcrumbs and previous/next links. The top bar
   has a bookmark star, *find in page* and the global search; the ⋮ menu has the index, *Open on the website*,
   *Share link*, *Translate page* and the font size. The search icon on the home screen searches all your downloaded versions.
4. **Check differences with the website** to compare your copy with what is online. Review the report, then
   *Apply changes* or *Discard*. If the version has disappeared from the website you are told, and the copy is
   kept.
5. **Export copy to ZIP** to save the offline site as a file anywhere (the Android file picker is used).
6. **Export to PDF**: choose the source (offline copy or website), the options and the sections, then pick where to save the file (or a
   folder when you want one file per section).
7. **Settings** (gear icon) are grouped in *Appearance*, *Reading* (page translation, font size and bookmarks backup), *Network and
   storage*, *Alerts*, *About* (updates from GitHub and version history) and *Licenses*.

## Architecture

### Overview

The app is a single Android module written in Kotlin with Jetpack Compose (Material 3). There is no database:
everything is stored as plain files, and long operations are coroutines hosted by a foreground service.

```mermaid
flowchart LR
    UI["ui/<br/>Compose screens + MainViewModel"] --> LIB
    UI --> JOBS["work/Jobs<br/>foreground service"]
    JOBS --> LIB["data/Library<br/>download · check · apply · zip"]
    JOBS --> PDF["pdf/PdfExporter"]
    LIB --> DISC["data/Discovery<br/>versions + structure"]
    LIB --> CRAWL["data/Crawler<br/>download + clean + store"]
    LIB --> SITE["data/SiteRenderer<br/>offline HTML site"]
    PDF --> CRAWL
    PDF --> IMG["pdf/ImageOptimizer"]
    PDF --> PRINT["pdf/PdfPrinter<br/>WebView → PDF"]
    PDF --> MERGE["PDFBox-Android<br/>merge, bookmarks, footer"]
    DISC --> HTTP["net/Http<br/>OkHttp + rate limit"]
    CRAWL --> HTTP
    HTTP -.-> WEB[("manual.mikrotik.com")]
    CRAWL --> STORE[("Store<br/>files on disk")]
    SITE --> STORE
    WORK["work/VersionCheckWorker (daily)<br/>work/ContentCheckWorker (weekly)"] --> LIB
    UI --> UPD["data/Updates<br/>GitHub releases"]
    UPD --> HTTP
    UI --> SEARCH["data/Search<br/>search-index.js"]
```

### Packages

| Package | Responsibility |
|---|---|
| `net` | `Http`: single OkHttp client with a global requests-per-second limiter, retries (429/5xx and network errors with growing back-off) and manual redirect handling (the site redirects to `http://`, which is forced back to `https://`). |
| `data` | `Discovery` (versions, sidebar menu, sitemap, changelog structure), `Crawler` (download, clean, cache), `Store` (files on disk), `SiteRenderer` (offline HTML site), `Library` (download / check / apply / compare / ZIP / availability / outline), `Model` (nodes, structure, versions, collections), `Search` (native full-text search over the generated index), `Updates` (GitHub releases, verified download, installer intent), `Translate` (ML Kit and online engines, translation cache, the JavaScript that swaps the text nodes), `Msg` (language of the progress messages), `Prefs` (settings, bookmarks, scroll positions), `Licenses`. |
| `pdf` | `PdfOptions`, `ImageOptimizer`, `PdfPrinter` (prints HTML with the WebView), `PdfExporter` (assembles, prints, merges and decorates the PDF). |
| `work` | `Jobs` (single long-running operation exposed as a `StateFlow` + the foreground `JobService`) `VersionCheckWorker` (daily) and `ContentCheckWorker` (weekly) with WorkManager. |
| `ui` | Compose screens (`Screens`, `DownloadScreen`, `PdfScreen`, `ReaderScreen` + `OutlinePanel` + `TranslateDialog`, `SettingsScreen`), shared components, `MainViewModel`, and `theme/` (color schemes, shapes). |
| `android.print` | `PrintCallbacks.java`: tiny Java shim, see [PDF export](#pdf-export). |

### Data on disk

Each downloaded version lives in its own folder under the app's private storage (`filesDir/docs/<label>/`).
The changelog uses the label `changelog` and the hardware guides `hardware` (see `isDocSet`).

```
docs/<label>/
├── meta.json         label, prefix, download/check times, page count, size, "removed online" flag, pending changes, partial flag
├── .partial          marker while a download is unfinished (resume uses it; removed on success)
├── structure.json    the manual tree (sections, categories, pages) with their paths
├── manifest.json     per page: key, title, SHA-1 of its visible text, ETag, Last-Modified, timestamp, file name
├── raw/              cleaned <article> HTML of every page (images as @ASSETS@/img/… placeholders)
├── site/             the generated offline site (what the reader and the ZIP use)
│   ├── index.html    table of contents + full-text search
│   ├── <pages>.html  one file per page, same folder layout as the website
│   └── assets/       site.css, img/, res/ (fonts, icons), search-index.js
└── staging/          only while a check has unapplied changes: new/modified pages + report.json
```

The PDF export keeps its own page cache in `filesDir/pdfcache/<label>/` (same layout without `site/`), and the
structure used by the PDF section picker is cached in `cacheDir/structures/`.

### Discovery of versions and structure

`Discovery` reads the website's Docusaurus markup:

- **Versions**: `/docs/introduction` contains the version dropdown (`/docs/7.25/introduction`…) and the
  `docusaurus_version` meta tag with the current one. The current version lives at `/docs`, the others at
  `/docs/<version>`. The result is cached in `filesDir/versions.json`.
- **Structure**: the sidebar `ul.theme-doc-sidebar-menu` is parsed into a tree of `Node`s. The sidebar only
  comes expanded for the active branch, so each *category* page is read to learn its children
  (`expandMenus`, first level when choosing sections; the crawler discovers deeper levels while downloading).
- **Pages not in the menu**: the current version's `sitemap.xml` (or `llms.txt` as a fallback) lists them and
  they are added to the tree. Older versions have no sitemap, so the current version's paths are translated
  to the old prefix and probed (HTTP 200) to see which ones exist.
- Links found inside pages that are in neither the menu nor the sitemap are attached under their nearest known
  ancestor (`attachDiscovered`) and dropped if they turn out not to be pages.

### Download pipeline

`Library.download` → `Discovery.loadStructure` → `Crawler.run` → `SiteRenderer.render`.

`Crawler` downloads the pages concurrently (6 coroutines, bounded by a semaphore) while the global limiter
keeps the whole app under the configured requests per second (default 10, adjustable 2 – 20). For each page:

1. `robots.txt` is honored (`Disallow` of the `*` group; `Crawl-delay` lowers the rate).
2. If a previous copy exists, a conditional request is made (`If-None-Match` / `If-Modified-Since`); a `304`
   reuses the stored page. When the server sends no validators the page is downloaded again and compared by
   content.
3. The `<article>` (or `<main>`) is extracted and **cleaned**: scripts, buttons, pagination and permalinks are
   removed, tab panels are expanded with their labels, collapsed `<details>` are opened, images are downloaded
   to `assets/img/` (file name = SHA-1 of the URL, so they are shared and cached) and replaced by
   `@ASSETS@/` placeholders, and internal links are tagged with `data-mk-key` / `data-mk-frag`.
4. A **hash** of the normalized visible text (one line per block) is stored; it is what later tells whether a
   page changed.
5. Category pages also yield their menu, which is merged into the tree (official order and titles); new nodes
   are queued, so the whole manual is found while downloading.

Network errors that survive the HTTP client's own retries are retried twice more per page; 404/410 mark a page
as gone, other failures are reported and, if a previous copy existed, it is kept.

The site stylesheet is downloaded once, and the `url(…)` references in it (fonts, icons) are rewritten to local
files.

### Offline HTML site

Besides the pages, every page gets a small script (`PAGE_JS`): copy buttons on the code blocks, highlighting of the
words in `?hl=`, and the scroll position, which the reader's WebView receives through a tiny `@JavascriptInterface`
(`MkBridge`; only local pages are loaded in it, other links open in the browser). Each page also carries
`<meta name="mk-url">` with its address on the website, used by *Open on the website* and *Share link*.

`SiteRenderer` turns `raw/` into `site/`:

- Files mirror the website path (`/docs/wireless/wifi` → `wireless/wifi.html`, or `…/index.html` when the page
  has children). Placeholders become relative `assets/` paths and links tagged with `data-mk-key` are
  rewritten to relative links to the local file (links to anything else stay as web links).
- Each page gets a top bar (index, search box, theme toggle), breadcrumbs, previous/next links and the
  Docusaurus stylesheet. The theme script runs in `<head>` before the first paint: forced theme (the app sets
  it from the reader), saved choice, or the system preference.
- `index.html` has the nested table of contents and the **search**: an inverted index
  (`assets/search-index.js`, base-36 posting lists) loaded with a plain `<script>`, so it also works from
  `file://`. All query words must match (the last one as a prefix); pages whose title matches rank first.
- A version that was published at `/docs` and later moved to `/docs/<version>` keeps working: the renderer
  accepts every prefix the version has been known under.

The reader is a `WebView` pointed at `file://…/site/index.html`. The app theme is injected (`window.MK_FORCE`
and `data-theme`), external links open in the browser and the font size is `WebSettings.textZoom`.

### Checking for changes and updating

`Library.check(label)` (not applying anything):

1. If the version now lives under a different prefix (e.g. it was `/docs` and a newer release moved it to
   `/docs/7.26`), the stored data is **migrated** to the new prefix first.
2. The stored structure is loaded; categories are marked as "not expanded" so their menus are read again, and
   new pages from the sitemap are added (current version only).
3. `Crawler` runs against the stored copy but **writes into `staging/`**. It reports `added` (not in the
   manifest), `modified` (hash differs), `gone` (404/410) and `failed`.
4. For every modified page a line diff is computed between the stored and the new text. The result is saved
   as `staging/report.json` and shown in the *Changes* screen.

`Library.apply` moves the staged pages into `raw/`, merges the manifest (optionally removing the gone pages and
their nodes), garbage-collects unreferenced files, re-renders the site and updates `meta.json`.
`discardPending` just deletes `staging/`.

### Retired versions

A version counts as retired when the website does not list it any more **and** its introduction page is gone (or
its old `/docs` prefix now serves another version); the double check avoids false alarms. `Library.refreshAvailability` runs when the app opens or the list is
refreshed, and `VersionCheckWorker` (WorkManager, once a day, only with network) runs the same check in the
background and posts a notification. The flag is stored in `meta.json`, so the banner, the card chip and the
version screen keep showing it. The offline copy is never deleted automatically, and *Check differences* on it just reports that the version is no
longer online. The changelog and the hardware guides are excluded from this check.

### The hardware guides

`https://manual.mikrotik.com/hardware/` is a second Docusaurus docs plugin of the same site (own sidebar with product
categories, ~250 flat pages such as `/hardware/cap-ax`; `robots.txt` allows it). It is a special version with
label `hardware` and prefix `/hardware` that is treated as a *current* version, so it reuses the normal
pipeline unchanged: structure from the sidebar of `/hardware/` (`entryUrl`) plus the sitemap pages missing from
the menu, category menus read while downloading, hash-based difference check, and the optional section download
(each product category is a section). Links to the RouterOS docs stay web links.

Two details of that site shape the structure: each sidebar category links to its first product's page *and* lists
that same product inside it, so the repeated entry keeps no key of its own (it would be a duplicate page) but its
`href` still names a downloaded page, and the offline index and the reader's outline list it as a normal entry
(`SiteRenderer.pageKeyOf`). Because opening such a category would otherwise show only that first product, a page that
lists all the products of the category (like the website's sidebar) is generated for each of them in `site/_cat/`
(`SiteRenderer.categoryPages`); the offline index, the reader's outline and a link at the top of every product page
point to it. The few `/hardware/tags` pages are part of the manual and are included (for the RouterOS
docs the tag pages are skipped). When the renderer changes, a stamp file (`site/.render-version`) makes the app rebuild
existing copies once at start, without downloading anything.

### The documentation changelog

`https://manual.mikrotik.com/changelog` is a Docusaurus *blog* (one post per working day, plus archive, tags
and authors pages), not a versioned manual. It is modeled as a special version with label `changelog` and
prefix `/changelog`, so it reuses the whole pipeline (download, offline site and search, check, apply, ZIP and
PDF):

- `Discovery.loadChangelogStructure` reads the sitemap, groups the posts by month (newest first) and appends
  the archive, tags and authors pages. Post titles are replaced by the page's `<h1>` after downloading.
- `refreshChangelog` adds new posts to the stored tree when checking for differences.
- Pagination pages (`/changelog/page/N`) and the blog at `/blog` are not downloaded.

### ZIP export

`Library.exportZip` streams every file of `site/` into a `ZipOutputStream` writing to a URI chosen with the
Storage Access Framework (`CreateDocument`). Entries live under `mktdoc-<label>/`, so unzipping it gives a
folder that opens by double-clicking `index.html`.

### PDF export

`PdfExporter` can print from the **offline copy** (default when there is one: no network, no download) or from the
**website** (it works without an offline copy):

1. **Pages**: from the offline copy's own `Store`, or, for the website source, the chosen sections are downloaded
   with the same `Crawler` into the PDF cache (`filesDir/pdfcache/<label>`) and reused for 7 days without a
   request. Optimized images always go to the PDF cache, never inside the offline copy.
2. **Images**: `ImageOptimizer` rescales and re-encodes them according to the quality option (*Optimized*: max.
   1400 px, JPEG 82; *Smallest*: 1000 px, JPEG 65; *Original*: untouched). PNG/WebP become JPEG, transparency
   is flattened onto white, SVG is untouched.
3. **Layout**: for every output file ("group") the pages are put in order and split into **blocks** (at most
   80 pages, and a block starts at every sheet break of the chosen *page breaks* mode). Each block is one HTML
   file using the site's CSS plus a print stylesheet (tighter spacing, code wrapped, no shadows, images capped
   in height, `zoom` for the *text size* option, `break-before: page` for the breaks).
4. **Printing**: `PdfPrinter` loads each block in an off-screen `WebView` and prints it with the framework's
   `PrintDocumentAdapter` **without showing a print dialog**. The adapter callbacks have non-public
   constructors, so a small shim in the `android.print` package (`PrintCallbacks.java`) subclasses them.
   A4 is requested with no margins; the page margins come from CSS `@page`.
5. **Locating pages**: every manual page has a tiny, almost white, zero-width marker (`MKP<index>X`) placed
   *inside its first heading*, so it moves to the next sheet together with the heading. After a block
   is printed, PDFBox's text stripper finds on which sheet each marker is, giving the sheet where every manual
   page starts.
6. **Contents**: printed twice. The first pass has placeholder numbers to learn how many sheets the contents
   need, the second one has the real numbers (cover + contents sheets + position of the page).
7. **Merge and finish** (PDFBox-Android): cover, contents and blocks are merged; every sheet gets a footer with
   its section and page number; the printed `<a>` link annotations that point to another exported page are
   rewritten from web (URI) links to jumps to that page's first sheet (links to anything else stay on the web);
   a hierarchical outline (bookmarks) is added; the file is written to the
   destination chosen with the Storage Access Framework (one document, or one per section in a chosen folder).
   If anything fails, the empty file created by the picker is deleted.

Options (same as the desktop app): layout (single file / one per section), cover title and image, image quality,
text size (extra small 70.7 %, small 85 %, normal), page breaks (continuous, new sheet per section, per section
and subsection, each page) and the sections or subsections to include.

### Background work and alerts

Two WorkManager workers are scheduled from the settings. `VersionCheckWorker` (daily, any network) fetches the
online versions, records the ones it has not seen before (`Prefs.registerOnlineVersions`; the first run only records,
so installing the app does not announce every version), flags retired copies, and checks GitHub for a newer app
release (notifying once per version). `ContentCheckWorker` (weekly, unmetered network, battery not low, off by
default) runs the same difference check as the *Check differences* button on each downloaded copy within an
8-minute budget and notifies how many changes are waiting; it never applies them and skips a copy that already has
changes pending or an operation that is running.

### Long operations and notifications

`Jobs` allows one long operation at a time (download, check, apply, ZIP, PDF). `Jobs.start` launches it in an
application-scoped coroutine and starts `JobService`, a foreground service (type `dataSync`) that shows an
ongoing notification with progress and a *Cancel* action. The state (`running`, phase, `done/total`, last log
lines, message or error) is a `StateFlow`, collected by the Compose `JobDialog`. When a job ends the service
posts a result notification and stops. Operations report progress through a small `Reporter` object (`log`,
`progress(phase, done, total)`).

### UI, state and theming

- `MainViewModel` holds the version lists, the local copies and the PDF section tree, and starts the jobs.
  User-visible texts built there use a context localized with the per-app language, because the application
  context does not follow it by itself.
- Navigation is a tiny back stack of route strings (`home`, `version:7.26`, `changes:7.26`, `pdf:7.26`,
  `reader:7.26`, `search:`, `download:7.26`, `compare:7.25|7.26`, `settings`) saved with `rememberSaveable`.
- Settings are a list of categories; each opens a sub-page (back returns to the list).
- **Theming**: `ThemeMode` (System, Dark, Dark blue, Black, Light) × `ThemePalette` (mktdoc, Material Design,
  Ocean, Forest, Violet, Amber, Rose, Slate), plus Material You dynamic colors on Android 12+. Dark blue and
  Black have fixed schemes; the generated palettes derive their light and dark schemes from a seed color.
  Shapes are slightly rounded (2 – 8 dp) and the action buttons are laid out in a grid.
- The app uses `AppCompatActivity` so the per-app language works on all supported versions.

## Build and test

Requirements: the Android SDK with platform 37 and network access the first time to fetch dependencies. The Gradle
wrapper is used; the Gradle daemon JVM is configured as JDK 25 (auto-provisioned) and the code targets Java 17.

```
./gradlew assembleDebug            # app/build/outputs/apk/debug/mktdoc-v1.1.2-debug.apk
./gradlew testDebugUnitTest        # unit tests
./gradlew lintDebug                # Android lint
./gradlew connectedDebugAndroidTest  # UI/integration tests on a device or emulator (it uninstalls the app afterwards,
                                     # which deletes its downloaded data on that device)
./gradlew assembleRelease          # release APK (minified); signed when keystore.properties exists
```

Downloads need at least 400 MB of free space.

For a signed release copy `keystore.properties.example` to `keystore.properties` and fill it in (it is
git-ignored). `minSdk` is 29 (Android 10), `targetSdk` 36.

Dependencies: Jetpack Compose + Material 3, AndroidX (activity, lifecycle, appcompat, WorkManager), OkHttp,
jsoup, PDFBox-Android, ML Kit Translation and kotlinx.coroutines. The unit tests (`app/src/test`) cover version comparison, path
normalization, the local file mapping and relative links, the Docusaurus menu parser, structure
serialization, the text hash used to detect changes, the search index and the PDF link mapping. The instrumented
tests (`app/src/androidTest`) cover the reusable Compose components (action grid, accessible rows, the reader's
outline panel) and the search code against a copy written to the app's storage.

### App updates

`Updates` reads `https://api.github.com/repos/tolgalen/mktdoc/releases` (public, unauthenticated, no personal data is
sent) and caches the list, so the version history also shows offline. The newest *stable* release with an `.apk`
asset that is newer than `BuildConfig.VERSION_NAME` is offered. Downloading runs as a normal job (foreground
service, progress, Wi-Fi-only option); the file goes to `cache/updates/` and is checked before it is offered:
SHA-256 against the asset's `digest` (or the checksum written in the release notes), and its package name and
signing certificate against the installed app's, so a file signed with another key (for example a debug build) is
refused. Installing hands the file to the system installer through a `FileProvider`
(`REQUEST_INSTALL_PACKAGES`); Android asks once to allow mktdoc to install apps, and Google Play Protect may show its
own scan prompt for an app it does not know. Android 10 does not fill `signingInfo` for an archive that is not
installed, so the deprecated `signatures` field is used as a fallback.

## Design notes and limitations

- **No database.** Plain files make the offline copy trivially exportable and inspectable.
- **Partial copies are top-level only**: you choose whole top-level sections (e.g. *CLI Reference*), not
  individual pages. A download that is cancelled or fails keeps the pages already fetched (checkpoints) and the
  next attempt resumes from them; the version stays usable meanwhile.
- **Change detection is by content hash** of the page's visible text, so cosmetic differences in the markup do
  not count as changes, and neither do changed images.
- **PDF links**: a link to a section of another exported page jumps to the *sheet* where that heading starts, not to
  its exact position on the sheet. Headings without an `id` fall back to the start of the page. Links to pages
  outside the PDF stay web links.
- **Memory/time**: very large PDFs (thousands of pages) are slow on a phone and PDFBox keeps temporary files;
  choose *one file per section* for the complete manual.
- Progress messages in the job dialog, the offline site's own labels and the PDF cover/contents texts are
  available in English and Spanish only; the app interface has five languages. The translations other than
  English and Spanish were written without a native review, so corrections are welcome.
- **Translation**: it is machine translation of the *text nodes* of a page, one by one, so a sentence split by inline
  code or a link is translated in pieces and technical terms (bridge, firewall…) may be translated literally.
  The source language is always English. Translating the PDF export is not supported. The cache lives in the app's
  cache folder (the system may clear it) and can be cleared in the settings. The API key of the online server is
  stored in the app's private preferences, unencrypted.
- Release notes in the version history are shown as published on GitHub (English).
- The app is independent and **not endorsed by MikroTik**. The documentation is MikroTik's content, downloaded
  for personal use.

## License

Copyright © 2026 tolgalen

mktdoc is released under the [MIT License](LICENSE): you may use, copy, modify, merge, publish, distribute,
sublicense and sell it, as long as the copyright notice and the permission notice are kept. It is provided "as is",
without warranty of any kind.

Versions 1.0.0 to 1.1.2 were published under the GNU General Public License v3 or later; whoever received them
may keep using and redistributing those versions under that license. From version 1.2.0 on, the code is MIT.

Third-party components (also shown in the app under *Settings › Licenses*):

| Component | License |
|---|---|
| Jetpack Compose, Material 3, AndroidX, WorkManager | Apache 2.0 |
| Kotlin, kotlinx.coroutines | Apache 2.0 |
| OkHttp, Okio | Apache 2.0 |
| jsoup | MIT |
| PDFBox-Android (port of Apache PDFBox) | Apache 2.0 |
| ML Kit Translation (Google) | Proprietary: Android SDK Terms of Service and ML Kit Terms (not open source) |

The documentation and changelog downloaded by the app are copyrighted content of MikroTik (SIA Mikrotikls) and are
not part of this repository.
