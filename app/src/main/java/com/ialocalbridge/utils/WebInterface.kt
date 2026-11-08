package com.ialocalbridge.utils

import android.content.Context

object WebInterface {
    fun getHtml(context: Context): String =
        context.assets.open("www/index.html").bufferedReader(Charsets.UTF_8).use { it.readText() }
}
