package com.speedread.rsvp.data.bookmark

import androidx.room.testing.MigrationTestHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.speedread.rsvp.data.bookmark.migrations.ALL_MIGRATIONS
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke tests for BookmarkDatabase migrations.
 *
 * Validates that the current schema (v11) opens cleanly via [MigrationTestHelper]
 * from the exported JSON under `app/schemas/`. When a new schema version ships,
 * add a matching test that opens the old version, runs the migration in
 * [ALL_MIGRATIONS], and asserts the resulting schema matches the new JSON.
 */
@RunWith(AndroidJUnit4::class)
class BookmarkDatabaseMigrationTest {

    @get:Rule
    val helper: MigrationTestHelper = MigrationTestHelper(
        InstrumentationRegistry.getInstrumentation(),
        BookmarkDatabase::class.java
    )

    @Test
    fun currentSchemaOpensCleanly() {
        // Creates a DB at the current version using the exported JSON.
        helper.createDatabase(TEST_DB_NAME, CURRENT_VERSION).close()

        // Reopen through the Room builder (with ALL_MIGRATIONS registered) and
        // run a trivial query to force schema validation. If the exported JSON
        // and the code-generated schema disagree, this throws.
        val runtimeDb = androidx.room.Room.databaseBuilder(
            InstrumentationRegistry.getInstrumentation().targetContext,
            BookmarkDatabase::class.java,
            TEST_DB_NAME
        )
            .addMigrations(*ALL_MIGRATIONS)
            .build()
        runtimeDb.openHelper.readableDatabase
        runtimeDb.close()
    }

    companion object {
        private const val TEST_DB_NAME = "migration-test.db"
        private const val CURRENT_VERSION = 11
    }
}
