package com.itsme.amkush.ipc

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.MatrixCursor
import android.database.Cursor
import android.net.Uri

/**
 * Cross-process ContentProvider — exposes injection config to hooked processes.
 * URI: content://com.itsme.amkush.ipc/config/{key}
 */
class FaceGateIpcProvider : ContentProvider() {

    companion object {
        const val AUTHORITY  = "com.itsme.amkush.ipc"
        const val BASE_PATH  = "config"
        const val COL_KEY    = "key"
        const val COL_VALUE  = "value"
        val BASE_URI: Uri = Uri.parse("content://$AUTHORITY/$BASE_PATH")
    }

    private fun prefs() = context?.getSharedPreferences("facegate_ipc", Context.MODE_PRIVATE)

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri, projection: Array<String>?,
        selection: String?, selectionArgs: Array<String>?, sortOrder: String?
    ): Cursor? {
        val key    = uri.lastPathSegment ?: return null
        val value  = prefs()?.getString(key, null)
        val cursor = MatrixCursor(arrayOf(COL_KEY, COL_VALUE))
        if (value != null) cursor.addRow(arrayOf(key, value))
        return cursor
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? {
        values ?: return null
        val key   = uri.lastPathSegment ?: (values.getAsString(COL_KEY) ?: return null)
        val value = values.getAsString(COL_VALUE)
        val ed    = prefs()?.edit() ?: return null
        if (value == null) ed.remove(key) else ed.putString(key, value)
        ed.apply()
        context?.contentResolver?.notifyChange(uri, null)
        return uri
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<String>?): Int {
        val key = uri.lastPathSegment ?: return 0
        prefs()?.edit()?.remove(key)?.apply()
        context?.contentResolver?.notifyChange(uri, null)
        return 1
    }

    override fun update(uri: Uri, values: ContentValues?, sel: String?, args: Array<String>?): Int {
        insert(uri, values); return 1
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/vnd.$AUTHORITY.config"
}
