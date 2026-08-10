package com.speedread.rsvp.di

import android.app.DownloadManager
import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.speedread.rsvp.Constants
import com.speedread.rsvp.data.ClipboardTextProvider
import com.speedread.rsvp.data.TextProvider
import com.speedread.rsvp.data.bookmark.BookmarkDao
import com.speedread.rsvp.data.bookmark.BookmarkDatabase
import com.speedread.rsvp.data.bookmark.SchemaResetNotifier
import com.speedread.rsvp.data.bookmark.migrations.ALL_MIGRATIONS
import com.speedread.rsvp.data.parser.EpubFileParser
import com.speedread.rsvp.data.parser.FileParser
import com.speedread.rsvp.data.parser.PdfFileParser
import com.speedread.rsvp.data.parser.TxtFileParser
import com.speedread.rsvp.engine.DefaultRsvpEngine
import com.speedread.rsvp.engine.DefaultTextProcessor
import com.speedread.rsvp.engine.RsvpEngine
import com.speedread.rsvp.engine.TextProcessor
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import dagger.multibindings.IntoSet
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    
    @Provides
    @Singleton
    fun provideRsvpEngine(): RsvpEngine {
        return DefaultRsvpEngine()
    }

    // Shared tokenizer: both RSVP playback and Page View pagination derive words from the
    // same TextProcessor so word-position indices (stored in bookmarks, page boundaries,
    // and the highlight StateFlow) stay consistent across modes regardless of line-ending
    // quirks (CR / CRLF) or the MAX_TOKENS safety cap.
    @Provides
    @Singleton
    fun provideTextProcessor(): TextProcessor {
        return DefaultTextProcessor()
    }
    
    @Provides
    @Singleton
    fun provideTextProvider(
        clipboardProvider: ClipboardTextProvider
    ): TextProvider {
        return clipboardProvider
    }
    
    @Provides
    @Singleton
    fun provideBookmarkDatabase(
        @ApplicationContext context: Context,
        schemaResetNotifier: SchemaResetNotifier
    ): BookmarkDatabase {
        val resetCallback = object : RoomDatabase.Callback() {
            override fun onDestructiveMigration(db: SupportSQLiteDatabase) {
                // Fires only when Room fell back to destructive migration, i.e. a user on
                // a pre-v11 install upgraded through here. Record the event so MainActivity
                // can surface a one-time notice on the next foreground.
                schemaResetNotifier.markReset()
            }
        }

        return Room.databaseBuilder(
            context,
            BookmarkDatabase::class.java,
            BookmarkDatabase.DATABASE_NAME
        )
            .addMigrations(*ALL_MIGRATIONS)
            // v11 is the first version with an exported schema JSON (see app/schemas/).
            // Pre-v11 installs have no reconstructable migration path, so permit a
            // one-shot destructive fallback limited to those legacy versions. Upgrades
            // originating from v11+ must ship a registered Migration or crash.
            .fallbackToDestructiveMigrationFrom(
                /* dropAllTables = */ false,
                *Constants.LEGACY_DB_VERSIONS_WITHOUT_SCHEMA
            )
            // Only downgrades — rare, mostly dev — are allowed to drop tables.
            .fallbackToDestructiveMigrationOnDowngrade(dropAllTables = false)
            .addCallback(resetCallback)
            .build()
    }
    
    @Provides
    fun provideBookmarkDao(database: BookmarkDatabase): BookmarkDao {
        return database.bookmarkDao()
    }
    
    @Provides
    fun provideSavedDocumentDao(database: BookmarkDatabase): com.speedread.rsvp.data.document.SavedDocumentDao {
        return database.savedDocumentDao()
    }
    
    
    @Provides
    fun provideTagDao(database: BookmarkDatabase): com.speedread.rsvp.data.tags.TagDao {
        return database.tagDao()
    }

    // The system DownloadManager has no Hilt-friendly constructor, so we provide it
    // explicitly. VoiceCatalogRepository depends on it for the in-app voice-catalog flow.
    @Provides
    @Singleton
    fun provideDownloadManager(
        @ApplicationContext context: Context
    ): DownloadManager =
        context.getSystemService(Context.DOWNLOAD_SERVICE) as DownloadManager

}

@Module
@InstallIn(SingletonComponent::class)
abstract class FileParserModule {
    
    @Binds
    @IntoSet
    abstract fun bindPdfFileParser(pdfFileParser: PdfFileParser): FileParser
    
    @Binds
    @IntoSet
    abstract fun bindEpubFileParser(epubFileParser: EpubFileParser): FileParser
    
    @Binds
    @IntoSet
    abstract fun bindTxtFileParser(txtFileParser: TxtFileParser): FileParser

    // UrlFileParser is intentionally NOT in the FileParser multibinding set. It's accessed
    // only via direct injection from FileTextProvider for URL imports; routing local .html
    // files through it would surface a misleading "invalid scheme" error because its
    // parseFile(Uri) only accepts http/https. Hilt still supplies it via its @Inject
    // constructor for the direct injection site.
}