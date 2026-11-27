[CmdletBinding()]
param(
    [switch]$NoArchive
)

$ErrorActionPreference = 'Stop'

$websiteRoot = [IO.Path]::GetFullPath($PSScriptRoot)
$repoRoot = [IO.Path]::GetFullPath((Join-Path $websiteRoot '..'))
$distRoot = [IO.Path]::GetFullPath((Join-Path $websiteRoot 'dist'))
$firelineDistRoot = [IO.Path]::GetFullPath((Join-Path $websiteRoot 'fireline-dist'))
$archivePath = [IO.Path]::GetFullPath((Join-Path $websiteRoot 'opcode-arena-deploy.zip'))
$firelineArchivePath = [IO.Path]::GetFullPath((Join-Path $websiteRoot 'opcode-fireline-deploy.zip'))
$sourceHtml = Join-Path $websiteRoot 'mockups\terminal-80.html'
$sourceFirelineHtml = Join-Path $websiteRoot 'mockups\fireline.html'
$staticRoot = Join-Path $websiteRoot 'static'
$firelineStaticRoot = Join-Path $websiteRoot 'fireline-static'

if (-not $distRoot.StartsWith($websiteRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to build outside website directory: $distRoot"
}
if (-not $firelineDistRoot.StartsWith($websiteRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase)) {
    throw "Refusing to build outside website directory: $firelineDistRoot"
}

if (-not (Test-Path -LiteralPath $sourceHtml)) {
    throw "Missing selected design source: $sourceHtml"
}
if (-not (Test-Path -LiteralPath $sourceFirelineHtml)) {
    throw "Missing FIRELINE design source: $sourceFirelineHtml"
}
if (-not (Test-Path -LiteralPath $firelineStaticRoot)) {
    throw "Missing FIRELINE static source: $firelineStaticRoot"
}

foreach ($outputRoot in @($distRoot, $firelineDistRoot)) {
    if (Test-Path -LiteralPath $outputRoot) {
        Remove-Item -LiteralPath $outputRoot -Recurse -Force
    }
    New-Item -ItemType Directory -Path $outputRoot | Out-Null
    New-Item -ItemType Directory -Path (Join-Path $outputRoot 'assets') | Out-Null
}

$html = Get-Content -Raw -LiteralPath $sourceHtml
$productionHead = @'
  <title>Opcode Arena — Program Your Robot. Run the Fight.</title>
  <meta name="theme-color" content="#000400">
  <meta name="robots" content="index,follow,max-image-preview:large">
  <link rel="canonical" href="https://opcode-arena.appsystems.at/">
  <link rel="icon" href="/assets/icon-512.png" type="image/png">
  <link rel="manifest" href="/site.webmanifest">
  <meta property="og:type" content="website">
  <meta property="og:site_name" content="Opcode Arena">
  <meta property="og:title" content="Opcode Arena — Program Your Robot. Run the Fight.">
  <meta property="og:description" content="Write RF-8 firmware for an 8-bit robot and watch your code fight in deterministic arenas.">
  <meta property="og:url" content="https://opcode-arena.appsystems.at/">
  <meta property="og:image" content="https://opcode-arena.appsystems.at/assets/social-preview.png">
  <meta property="og:image:width" content="1024">
  <meta property="og:image:height" content="500">
  <meta name="twitter:card" content="summary_large_image">
  <script src="/site.js" defer></script>
  <script type="application/ld+json">
  {
    "@context": "https://schema.org",
    "@graph": [
      {
        "@type": "SoftwareApplication",
        "name": "Opcode Arena",
        "operatingSystem": "Android",
        "applicationCategory": "GameApplication",
        "url": "https://play.google.com/store/apps/details?id=robofight.android"
      },
      {
        "@type": "SoftwareApplication",
        "name": "Opcode Arena: The Circuit",
        "operatingSystem": "Android",
        "applicationCategory": "GameApplication",
        "url": "https://play.google.com/store/apps/details?id=robofight.android.pro"
      }
    ]
  }
  </script>
'@

$html = $html.Replace('  <title>Opcode Arena — Terminal 80</title>', $productionHead.TrimEnd())
$html = [regex]::Replace($html, '\s*<a class="switcher" href="index\.html">\[BACK TO 3 DIRECTIONS\]</a>', '')
$html = [regex]::Replace($html, 'id="panel-(about|system|codes|editions|circuit)"', 'id="$1"')
$html = $html.Replace(' tabindex="-1" hidden', ' tabindex="-1"')
$html = $html.Replace('<body>', "<body>`r`n  <!-- Production build generated from website/mockups/terminal-80.html -->")
Set-Content -LiteralPath (Join-Path $distRoot 'index.html') -Value $html -Encoding utf8NoBOM

$firelineHtml = Get-Content -Raw -LiteralPath $sourceFirelineHtml
Set-Content -LiteralPath (Join-Path $firelineDistRoot 'index.html') -Value $firelineHtml -Encoding utf8NoBOM

Copy-Item -LiteralPath (Join-Path $repoRoot 'privacy') -Destination (Join-Path $distRoot 'privacy') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'privacy') -Destination (Join-Path $firelineDistRoot 'privacy') -Recurse
Copy-Item -LiteralPath (Join-Path $repoRoot 'assets\store\release\free\icon-512.png') -Destination (Join-Path $distRoot 'assets\icon-512.png')
Copy-Item -LiteralPath (Join-Path $repoRoot 'assets\store\release\free\feature-graphic-1024x500.png') -Destination (Join-Path $distRoot 'assets\social-preview.png')
Copy-Item -LiteralPath (Join-Path $repoRoot 'assets\store\fireline\helmet-rover-master.png') -Destination (Join-Path $firelineDistRoot 'assets\fireline-helmet-rover.png')
Get-ChildItem -LiteralPath $staticRoot -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $distRoot $_.Name) -Recurse -Force
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $firelineDistRoot $_.Name) -Recurse -Force
}
Get-ChildItem -LiteralPath $firelineStaticRoot -Force | ForEach-Object {
    Copy-Item -LiteralPath $_.FullName -Destination (Join-Path $firelineDistRoot $_.Name) -Recurse -Force
}

if (-not $NoArchive) {
    foreach ($archive in @($archivePath, $firelineArchivePath)) {
        if (Test-Path -LiteralPath $archive) {
            Remove-Item -LiteralPath $archive -Force
        }
    }
    Compress-Archive -Path (Join-Path $distRoot '*') -DestinationPath $archivePath -CompressionLevel Optimal
    Compress-Archive -Path (Join-Path $firelineDistRoot '*') -DestinationPath $firelineArchivePath -CompressionLevel Optimal
}

$arenaRequired = @(
    'index.html',
    'privacy\index.html',
    '404.html',
    'robots.txt',
    'sitemap.xml',
    'site.webmanifest',
    'site.js',
    'site-event.htm',
    'App_Data\site-event-lib.php',
    'web.config',
    'assets\icon-512.png',
    'assets\social-preview.png'
)
$firelineRequired = @(
    'index.html',
    'privacy\index.html',
    '404.html',
    'robots.txt',
    'sitemap.xml',
    'site.webmanifest',
    'site.js',
    'site-event.htm',
    'App_Data\site-event-lib.php',
    'web.config',
    'assets\fireline-helmet-rover.png'
)
foreach ($relativePath in $arenaRequired) {
    if (-not (Test-Path -LiteralPath (Join-Path $distRoot $relativePath))) {
        throw "Arena build output is missing: $relativePath"
    }
}
foreach ($relativePath in $firelineRequired) {
    if (-not (Test-Path -LiteralPath (Join-Path $firelineDistRoot $relativePath))) {
        throw "FIRELINE build output is missing: $relativePath"
    }
}

Write-Host "Opcode Arena build complete: $distRoot"
Write-Host "Opcode FIRELINE build complete: $firelineDistRoot"
if (-not $NoArchive) {
    foreach ($archive in @($archivePath, $firelineArchivePath)) {
        $hash = Get-FileHash -LiteralPath $archive -Algorithm SHA256
        Write-Host "Deployment archive: $archive"
        Write-Host "SHA256: $($hash.Hash)"
    }
}
