package org.mozilla.scryer.persistence

import androidx.room.*
import java.util.*

@Fts4(contentEntity = ScreenshotModel::class)
@Entity(tableName = "fts")
data class FtsEntity constructor (
        @PrimaryKey(autoGenerate = true) var rowid: Int = 0,
        @ColumnInfo(name = "content_text") var contentText: String) {
}
