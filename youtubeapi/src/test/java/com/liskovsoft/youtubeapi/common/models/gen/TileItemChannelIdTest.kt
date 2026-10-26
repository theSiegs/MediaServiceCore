package com.liskovsoft.youtubeapi.common.models.gen

import com.google.gson.Gson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class TileItemChannelIdTest {
    @Test
    fun byLineLinkGivesChannelId() {
        val tile = parseTile(run("""{"browseEndpoint": {"browseId": "UCsingle"}}"""))

        assertEquals("UCsingle", tile.getChannelId())
    }

    @Test
    fun collaborationByLineGivesFirstChannelOfSheet() {
        val tile = parseTile(run("""{"showSheetCommand": {"panelLoadingStrategy": {"inlineContent": {"sheetViewModel": {"content": {"listViewModel": {"listItems": [
            {"listItemViewModel": {"rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {"browseEndpoint": {"browseId": "UCuploader"}}}}}}},
            {"listItemViewModel": {"rendererContext": {"commandContext": {"onTap": {"innertubeCommand": {"browseEndpoint": {"browseId": "UCguest"}}}}}}}
        ]}}}}}}}"""))

        assertEquals("UCuploader", tile.getChannelId())
    }

    @Test
    fun byLineWithoutLinkHasNoChannelId() {
        val tile = parseTile(run("{}"))

        assertNull(tile.getChannelId())
    }

    // The byline text run of a player suggestion tile, with the given navigationEndpoint
    private fun run(navigationEndpoint: String) = """{"metadata": {"tileMetadataRenderer": {"lines": [{"lineRenderer": {"items": [
        {"lineItemRenderer": {"text": {"runs": [{"text": "Channel", "navigationEndpoint": $navigationEndpoint}]}}}
    ]}}]}}}"""

    private fun parseTile(json: String) = Gson().fromJson(json, TileItem::class.java)
}
