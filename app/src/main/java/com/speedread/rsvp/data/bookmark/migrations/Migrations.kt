package com.speedread.rsvp.data.bookmark.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Registry of every forward Room migration for `BookmarkDatabase`.
 *
 * To add a migration for version N -> N+1:
 * 1. Bump `@Database(version = N+1)` in `BookmarkDatabase`.
 * 2. Build once so Room emits `app/schemas/.../${N+1}.json`. Commit it.
 * 3. Define `val MIGRATION_${N}_${N+1} = object : Migration(${N}, ${N+1}) { ... }` here.
 * 4. Append it to [ALL_MIGRATIONS].
 * 5. Add a `MigrationTestHelper` test in `BookmarkDatabaseMigrationTest` that opens
 *    the old schema JSON, runs the migration, and validates against the new schema.
 *
 * v11 is the first version with a committed schema JSON (`app/schemas/.../11.json`).
 * No JSONs exist for v1..v10, so pre-v11 installs are handled via a bounded
 * `fallbackToDestructiveMigrationFrom` in `AppModule`; see
 * `Constants.LEGACY_DB_VERSIONS_WITHOUT_SCHEMA`. When that fallback fires, a
 * one-time notice is shown to the user on next launch via `SchemaResetNotifier`.
 *
 * An upgrade originating from v11 or later without a matching Migration in this
 * array will crash at launch — by design; we do not silently destroy user data.
 */
/**
 * v11 -> v12: figure-region metadata for inline figures in Page View. Nullable TEXT column
 * mirroring the pageBoundariesJson pattern; existing rows keep NULL (their PDFs simply have
 * no detected figures until re-imported).
 */
internal val MIGRATION_11_12: Migration = object : Migration(11, 12) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE saved_documents ADD COLUMN figureRegionsJson TEXT")
    }
}

internal val ALL_MIGRATIONS: Array<Migration> = arrayOf(
    MIGRATION_11_12
)
