package com.horis.cncverse

import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin
import android.content.Context

@CloudstreamPlugin
open class CNCVersePlugin : Plugin() {
    override fun load(context: Context) {
        DisneyPlusStorage.init(context.applicationContext)
        registerMainAPI(DisneyPlusProvider())
    }
}
