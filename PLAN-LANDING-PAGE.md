# Opcode Arena website plan

Status: production marketing v1 prepared; challenge-sharing phases proposed

## 1. Purpose

The first release is a focused marketing and information site for **Opcode
Arena** and **Opcode Arena: The Circuit**. Its main jobs are to explain the game
quickly, show its distinctive terminal aesthetic, and send interested visitors
to the manual or the Android store listings.

The same site should later become the home of a player-created challenge-code
library. The initial release therefore stays simple at the product surface but
uses a server-capable structure, versioned API boundary, database migrations,
and stable URLs from the beginning.

### Primary launch goal

A visitor should understand this sentence within a few seconds:

> Write firmware for an 8-bit robot, then watch your code fight.

The primary call to action is **Read the manual**. Both Android editions are
already available, so their Google Play actions are equally prominent.

### Product principles carried onto the web

- Programming, not reflexes.
- The text grid is the visual identity; do not replace it with generic sci-fi
  imagery.
- Phosphor green is the shared system/game color. Amber signals The Circuit,
  its content, and its story.
- Claims stay concrete: deterministic fights, RF-8 assembly, five free arenas,
  challenge codes, and offline/private Android apps.
- The site may tease the story, but it must not explain the whole saga before
  the game does.

## 2. Launch page

Use one narrative page, not a collection of interchangeable feature cards.

### Header

- Opcode Arena wordmark or icon.
- Compact links: `How it works`, `The Circuit`, `Manual`, `Privacy`.
- Persistent high-contrast **Read the manual** action.

### Hero: the code enters the arena

Suggested copy:

> **YOUR CODE. IN THE ARENA.**
>
> Write firmware in a tiny 8-bit assembly language. Load it into a robot.
> Watch every instruction become movement, aim, heat, and fire.

Actions:

1. **Read the manual** — primary.
2. **Get Opcode Arena** — the free Google Play listing.
3. **Get The Circuit** — the paid Google Play listing.

Canonical launch links:

- Manual: `https://github.com/Clemens-U/robofight/blob/main/MANUAL.md`
- Opcode Arena: `https://play.google.com/store/apps/details?id=robofight.android&pcampaignid=web_share`
- Opcode Arena: The Circuit: `https://play.google.com/store/apps/details?id=robofight.android.pro&pcampaignid=web_share`

The visual should combine a short real RF-8 program with a real 20×20 arena
frame. A restrained animation may advance the current-instruction marker and
one projectile, but the content must remain legible with motion disabled.

### How it works: sense, decide, act

Show the actual loop instead of abstract marketing language:

1. **Sense** — read ports such as `DIST`, `ANGLE`, `HEAT`, and `AHEAD`.
2. **Decide** — branch, count, call routines, and manage heat.
3. **Act** — move, turn, shield, and shoot. Ordinary operations cost one tick,
   `MOVE` costs two, and `SHOOT`/`SHIELD` cost three.

Pair this with a compact, authentic RF-8 excerpt and an arena screenshot. Link
directly to the corresponding manual sections.

### The fight is reproducible

Explain the differentiator:

- Fights are deterministic.
- An `RF1-...` challenge code contains the fight setup and pinned firmware.
- Another phone can replay the same challenge without an account or server.
- A win can be sent back as the next challenge in the chain.

This section prepares visitors for the future web challenge library without
promising that the library exists at launch.

### Two ways into the arena

Present the editions together while keeping their identities distinct:

- **Opcode Arena** — five hand-built training arenas; free; green identity.
- **Opcode Arena: The Circuit** — the complete game plus Arena Pack 1,
  including seed-generated arenas; amber tier signal.

Use the existing release icons, feature graphics, and selected direct app
screenshots from `assets/store/release/free/` and
`assets/store/release/circuit/`. Do not mix assets between editions.

### The Circuit story signal

Keep this short and suggestive:

> Five arenas train you. The Circuit is where training begins for real.
>
> Beneath the floors are maintenance levels, locked doors, and a signal that
> was never meant for the arena.

The first sentence is established product copy. The second is an editorial
teaser for the planned escape story and should be treated as mood, not a claim
that those chapters are already playable. Avoid naming unreleased mechanics in
the launch feature list.

### Closing action

End with a terminal-style prompt:

```text
RF:\> LOAD MANUAL
SYSTEM READY. YOUR MOVE.
```

Repeat **Read the manual**, followed by the store buttons and a smaller
**View source on GitHub** link.

### Footer

- Manual
- Privacy policy
- GitHub repository
- Support email
- Edition/store links
- Copyright and license note

## 3. Stable URL structure

Reserve the future routes now even if some are not linked publicly:

| Route | Launch | Later |
| --- | --- | --- |
| `/` | Marketing page | Marketing page |
| `/manual/` | Optional redirect to the canonical GitHub manual | Rendered manual if desired later |
| `/privacy/` | Existing policy | Update when web sharing changes data practices |
| `/challenges/` | Optional “coming later” response or unlisted | Search/browse library |
| `/challenges/{slug}` | Not active | Shareable challenge detail |
| `/submit/` | Not active | Challenge submission flow |
| `/api/v1/*` | Health/version only or absent | Versioned challenge API |

At launch, manual actions use the canonical GitHub `MANUAL.md` URL. If the
manual is later rendered at `/manual/`, it must be generated from the repository
file so there is still one source of truth and no second hand-edited copy.

## 4. Recommended technical shape

### Static-first, backend-agnostic

Build the launch website as static HTML, CSS, and small vanilla JavaScript
modules with no required frontend framework or Node build step. Add server-side
code only when the challenge-sharing feature needs it.

Recommended shape:

- Static marketing page and terminal navigation.
- A later versioned `/api/v1` implemented in a server technology selected for
  the deployment environment. A small PHP application is explicitly acceptable;
  Kotlin/Ktor or another service remains an option.
- The existing MySQL server, reached only from server-side code. The browser
  never connects directly to MySQL.
- Schema migrations committed to the repository from the first deployment.
- Object storage only if screenshots or player uploads are later accepted;
  challenge codes themselves belong in SQL.

The database may remain empty at launch. What matters is that environment
configuration, migration ownership, API routing, error format, and deployment
boundaries are already defined.

### MySQL connection contract

- Keep `MYSQL_HOST`, `MYSQL_PORT`, `MYSQL_DATABASE`, `MYSQL_USER`, and
  `MYSQL_PASSWORD` in deployment secrets, never in source or client-side
  environment variables.
- Require TLS for connections that cross a host or network boundary.
- Use a narrowly privileged application user. It may read and write the
  website schema but must not administer the server or access unrelated
  databases.
- Use PDO prepared statements for PHP, or a small connection pool for a
  persistent Kotlin/Node service. Cap concurrency so traffic bursts cannot
  exhaust the existing server's connection limit.
- Run migrations through a separate deployment identity or controlled release
  job; the request-serving identity should not need `ALTER`/`DROP` privileges.
- Record and test the minimum supported MySQL version before choosing generated
  columns, JSON indexes, or full-text search features.
- Store timestamps in UTC and use `utf8mb4` throughout.

### Suggested repository layout

```text
website/
  public/              production HTML, CSS, JavaScript, and static assets
  api/                 optional future PHP or other server implementation
  db/
    migrations/         append-only SQL migrations
  tests/
```

Keep website dependencies separate from the Android/engine Gradle build.

### Challenge codes are opaque by default

Basic sharing does not require the website to understand an RF1 code. It may
store a size-limited submitted string, calculate a hash, assign a public slug,
and let the Android apps perform authoritative validation when the code is
opened.

The Kotlin engine and `ChallengeFormat` implementation remain the authority for
what an RF1 code means. If the website later needs arena/rule/result metadata,
compatibility warnings, canonicalization, or previews, it calls a dedicated
challenge-inspection API. Do not reimplement the full parser inside PHP or the
browser merely to support sharing. The website must not claim to simulate a
fight unless cross-runtime determinism is proven.

## 5. Challenge-library data model

Create the schema only when the first server feature is implemented, but agree
on the shape now so launch architecture does not block it.

### `challenges`

| Field | Purpose |
| --- | --- |
| `id` | Internal immutable identifier (UUID/ULID) |
| `slug` | Short public identifier; never derived from a sequential id |
| `submitted_code` | Opaque, size-limited value exactly as accepted for sharing |
| `canonical_code` | Nullable canonical value returned by the inspection API |
| `code_hash` | Hash for exact/normalized deduplication |
| `title` | Optional player-facing title, length-limited |
| `description` | Optional plain-text description, length-limited |
| `parse_status` | `not_requested`, `valid`, `invalid`, or `error` |
| `arena_id`, `mode`, `spawn`, `seed` | Nullable metadata returned by the inspection API |
| `engine_version`, `format_version` | Nullable compatibility metadata |
| `author_id` | Nullable until accounts exist |
| `status` | `pending`, `published`, `hidden`, or `rejected` |
| `created_at`, `updated_at` | Audit timestamps |

### Supporting tables

- `authors` — only when identity is introduced; do not require accounts merely
  to launch the marketing site.
- `challenge_reports` — abuse/spam reports with rate limits and state.
- `moderation_events` — append-only administrative audit trail.
- `challenge_tags` and `challenge_tag_map` — add only after real discovery
  needs appear; do not start with free-form tags.
- `favorites` — add only with accounts. Anonymous browser favorites stay in
  local storage.

Do not store derived popularity counters as the sole record. Either calculate
them from events or maintain them as rebuildable aggregates.

## 6. Future API boundary

Start with JSON and explicit versioning:

```text
GET  /api/v1/challenges?arena=&mode=&cursor=
GET  /api/v1/challenges/{slug}
POST /api/v1/challenges
POST /api/v1/challenges/{slug}/reports
POST /api/v1/challenge-code/inspect
```

`POST /api/v1/challenge-code/inspect` is the optional parsing boundary. Its
implementation may live in a separate service that reuses the Kotlin
`ChallengeFormat` code. The website or PHP sharing backend calls it
server-to-server only when interpreted metadata is required. A representative
response contains:

```json
{
  "valid": true,
  "canonicalCode": "RF1-...",
  "formatVersion": 1,
  "engineVersion": 1,
  "arenaId": 0,
  "mode": "KO",
  "spawn": "SEPARATE",
  "seed": 0,
  "warning": null
}
```

The inspection API validates and describes a code. It never runs supplied
firmware or simulates a fight on the request path.

Submission processing:

1. Enforce body and field-size limits before storing anything.
2. Apply only bounded transport checks required for sharing, such as trimming
   surrounding whitespace and requiring an `RF1-` prefix. Preserve the
   submitted value; do not pretend this is full validation.
3. Hash and deduplicate the accepted opaque value.
4. When interpreted metadata is needed, call the inspection API and store its
   canonical value, status, and returned metadata. Treat timeouts as `error`,
   not as proof that the code is invalid.
5. Apply rate limits and bot protection.
6. Store new submissions as `pending` until the moderation model is proven.
7. Return the public slug only after publication.

All database access stays server-side. The browser never receives database
credentials or a general-purpose database client key.

## 7. Trust, safety, and privacy

The future sharing platform changes the current privacy posture. The Android
apps can remain offline and account-free, but the website will process network
requests and public submissions.

Before challenge submission opens:

- Publish website-specific privacy text covering IP/security logs, challenge
  content, retention, deletion, and any account data.
- Add terms for public user-submitted content and a takedown/report process.
- Keep titles/descriptions plain text; render no player HTML or Markdown at
  first.
- Rate-limit writes, cap code length to the RF1 specification, and use a
  pending/moderation state.
- Do not publish embedded source code automatically until its display and abuse
  implications have been reviewed. A challenge code may carry more than arena
  metadata.
- Use only the selected first-party, cookie-free site-event counters at launch.
  Store aggregate hourly totals for page views, panel views, and important
  outgoing actions; do not store visitor identifiers or full referrer URLs.

The current Android privacy claim (“developer data collection: none”) must not
be reused as a claim about the later sharing website.

## 8. Accessibility, performance, and discovery

- Use semantic headings, links, and buttons; the terminal look must not depend
  on `div` elements or ASCII alone.
- Meet WCAG AA contrast for green/amber text and controls.
- Honor `prefers-reduced-motion`; no essential information may exist only in
  an animation.
- Provide useful alt text using the existing release asset descriptions as the
  starting point.
- Keep the hero's critical text in HTML, not baked into an image.
- Avoid loading every phone screenshot above the fold. Optimize responsive
  images and target a small first-page payload.
- Add title, description, canonical URL, Open Graph image, favicon/app icons,
  sitemap, and `robots.txt`.
- Add structured data for both Android software applications using their
  confirmed Google Play URLs; confirm the paid price at deployment.

## 9. Delivery phases

### Phase 0 — decisions and content lock

- Choose domain and hosting/database provider.
- Google Play URLs confirmed; both editions are live.
- Approve the launch copy and the exact Circuit teaser.
- Select 3–5 existing release assets for the page.
- Use the confirmed GitHub manual URL at launch; a rendered `/manual/` remains
  an optional later enhancement.

### Phase 1 — marketing launch

- Build the single narrative page and privacy route; link the manual to its
  canonical GitHub URL.
- Establish the server-capable project, environment configuration, versioned
  API namespace, and migration command even if the database has no product
  records.
- Add responsive/accessibility checks and visual regression snapshots for
  desktop and mobile.
- Add a minimal deployment check: homepage, both Google Play links, the manual
  link, privacy, and an unknown route.
- Publish without accounts or challenge submission. Include the documented
  first-party aggregate site-event counters.

### Phase 2 — read-only challenge gallery

- Add the schema and a curated/admin-seeded challenge set.
- Implement list, filter, and detail routes.
- Treat codes as opaque values for basic listing and copying.
- If metadata-driven filters or previews are included, add the dedicated
  inspection API and validate it against Kotlin-generated RF1 reference vectors.
- Keep the gallery read-only while moderation and privacy wording are tested.

### Phase 3 — player submissions

- Add submission, deduplication, rate limiting, moderation, reporting, and
  deletion/takedown workflows.
- Start with accountless submissions only if the moderation burden is
  acceptable; otherwise introduce a simple verified identity at this point.
- Preserve permanent challenge URLs and redirect merged duplicates.

### Phase 4 — community features

- Accounts, authors, favorites, collections, and curated events only after
  usage demonstrates a need.
- Treat comments, rankings, and arbitrary tags as separate moderation-heavy
  products, not automatic additions to challenge sharing.

## 10. Launch acceptance criteria

- A first-time visitor can describe the game after the hero and first content
  section.
- The manual is reachable from the header, hero, and closing action.
- Edition differences are accurate and visually unambiguous.
- The Circuit teaser is clearly a story signal, not a shipped-feature claim.
- All claims match the current manual/store documentation.
- Mobile layout works at 320 CSS pixels without horizontal scrolling.
- Keyboard navigation, focus indicators, reduced motion, and screen-reader
  landmarks are verified.
- Homepage and all primary outbound links remain useful with JavaScript disabled.
- The deployment has a documented database/migration path and versioned API
  boundary, even though launch functionality is read-only.
- No challenge-sharing UI appears active before its privacy, moderation, and
  validation paths are ready.

## 11. Decisions still needed

1. Public domain and preferred host.
2. Whether launch is English-only or English/German. English-only is the
   recommended first release; add German when the marketing copy and manual can
   be maintained together.
3. The approved one- or two-line Circuit teaser.
4. Whether the site presents the project as an individual developer project or
   under an appsystems brand.

