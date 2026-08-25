# Composum Tools

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A lightweight, dependency-minimal framework for building a customizable set of maintenance and
development tools for an Apache Sling or AEM (incl. AEM as a Cloud Service) instance — a JCR
resource browser, a Felix Web Console proxy, and a tile-based dashboard to arrange them on.

![Browser screenshot placeholder](docs/images/browser.png)
*Placeholder — JCR browser with properties view*

## Background

Composum Tools is a from-scratch, purpose-built successor to two older, independently maintained
projects:

- the full [Composum Nodes](https://github.com/ist-dresden/composum-nodes) JCR browser,
  including many features (package manager, user management, Groovy console, ACL editor, ...)
  that have not been migrated yet, but will be added later if useful or necessary.
- the more lightweight [Composum Dashboard](https://github.com/ist-dresden/composum-dashboard),
  an earlier attempt at replacing Nodes with a tile-based framework for arranging a small set of
  read-only tools.

Both projects are being **replaced** by `tools`. The goals of the rewrite are:

- **Less maintenance** — one small codebase with a minimal installation and repository footprint.
- **Fewer dependencies** — built against a minimal Sling API surface plus Jackson (already part
  of both the AEM and the plain Sling Starter feature set); no Gson (no longer supported on AEM),
  no JCR content package for the UI itself — the whole UI is plain Java/OSGi bundle code.
- **Only what is actually used** — instead of porting every feature of Nodes/Dashboard, `tools`
  implements only the subset that is genuinely needed here: browsing/inspecting resources,
  running queries, exporting results, and a couple of read-only Felix Console views.

If a feature you relied on in Nodes or Dashboard is missing here, that is intentional — please
raise it so it can be added deliberately, rather than carried forward "just in case".

## Features

### Browser

A JCR/Sling resource browser: a tree on the left (`.browser.tree.json`), a set of pluggable
**tools** above it, and a set of pluggable **views** plus **actions** on the right for the
selected resource.

**Tools** (left-hand panel):

- **Favorites** — a configurable, regex-driven set of quick-access root paths (e.g. `/content`,
  `/conf`, `/apps`), plus a "recently visited" history.
- **Query** — runs JCR-SQL2 or XPath queries (with a configurable list of query templates),
  shows the results as a table, and offers **streaming JSON and CSV export** with configurable
  CSV columns.

**Views** (per-resource detail tabs):

- **Properties** — full property listing of the selected resource, including binary download of
  `jcr:data`.
- **Display** — renders the resource directly in an iframe; on AEM author instances this adds
  `wcmmode=disabled` and an authoring parameters panel.
- **JSON** / **XML** — a recursive dump of the resource subtree, with an optional "source mode"
  that hides repository noise (`jcr:uuid`, `jcr:created`, `cq:lastReplicated*`, ...).
- **CA Config** — shows resolved Sling Context-Aware Configuration values for the selected
  resource, restricted to a configurable set of configuration types.

**Actions**: *View* (open in a new tab) everywhere; on AEM author instances additionally *Edit*
(page editor), *Manage* (Assets/Sites console) and *Activate*/*Deactivate* (replication).

### Dashboard

A single overview page (`Page`/`Tile` widgets) listing all enabled tools as tiles, each linking
to its detail page. Every module below contributes its own widget automatically once enabled.

### Console (AEM only)

Embeds selected read-only [Felix Web Console](https://felix.apache.org/documentation/subprojects/apache-felix-web-console.html)
plugins (Requests, JCR Resolver, Servlet Resolver) inside the tools UI, rewriting their internal
resource/content links so they work without direct access to `/system/console` — useful on
AEMaaCS, where that console is not reachable.

## Getting started

### Module layout

```
tools/
├── sling/
│   ├── bundle/    the core framework + Dashboard + Browser (works on plain Sling and on AEM)
│   └── package/    a content package wrapping the sling/bundle, for package-manager based installs
└── aem/
    ├── bundle/    AEM-specific extensions (author-only Edit/Manage/Activate actions, Felix
    │              Console proxy, AEM-aware Display view and platform config) — depends on sling/bundle
    └── package/    a content package wrapping the aem/bundle
```

Every module is a plain OSGi bundle; there is no JCR content to install for the UI itself, so a
`bundle` module deployed to a running instance is enough to use the tools. The `package` modules
exist only for environments where content-package based deployment (rather than direct bundle
install) is the required workflow. The AEM bundle already embeds the Sling bundle, so for an
AEM installation only that single bundle needs to be deployed.

### Building

```bash
mvn clean install
```

Deploy directly to a running instance during development (adjust host/port via the usual
`sling.host`/`sling.port` properties if needed):

```bash
# plain Sling instance
mvn -pl sling/bundle -am -P installBundleSling install

# AEM author instance
mvn -pl aem/bundle -am -P installBundleAEM install

# AEM publish instance
mvn -pl aem/bundle -am -P installBundleAEMPublish install
```

Publishing a release to Maven Central uses the `deploy-central` profile (see `pom.xml`).

### Usage

Once the bundle(s) are active, open (default servlet path `/apps/cpm/tools`, configurable):

| URL | Page |
|---|---|
| `/apps/cpm/tools.dashboard.html` | Dashboard overview |
| `/apps/cpm/tools.browser.html` | JCR Browser |
| `/apps/cpm/tools.console.html` | Felix Console proxy (AEM only) |

## Configuration & customization

Every building block (`Dashboard`, `Browser`, `Favorites`, `Query`, each `View`, `DefaultActions`
/ `AemActions`, each `ConsoleProxy`, ...) is a separate OSGi component with its own
`@ObjectClassDefinition`, configurable per environment (author/publish/dev/...) through the usual
OSGi configuration mechanism. The most commonly adjusted settings:

- **Enable/disable** a whole module: `Browser.Config#tools()` / `Browser.Config#views()` — empty
  means "all enabled", otherwise only the listed keys are active. Same pattern (`enabled()`) on
  `Dashboard`, `ConsoleProxy` implementations, etc.
- **Access restrictions**: `Server.Config#allowedPathPatterns()` / `disabledPathPatterns()` and
  `allowedPropertyPatterns()` / `disabledPropertyPatterns()` — regex allow/deny lists applied
  repository-wide, before any view or export renders a resource or property.
- **Favorites**: `Browser.Config#favorites()` — `label=regex` pairs.
- **Query templates & CSV export columns**: `Browser.Config#queryTemplates()` and
  `Browser.Config#queryCsvProperties()` (the latter as `column[=candidate1|candidate2|...]`,
  first matching property wins).
- **Exposed CA configurations**: `Browser.Config#caConfigurations()`.
- **JSON/XML "source mode" noise filters**: `JsonView.Config#nonSourceProperties()` /
  `nonSourceMixins()` (and the equivalent on `XmlView`).

For extension beyond configuration — a new tool, view, action set or console proxy — implement
the relevant small interface (`Tool`, `View`, `Actions`, `ConsoleProxy`) as its own OSGi
component; it registers itself with the framework via `PluginSet#attach()`/`#detach()` in its
`@Activate`/`@Deactivate` methods, exactly like the built-in ones (see e.g.
[`Favorites`](sling/bundle/src/main/java/com/composum/sling/browser/tool/Favorites.java) or
[`JsonView`](sling/bundle/src/main/java/com/composum/sling/browser/view/JsonView.java) as a
template).

Note that all HTML templates and static assets (JS/CSS/icons) are plain Java classpath resources
bundled *inside* the OSGi bundle — there is deliberately no JCR content package backing the UI.
Visual/markup customization therefore means adjusting the bundle sources (e.g. in a fork or a
downstream module providing overriding components), not overlaying content under `/apps`.

## License

MIT — see [`LICENSE`](LICENSE).

## See also

- [Composum Nodes](https://github.com/ist-dresden/composum-nodes) — the full-featured JCR
  browser this project's Browser is a reduced, purpose-built alternative to.
- [Composum Dashboard](https://github.com/ist-dresden/composum-dashboard) — the tile-based
  framework this project's Dashboard is a reduced, purpose-built alternative to.
