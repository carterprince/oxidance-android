package io.github.oxidance

import io.github.oxidance.core.SearchItem
import io.github.oxidance.core.YtMusic
import org.junit.Test

class LiveSearchTest {
    @Test fun artistIds() {
        val client = YtMusic()
        println("suggestions: " + client.suggestions("nujabes").filterIsInstance<SearchItem.ArtistItem>().map { it.artist.link })
        println("artists: " + client.searchArtists("nujabes").map { it.link })
    }
}
