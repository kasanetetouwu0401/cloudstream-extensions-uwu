// use an integer for version numbers
version = 42

android {
    buildFeatures {
        buildConfig = true
    }
}

cloudstream {
    
    language = "en"
    // All of these properties are optional, you can safely remove them

    description = "Disney+ Multiple Languages"
    authors = listOf("horis")

    /**
     * Status int as the following:
     * 0: Down
     * 1: Ok
     * 2: Slow
     * 3: Beta only
     * */
    status = 1 // will be 3 if unspecified
    tvTypes = listOf(
        "Movie",
        "TvSeries"
    )

    iconUrl = "https://i.postimg.cc/TYKyC16H/images.png"
}
