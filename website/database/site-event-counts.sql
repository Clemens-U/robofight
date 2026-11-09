CREATE TABLE IF NOT EXISTS `site_event_counts` (
  `event_date_utc` date NOT NULL,
  `event_hour_utc` tinyint unsigned NOT NULL,
  `event_name` varchar(64) NOT NULL,
  `page_path` varchar(255) NOT NULL,
  `product` varchar(64) NOT NULL,
  `locale` varchar(8) NOT NULL,
  `referrer_category` varchar(32) NOT NULL,
  `traffic_type` varchar(16) NOT NULL,
  `event_count` bigint unsigned NOT NULL DEFAULT 0,
  `created_at` datetime NOT NULL,
  `updated_at` datetime NOT NULL,
  PRIMARY KEY (`event_date_utc`, `event_hour_utc`, `event_name`, `page_path`, `product`, `locale`, `referrer_category`, `traffic_type`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
