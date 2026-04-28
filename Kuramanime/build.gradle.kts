Version = 1

cloudstream {
    language = "id"
    authors = listOf("Sanzz")
    status = 1
    tvTypes = listOf(
        "Anime",
        "AnimeMovie",
        "OVA",
    )

    iconUrl = "https://t2.gstatic.com/faviconV2?client=SOCIAL&type=FAVICON&fallback_opts=TYPE,SIZE,URL&url=https://v17.kuramanime.ink&size=%size%"
}

// Tambahkan blok dependencies ini di bagian bawah ya sayang! 👇
dependencies {
    // Pastikan dependensi bawaan project CloudStream tetap ada
    implementation(project(":Cloudstream"))
    
    // Ini senjata rahasia kita dari Miku! (Rhino JavaScript Engine)
    implementation("org.mozilla:rhino:1.7.14")
}
