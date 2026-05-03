package com.platform.shared.database

/**
 * Who owns Flyway migrations on cold start.
 *
 * Both `platform` and `collector` ship the same `shared/` migration resources
 * on their classpath. If both ran [MIGRATE] concurrently, they would race for
 * Flyway's `flyway_schema_history` lock — safe for data integrity but a real
 * source of cold-start latency and lock-timeout failures as the migration
 * chain grows. Splitting ownership eliminates the race and makes the schema
 * lifecycle explicit.
 */
enum class MigrationMode {
    /** Run pending migrations. Exactly one service per database should use this. */
    MIGRATE,

    /**
     * Validate that the schema matches the migrations on the classpath; do not
     * apply anything. Fails fast (process exits non-zero) if the schema is
     * missing migrations the binary expects — Cloud Run / Kubernetes treats
     * the failed startup as a probe failure and retries the revision once the
     * migrating service has caught up.
     */
    VALIDATE,
}
