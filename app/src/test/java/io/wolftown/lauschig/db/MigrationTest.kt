package io.wolftown.lauschig.db

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * Version 1 is what the first test installs carry; their progress must survive the update. The v1 database is
 * built from the exported schema (MigrationTestHelper cannot read unit-test assets under Robolectric).
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {
    @Test fun v1ToV2_keepsProgress_andDefaultsHideToFalse() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val file = context.getDatabasePath("migration-test.db").apply { parentFile!!.mkdirs(); delete() }
        val schema = JSONObject(File("schemas/io.wolftown.lauschig.db.LauschigDatabase/1.json").readText()).getJSONObject("database")
        SQLiteDatabase.openOrCreateDatabase(file, null).use { db ->
            val entities = schema.getJSONArray("entities")
            for (i in 0 until entities.length()) {
                val e = entities.getJSONObject(i)
                val table = e.getString("tableName")
                db.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
                e.optJSONArray("indices")?.let { idx -> for (j in 0 until idx.length()) db.execSQL(idx.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table)) }
            }
            val setup = schema.getJSONArray("setupQueries")
            for (i in 0 until setup.length()) db.execSQL(setup.getString(i))
            db.execSQL("INSERT INTO progress (serverId, itemId, currentTimeSec, durationSec, isFinished, lastUpdate, dirty) VALUES ('s', 'a', 12.5, 100.0, 0, 7, 1)")
            db.version = 1
        }
        val room = Room.databaseBuilder(context, LauschigDatabase::class.java, file.path).allowMainThreadQueries().build()
        try {
            val p = room.progressDao().get("s", "a")!!
            assertEquals(12.5, p.currentTimeSec, 0.0)
            assertFalse(p.hideFromContinue)
        } finally {
            room.close()
        }
    }
}
