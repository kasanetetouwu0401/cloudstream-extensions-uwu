package com.dubbindo

import com.dubbindo.Dubbindo
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin
import com.lagradost.cloudstream3.plugins.Plugin

@CloudstreamPlugin
class DubbindoPlugin : Plugin() {
    override fun load() {
        registerMainAPI(Dubbindo())
    }
}
