package com.gios.brightnotebooklite.data

import androidx.room.Database
import androidx.room.RoomDatabase
import com.thelightphone.sdk.SealedLightContext
import com.thelightphone.sdk.buildDatabase

@Database(
    entities = [NoteEntity::class, CalendarEntity::class, EntryEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class NotebookDatabase : RoomDatabase() {
    abstract fun dao(): NotebookDao
}

/**
 * One database for the process. Screens and the background refresh both get here through the
 * SDK's own `buildDatabase`, which is the sanctioned way for a tool to open Room.
 */
object Notebook {
    private const val NAME = "notebook.db"

    @Volatile
    private var instance: NotebookDatabase? = null

    fun dao(context: SealedLightContext): NotebookDao = db(context).dao()

    private fun db(context: SealedLightContext): NotebookDatabase =
        instance ?: synchronized(this) {
            instance ?: context.buildDatabase(NotebookDatabase::class.java, NAME).also { instance = it }
        }
}
