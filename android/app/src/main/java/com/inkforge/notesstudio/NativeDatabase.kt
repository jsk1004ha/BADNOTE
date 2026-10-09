package com.inkforge.notesstudio

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Entity(tableName = "documents")
data class DocumentRow(@PrimaryKey val id: String, val body: String, val updated: Long)

@Entity(tableName = "pages", foreignKeys = [ForeignKey(
    entity = DocumentRow::class, parentColumns = ["id"], childColumns = ["document_id"], onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["document_id", "position"], name = "page_order")])
data class PageRow(@PrimaryKey val id: String, @ColumnInfo(name = "document_id") val documentId: String,
                   val position: Int, val body: String)

@Entity(tableName = "objects", primaryKeys = ["page_id", "id"], foreignKeys = [ForeignKey(
    entity = PageRow::class, parentColumns = ["id"], childColumns = ["page_id"], onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["page_id", "position"], name = "object_order")])
data class ObjectRow(@ColumnInfo(name = "page_id") val pageId: String, val id: String,
                     val position: Int, val body: String)

@Entity(tableName = "settings")
data class SettingRow(@PrimaryKey val key: String, val body: String)

@Entity(tableName = "page_generation", foreignKeys = [ForeignKey(
    entity = PageRow::class, parentColumns = ["id"], childColumns = ["page_id"], onDelete = ForeignKey.CASCADE
)])
data class PageGenerationRow(@PrimaryKey @ColumnInfo(name = "page_id") val pageId: String,
                             val generation: Long, @ColumnInfo(name = "next_capture_seq") val nextCaptureSeq: Long)

@Entity(tableName = "ocr_page_state", foreignKeys = [ForeignKey(
    entity = PageRow::class, parentColumns = ["id"], childColumns = ["page_id"], onDelete = ForeignKey.CASCADE
)], indices = [Index(value = ["page_id"], name = "ocr_page_state_page")])
data class OcrPageStateRow(@PrimaryKey @ColumnInfo(name = "page_id") val pageId: String,
                           @ColumnInfo(name = "document_id") val documentId: String,
                           val generation: Long, val digest: String, val status: String,
                           val body: String)

@Entity(tableName = "ocr_regions", primaryKeys = ["page_id", "region_id"], foreignKeys = [ForeignKey(
    entity = PageRow::class, parentColumns = ["id"], childColumns = ["page_id"], onDelete = ForeignKey.CASCADE
)])
data class OcrRegionRow(@ColumnInfo(name = "page_id") val pageId: String,
                        @ColumnInfo(name = "region_id") val regionId: String, val body: String)

@Entity(tableName = "ocr_corrections", primaryKeys = ["page_id", "region_id"], foreignKeys = [ForeignKey(
    entity = PageRow::class, parentColumns = ["id"], childColumns = ["page_id"], onDelete = ForeignKey.CASCADE
)])
data class OcrCorrectionRow(@ColumnInfo(name = "page_id") val pageId: String,
                            @ColumnInfo(name = "region_id") val regionId: String,
                            val body: String, @ColumnInfo(name = "updated_at") val updatedAt: Long)

@Entity(tableName = "ocr_cache")
data class OcrCacheRow(@PrimaryKey @ColumnInfo(name = "content_key") val contentKey: String,
                       val body: String, @ColumnInfo(name = "byte_count") val byteCount: Int,
                       @ColumnInfo(name = "last_used") val lastUsed: Long)

@Database(entities = [DocumentRow::class, PageRow::class, ObjectRow::class, SettingRow::class,
    PageGenerationRow::class, OcrPageStateRow::class, OcrRegionRow::class,
    OcrCorrectionRow::class, OcrCacheRow::class], version = 2, exportSchema = false)
abstract class NativeDatabase : RoomDatabase() {
    companion object {
        const val NAME = "badnote-native.db"

        /** SQLite v1 allowed null TEXT primary keys. Keep those exact rows in a recovery table. */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.query("SELECT COUNT(*) FROM pages p LEFT JOIN documents d ON p.document_id=d.id WHERE d.id IS NULL").use {
                    it.moveToFirst(); require(it.getLong(0) == 0L) { "v1 page has no document; migration rolled back" }
                }
                db.query("SELECT COUNT(*) FROM objects o LEFT JOIN pages p ON o.page_id=p.id WHERE p.id IS NULL").use {
                    it.moveToFirst(); require(it.getLong(0) == 0L) { "v1 object has no page; migration rolled back" }
                }
                db.execSQL("CREATE TABLE IF NOT EXISTS legacy_v1_null_rows(source_table TEXT NOT NULL,source_rowid INTEGER NOT NULL,id_or_key TEXT,foreign_id TEXT,position INTEGER,body TEXT NOT NULL,updated INTEGER,PRIMARY KEY(source_table,source_rowid))")
                db.execSQL("INSERT INTO legacy_v1_null_rows SELECT 'documents',rowid,id,NULL,NULL,body,updated FROM documents WHERE id IS NULL")
                db.execSQL("INSERT INTO legacy_v1_null_rows SELECT 'pages',rowid,id,document_id,position,body,NULL FROM pages WHERE id IS NULL")
                db.execSQL("INSERT INTO legacy_v1_null_rows SELECT 'settings',rowid,key,NULL,NULL,body,NULL FROM settings WHERE key IS NULL")

                db.execSQL("CREATE TABLE documents_new(id TEXT NOT NULL PRIMARY KEY,body TEXT NOT NULL,updated INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE pages_new(id TEXT NOT NULL PRIMARY KEY,document_id TEXT NOT NULL,position INTEGER NOT NULL,body TEXT NOT NULL)")
                db.execSQL("CREATE TABLE objects_new(page_id TEXT NOT NULL,id TEXT NOT NULL,position INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(page_id,id))")
                db.execSQL("CREATE TABLE settings_new(key TEXT NOT NULL PRIMARY KEY,body TEXT NOT NULL)")
                db.execSQL("INSERT INTO documents_new SELECT id,body,updated FROM documents WHERE id IS NOT NULL ORDER BY rowid")
                db.execSQL("INSERT INTO pages_new SELECT id,document_id,position,body FROM pages WHERE id IS NOT NULL ORDER BY rowid")
                db.execSQL("INSERT INTO objects_new SELECT page_id,id,position,body FROM objects ORDER BY rowid")
                db.execSQL("INSERT INTO settings_new SELECT key,body FROM settings WHERE key IS NOT NULL ORDER BY rowid")
                db.execSQL("DROP TABLE objects")
                db.execSQL("DROP TABLE pages")
                db.execSQL("DROP TABLE documents")
                db.execSQL("DROP TABLE settings")
                db.execSQL("CREATE TABLE documents(id TEXT NOT NULL PRIMARY KEY,body TEXT NOT NULL,updated INTEGER NOT NULL)")
                db.execSQL("CREATE TABLE pages(id TEXT NOT NULL PRIMARY KEY,document_id TEXT NOT NULL,position INTEGER NOT NULL,body TEXT NOT NULL,FOREIGN KEY(document_id) REFERENCES documents(id) ON DELETE CASCADE ON UPDATE NO ACTION)")
                db.execSQL("CREATE TABLE objects(page_id TEXT NOT NULL,id TEXT NOT NULL,position INTEGER NOT NULL,body TEXT NOT NULL,PRIMARY KEY(page_id,id),FOREIGN KEY(page_id) REFERENCES pages(id) ON DELETE CASCADE ON UPDATE NO ACTION)")
                db.execSQL("CREATE TABLE settings(key TEXT NOT NULL PRIMARY KEY,body TEXT NOT NULL)")
                db.execSQL("INSERT INTO documents SELECT * FROM documents_new ORDER BY rowid")
                db.execSQL("INSERT INTO pages SELECT * FROM pages_new ORDER BY rowid")
                db.execSQL("INSERT INTO objects SELECT * FROM objects_new ORDER BY rowid")
                db.execSQL("INSERT INTO settings SELECT * FROM settings_new ORDER BY rowid")
                db.execSQL("DROP TABLE objects_new")
                db.execSQL("DROP TABLE pages_new")
                db.execSQL("DROP TABLE documents_new")
                db.execSQL("DROP TABLE settings_new")
                db.execSQL("CREATE INDEX page_order ON pages(document_id,position)")
                db.execSQL("CREATE INDEX object_order ON objects(page_id,position)")
                createOcrTables(db)
            }
        }

        private fun createOcrTables(db: SupportSQLiteDatabase) {
            db.execSQL("CREATE TABLE IF NOT EXISTS page_generation(page_id TEXT NOT NULL PRIMARY KEY,generation INTEGER NOT NULL,next_capture_seq INTEGER NOT NULL,FOREIGN KEY(page_id) REFERENCES pages(id) ON DELETE CASCADE ON UPDATE NO ACTION)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ocr_page_state(page_id TEXT NOT NULL PRIMARY KEY,document_id TEXT NOT NULL,generation INTEGER NOT NULL,digest TEXT NOT NULL,status TEXT NOT NULL,body TEXT NOT NULL,FOREIGN KEY(page_id) REFERENCES pages(id) ON DELETE CASCADE ON UPDATE NO ACTION)")
            db.execSQL("CREATE INDEX IF NOT EXISTS ocr_page_state_page ON ocr_page_state(page_id)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ocr_regions(page_id TEXT NOT NULL,region_id TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(page_id,region_id),FOREIGN KEY(page_id) REFERENCES pages(id) ON DELETE CASCADE ON UPDATE NO ACTION)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ocr_corrections(page_id TEXT NOT NULL,region_id TEXT NOT NULL,body TEXT NOT NULL,updated_at INTEGER NOT NULL,PRIMARY KEY(page_id,region_id),FOREIGN KEY(page_id) REFERENCES pages(id) ON DELETE CASCADE ON UPDATE NO ACTION)")
            db.execSQL("CREATE TABLE IF NOT EXISTS ocr_cache(content_key TEXT NOT NULL PRIMARY KEY,body TEXT NOT NULL,byte_count INTEGER NOT NULL,last_used INTEGER NOT NULL)")
        }

        fun open(context: Context, name: String = NAME): NativeDatabase = Room.databaseBuilder(
            context.applicationContext, NativeDatabase::class.java, name
        ).addMigrations(MIGRATION_1_2).build()
    }
}
