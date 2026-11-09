<?php

declare(strict_types=1);

function siteEventDefaultConfig(): array
{
    $product = siteEventCleanToken($payload['product'] ?? '', 64, 'opcode_arena');
    if (!in_array($product, ['opcode_arena', 'opcode_fireline'], true)) {
        $product = 'opcode_arena';
    }

    return [
        'db' => [
            'host' => getenv('SITE_EVENT_DB_HOST') ?: (getenv('DOWNLOAD_COUNTER_DB_HOST') ?: '127.0.0.1'),
            'port' => getenv('SITE_EVENT_DB_PORT') ?: (getenv('DOWNLOAD_COUNTER_DB_PORT') ?: '3306'),
            'name' => getenv('SITE_EVENT_DB_NAME') ?: (getenv('DOWNLOAD_COUNTER_DB_NAME') ?: ''),
            'user' => getenv('SITE_EVENT_DB_USER') ?: (getenv('DOWNLOAD_COUNTER_DB_USER') ?: ''),
            'password' => getenv('SITE_EVENT_DB_PASSWORD') ?: (getenv('DOWNLOAD_COUNTER_DB_PASSWORD') ?: ''),
            'site_event_counts_table' => getenv('SITE_EVENT_COUNTS_DB_TABLE') ?: 'site_event_counts',
        ],
    ];
}

function siteEventMergeConfig(array $base, array $override): array
{
    foreach ($override as $key => $value) {
        if (isset($base[$key]) && is_array($base[$key]) && is_array($value)) {
            $base[$key] = siteEventMergeConfig($base[$key], $value);
            continue;
        }

        $base[$key] = $value;
    }

    return $base;
}

function siteEventConfig(): array
{
    static $config = null;

    if ($config !== null) {
        return $config;
    }

    $config = siteEventDefaultConfig();
    $configPaths = [
        dirname(__DIR__, 2) . DIRECTORY_SEPARATOR . 'download-counter-config.local.php',
        dirname(__DIR__, 2) . DIRECTORY_SEPARATOR . 'site-event-config.local.php',
    ];

    foreach ($configPaths as $localConfigPath) {
        if (!is_file($localConfigPath)) {
            continue;
        }

        $localConfig = require $localConfigPath;
        if (is_array($localConfig)) {
            $config = siteEventMergeConfig($config, $localConfig);
        }
        break;
    }

    if (empty($config['db']['site_event_counts_table'])) {
        $config['db']['site_event_counts_table'] = 'site_event_counts';
    }

    return $config;
}

function siteEventEscapeIdentifier(string $identifier): string
{
    if (!preg_match('/^[a-zA-Z0-9_.]+$/', $identifier)) {
        throw new RuntimeException('Invalid site event table identifier.');
    }

    $parts = explode('.', $identifier);
    return implode('.', array_map(static function (string $part): string {
        return '`' . $part . '`';
    }, $parts));
}

function siteEventCreatePdo(): PDO
{
    $db = siteEventConfig()['db'];

    if ($db['name'] === '' || $db['user'] === '') {
        throw new RuntimeException('Site event database credentials are not configured.');
    }

    $dsn = sprintf(
        'mysql:host=%s;port=%s;dbname=%s;charset=utf8mb4',
        $db['host'],
        $db['port'],
        $db['name']
    );

    return new PDO(
        $dsn,
        $db['user'],
        $db['password'],
        [
            PDO::ATTR_ERRMODE => PDO::ERRMODE_EXCEPTION,
            PDO::ATTR_DEFAULT_FETCH_MODE => PDO::FETCH_ASSOC,
            PDO::ATTR_EMULATE_PREPARES => false,
        ]
    );
}

function siteEventReadPayload(): array
{
    $contentType = isset($_SERVER['CONTENT_TYPE']) ? strtolower((string) $_SERVER['CONTENT_TYPE']) : '';
    if (strpos($contentType, 'application/json') !== 0) {
        return [];
    }

    $rawBody = file_get_contents('php://input');
    if ($rawBody === false || strlen($rawBody) > 4096) {
        return [];
    }

    $payload = json_decode($rawBody, true);
    return is_array($payload) ? $payload : [];
}

function siteEventCleanToken($value, int $maxLength, string $fallback): string
{
    $value = is_string($value) ? strtolower(trim($value)) : '';
    if ($value === '' || !preg_match('/^[a-z0-9_-]+$/', $value)) {
        return $fallback;
    }

    return substr($value, 0, $maxLength);
}

function siteEventCleanPath($value): string
{
    $path = is_string($value) ? parse_url($value, PHP_URL_PATH) : null;
    if (!is_string($path) || $path === '') {
        return '/';
    }

    if ($path[0] !== '/') {
        $path = '/' . $path;
    }

    $path = preg_replace('/[^a-zA-Z0-9\/._~-]/', '', $path);
    if (!is_string($path) || $path === '') {
        return '/';
    }

    return substr($path, 0, 255);
}

function siteEventNormalizePayload(array $payload): ?array
{
    $allowedEvents = [
        'page_view',
        'panel_view',
        'manual_clicked',
        'free_app_clicked',
        'circuit_clicked',
        'source_clicked',
        'external_link_clicked',
    ];

    $eventName = siteEventCleanToken($payload['event'] ?? '', 64, '');
    if (!in_array($eventName, $allowedEvents, true)) {
        return null;
    }

    $referrer = siteEventCleanToken($payload['referrer'] ?? '', 32, 'unknown');
    $allowedReferrers = ['direct', 'internal', 'google', 'bing', 'duckduckgo', 'other', 'unknown'];
    if (!in_array($referrer, $allowedReferrers, true)) {
        $referrer = 'other';
    }

    $locale = siteEventCleanToken($payload['locale'] ?? '', 8, 'unknown');
    if (!in_array($locale, ['en', 'de', 'unknown'], true)) {
        $locale = 'unknown';
    }

    return [
        'event_name' => $eventName,
        'page_path' => siteEventCleanPath($payload['path'] ?? '/'),
        'product' => $product,
        'locale' => $locale,
        'referrer_category' => $referrer,
        'traffic_type' => siteEventCleanToken($payload['traffic_type'] ?? '', 16, 'external') === 'internal' ? 'internal' : 'external',
    ];
}

function siteEventRequestOriginAllowed(): bool
{
    $origin = isset($_SERVER['HTTP_ORIGIN']) ? (string) $_SERVER['HTTP_ORIGIN'] : '';
    if ($origin === '') {
        return true;
    }

    $host = parse_url($origin, PHP_URL_HOST);
    return is_string($host) && in_array(
        strtolower($host),
        ['opcode-arena.appsystems.at', 'opcode-fireline.appsystems.at'],
        true
    );
}

function siteEventIncrement(PDO $pdo, array $event): void
{
    $table = siteEventConfig()['db']['site_event_counts_table'];
    $sql = sprintf(
        'INSERT INTO %1$s ' .
        '(event_date_utc, event_hour_utc, event_name, page_path, product, locale, referrer_category, traffic_type, event_count, created_at, updated_at) ' .
        'VALUES (UTC_DATE(), HOUR(UTC_TIMESTAMP()), :event_name, :page_path, :product, :locale, :referrer_category, :traffic_type, 1, UTC_TIMESTAMP(), UTC_TIMESTAMP()) ' .
        'ON DUPLICATE KEY UPDATE event_count = event_count + 1, updated_at = UTC_TIMESTAMP()',
        siteEventEscapeIdentifier($table)
    );

    $statement = $pdo->prepare($sql);
    $statement->execute($event);
}

function siteEventHandleRequest(): void
{
    header('Cache-Control: no-store');

    if (($_SERVER['REQUEST_METHOD'] ?? '') !== 'POST') {
        http_response_code(405);
        header('Allow: POST');
        return;
    }

    if (!siteEventRequestOriginAllowed()) {
        http_response_code(204);
        return;
    }

    $event = siteEventNormalizePayload(siteEventReadPayload());
    if ($event === null) {
        http_response_code(204);
        return;
    }

    try {
        siteEventIncrement(siteEventCreatePdo(), $event);
    } catch (Throwable $exception) {
        error_log('Opcode Arena site event increment failed: ' . $exception->getMessage());
    }

    http_response_code(204);
}
