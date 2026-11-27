# Opcode Arena and Opcode FIRELINE website deployment

The two products use separate domains and separate document roots. Each is a
static frontend with one small PHP endpoint for first-party, cookie-free site
statistics. Neither site has an account system, third-party analytics or visitor
identifiers. Site events are written as aggregate hourly counters in the
existing MySQL database and separated by their `product` value.

## Build

From the repository root on Windows PowerShell:

```powershell
.\website\build.ps1
```

The build recreates:

- `website/dist/` — upload-ready document root;
- `website/opcode-arena-deploy.zip` — Arena deployment archive;
- `website/fireline-dist/` — independent FIRELINE document root;
- `website/opcode-fireline-deploy.zip` — FIRELINE deployment archive.

The selected `website/mockups/terminal-80.html` file is the Arena design source;
`website/mockups/fireline.html` is the FIRELINE sibling-page source. The build
adds production metadata and the tracking script, removes the Arena mockup
switcher, copies the PHP event endpoint and shared bilingual privacy policy into
both roots, applies domain-specific manifests, robots files, sitemaps and 404
pages, and packages each deployment independently.

## IIS deployment

1. Create DNS for `opcode-arena.appsystems.at` and
   `opcode-fireline.appsystems.at`.
2. Configure HTTPS certificates for both subdomains.
3. Upload the **contents** of `website/dist/` to the Arena document root and
   the **contents** of `website/fireline-dist/` to the FIRELINE document root.
   Each root includes its own `web.config`.
4. Install or confirm the Microsoft IIS URL Rewrite module. The conditional
   HTTP-to-HTTPS rule in `web.config` depends on it.
5. Confirm PHP FastCGI is available at
   `C:\Program Files (x86)\PHP\v8.5.12\php-cgi.exe`. Because server rewrite
   rules block public `*.php` URLs, the site declares an exact handler for
   `site-event.htm`. No other `.htm` or `.html` file is passed through PHP.
6. Confirm the shared `download-counter-config.local.php` file one directory
   above the website contains the existing MySQL `db` configuration, or set
   the `SITE_EVENT_DB_HOST`, `SITE_EVENT_DB_PORT`, `SITE_EVENT_DB_NAME`,
   `SITE_EVENT_DB_USER`, and `SITE_EVENT_DB_PASSWORD` environment variables.
7. Ensure the `site_event_counts` table exists. The idempotent schema is in
   `website/database/site-event-counts.sql`; the existing JSONBuddy table can
   be shared because `product` is part of each aggregate key.
8. On both domains, verify `/`, `/privacy/`, `/404.html`, `/robots.txt`,
   `/sitemap.xml`, and a POST to `/site-event.htm`. A GET to the event endpoint
   must return `405` from PHP and must never expose its source.

The `web.config` file sets the default document, disables directory listings,
registers the 404 page, maps only `site-event.htm` to PHP, maps the web-manifest
MIME type, redirects HTTP to HTTPS, and adds conservative security headers. If
URL Rewrite is unavailable,
remove only the `<rewrite>` section and configure the HTTPS redirect at the IIS
site, reverse proxy, or load balancer instead.

The FIRELINE source configuration is
`website/fireline-static/web.config`; the build copies it to
`website/fireline-dist/web.config`. Its FastCGI `scriptProcessor` currently
targets `C:\Program Files (x86)\PHP\v8.5.12\php-cgi.exe`, matching the Arena
environment. Change that path only if the FIRELINE IIS application uses a
different registered PHP runtime.

## SVN deployment route

### Opcode Arena

- Repository: `http://138.201.224.137:5512/svn/appsystems/web/opcode-arena`
- IIS working copy: `C:\inetpub\wwwroot\web\opcode-arena`

The initial production release was committed at SVN revision `45069`; the IIS
configuration correction is revision `45070`, and first-party site statistics
were added in revision `45072`. The redundant site-level PHP handler was removed
in revision `45073` in favor of the inherited server mapping. The IIS working
copy was then switched to the rewrite-compatible `.htm` collector with an exact
PHP 8.5.12 handler in revision `45074`. The IIS working copy is a clean checkout
of that directory. A normal server refresh is:

```powershell
svn update C:\inetpub\wwwroot\web\opcode-arena
```

For a new production build, copy the contents of `website/dist/` into the SVN
working copy, use `svn add --force` for new files and `svn delete` for removed
files, review `svn status`, and commit. Do not copy the deployment ZIP or the
working copy's `.svn` metadata into the document root.

### Opcode FIRELINE

- Repository: `http://138.201.224.137:5512/svn/appsystems/web/opcode-fireline`
- IIS working copy: `C:\inetpub\wwwroot\web\opcode-fireline`
- Initial production site: SVN revision `45081` (9 November 2026)
- Terminal-panel navigation update: SVN revision `45082` (9 November 2026)
- FIRELINE thermal mission-map update: SVN revision `45083` (9 November 2026)

Deploy only `website/fireline-dist/` to this working copy. Do not mix files from
the two output roots. A normal server refresh is:

```powershell
svn update C:\inetpub\wwwroot\web\opcode-fireline
```

## Release checks

- All terminal navigation panels work with JavaScript enabled.
- With JavaScript disabled, all content remains visible and anchor navigation
  remains usable.
- The manual and both Google Play links open successfully.
- The layout works at 320 CSS pixels without horizontal scrolling.
- The privacy page retains its current effective date and content.
- The canonical URL is `https://opcode-arena.appsystems.at/`.
- The FIRELINE canonical URL is `https://opcode-fireline.appsystems.at/`.
- A page view increments a row with `product = opcode_arena`; manual and Google
  Play clicks use their specific event names.
- A FIRELINE page view increments a row with `product = opcode_fireline`.
- Each domain exposes the shared policy at its own `/privacy/` URL.
- Normal visitors receive no cookies or local-storage identifier. To exclude a
  maintainer browser, visit once with `?opcode_arena_internal=1`; remove the
  marker with `?opcode_arena_internal=0`.

## Future sharing platform

Do not place MySQL credentials in this deployment directory. The statistics
endpoint reads environment variables or the existing shared configuration
outside the document root. A later PHP or other server-side API belongs outside
the public document root except for its explicit front controller. See
`PLAN-LANDING-PAGE.md` for the opaque-code sharing and optional
challenge-inspection API design.
