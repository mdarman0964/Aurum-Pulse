package com.ras.hedgefund

import android.content.Context
import org.json.JSONObject
import java.io.File

/** Append-only trade journal (one JSON object per line) in app-private storage. */
object Journal {
    private fun file(ctx: Context) = File(ctx.filesDir, "journal.jsonl")

    @Synchronized
    fun append(ctx: Context, o: JSONObject) { file(ctx).appendText(o.toString() + "\n") }

    @Synchronized
    fun recent(ctx: Context, n: Int): List<String> {
        val f = file(ctx)
        if (!f.exists()) return emptyList()
        return f.readLines().takeLast(n).reversed()
    }
}
