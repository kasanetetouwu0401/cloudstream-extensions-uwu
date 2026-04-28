
package com.auratail

import android.content.Context
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class AuratailPlugin : Plugin() {
    override fun load(context: Context) {
        registerMainAPI(Auratail())
    }
}
