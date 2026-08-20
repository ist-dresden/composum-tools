# FontAwesome → Bootstrap Icons Mapping

Referenztabelle für die Migration von FontAwesome 4 (`fa fa-*`) auf Bootstrap Icons 1.13.1
(`bi bi-*`, siehe `/lib/bootstrap/icons/1.13.1/`) in diesem Modul.

Bootstrap Icons hat kein identisches Icon-Set — viele Zuordnungen sind sinngemäße
Äquivalente, keine 1:1-Treffer. Vor jeder neuen Zuordnung lohnt sich ein Blick in
`bootstrap-icons.json` im selben Verzeichnis, ob ein passenderer Name existiert.

## Verwendete Zuordnungen (aus `script.js`, `BrowserTree.treeOptions.types`)

| FontAwesome | Bootstrap Icons | Hinweis |
|---|---|---|
| `fa-bars` | `bi-list` | Hamburger-Menü-Icon; auch von Bootstrap selbst im Navbar-Toggler verwendet |
| `fa-book` | `bi-book` | |
| `fa-bookmark-o` | `bi-bookmark` | |
| `fa-code` | `bi-code` | |
| `fa-cog` | `bi-gear` | |
| `fa-cogs` | `bi-gear-wide-connected` | mehrere Zahnräder |
| `fa-cube` | `bi-box` | kein Würfel-Icon in BI |
| `fa-cubes` | `bi-boxes` | |
| `fa-database` | `bi-database` | |
| `fa-diamond` | `bi-diamond` | |
| `fa-ellipsis-h` | `bi-three-dots` | |
| `fa-ellipsis-v` | `bi-three-dots-vertical` | |
| `fa-file-archive-o` | `bi-file-earmark-zip` | |
| `fa-file-code-o` | `bi-file-earmark-code` | |
| `fa-file-image-o` | `bi-file-earmark-image` | |
| `fa-file-o` | `bi-file-earmark` | |
| `fa-file-pdf-o` | `bi-file-earmark-pdf` | |
| `fa-file-text-o` | `bi-file-earmark-text` | |
| `fa-file-video-o` | `bi-file-earmark-play` | |
| `fa-filter` | `bi-funnel` | |
| `fa-folder` (solid) | `bi-folder-fill` | gefüllte Variante |
| `fa-folder-o` (outline) | `bi-folder` | Umriss-Variante |
| `fa-globe` | `bi-globe` | |
| `fa-group` | `bi-people` | |
| `fa-hand-o-right` | `bi-hand-thumb-up` | kein exaktes Pendant (kein zeigender Finger nach rechts in BI) |
| `fa-history` | `bi-clock-history` | |
| `fa-key` | `bi-key` | |
| `fa-laptop` | `bi-laptop` | |
| `fa-link` | `bi-link-45deg` | |
| `fa-picture-o` | `bi-image` | |
| `fa-puzzle-piece` | `bi-puzzle` | |
| `fa-share` | `bi-share` | |
| `fa-share-square-o` | `bi-folder-symlink` | hier für "Client-Library-Ordner" gewählt; für generisches Teilen eher `bi-box-arrow-up-right` |
| `fa-signal` | `bi-activity` | |
| `fa-sitemap` | `bi-diagram-3` | |
| `fa-tag` | `bi-tag` | |
| `fa-tags` | `bi-tags` | |
| `fa-university` | `bi-bank` | |
| `fa-user` | `bi-person` | |

## Weitere Zuordnungen (aus dem Spinner-Thema)

| FontAwesome | Bootstrap Icons | Hinweis |
|---|---|---|
| `fa-refresh` | `bi-arrow-clockwise` | einzelner Kreispfeil |
| `fa-repeat` / `fa-rotate-right` | `bi-arrow-clockwise` | |
| `fa-undo` / `fa-rotate-left` | `bi-arrow-counterclockwise` | |
| `fa-retweet` | `bi-arrow-repeat` | zwei gegenläufige Pfeile (Sync/Loop) |
| `fa-spinner` + `fa-pulse`/`fa-spin` | *kein Icon-Äquivalent* | Bootstrap Icons hat keine Rotations-Utility-Klasse. Stattdessen Bootstraps eigene Komponente `spinner-border` / `spinner-grow` verwenden (siehe `option/query/query.html` + `option/query/style.css` in diesem Modul) — Geschwindigkeit über CSS-Variable `--bs-spinner-animation-speed` steuerbar. Alternative: `bi-arrow-clockwise` + eigene `@keyframes`-Rotation. |

## Vorgehen bei neuen Zuordnungen

1. Icon-Name in `bootstrap-icons.json` suchen (Verzeichnis `com/composum/lib/bootstrap/icons/1.13.1/`).
2. Falls kein passendes Icon existiert: semantisch nächstliegendes wählen und hier dokumentieren.
3. Für Animationen (Spin, Pulse, Fade) möglichst Bootstraps eigene Komponenten/Utilities nutzen
   (`spinner-border`, `spinner-grow`, `fade`, ...) statt Icon-Font-Klassen zu ersetzen — Bootstrap 5
   ist in diesem Modul ohnehin bereits vollständig eingebunden (`/lib/bootstrap/5.2.3/`).
4. Klassen-Präfix ist `bi bi-<name>` (Basisklasse `bi` + Glyphen-Klasse `bi-<name>`), keine
   `-o`/`-fill`-Endung wie bei FontAwesome — Outline ist bei BI meist der Default, `-fill` das Suffix
   für die gefüllte Variante (z. B. `bi-folder` vs. `bi-folder-fill`).
