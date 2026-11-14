package com.phonepc.pairing

import com.phonepc.pairing.ui.JarvisIntent
import com.phonepc.pairing.ui.JarvisParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Voice-command parsing for the pro PC commands:
 *  - "open <site> in <name> <n> chrome profile" (page AND profile in one go)
 *  - "open <domain> and search <query> (on it)"
 *  - regressions for the plain open / search forms
 */
class JarvisCommandsTest {

    private fun pc(raw: String): JarvisIntent.Pc {
        val intent = JarvisParser.parse(raw)
        assertTrue("expected a PC command for \"$raw\", got $intent", intent is JarvisIntent.Pc)
        return intent as JarvisIntent.Pc
    }

    // ---- Chrome profile: page + profile must BOTH survive ----

    @Test
    fun `open fiverr in ali 2 chrome profile keeps the page and the profile`() {
        val c = pc("open fiverr in ali 2 chrome profile")
        assertEquals("LAUNCH_APP", c.action)
        assertEquals("chrome", c.args["app"])
        assertEquals("ali 2", c.args["profileName"])
        assertEquals("2", c.args["profile"])
        assertTrue("page should be fiverr, got ${c.args["url"]}",
            (c.args["url"] ?: "").contains("fiverr.com"))
    }

    @Test
    fun `profile-first phrasing still carries the page`() {
        val c = pc("open ali 3 chrome profile and open fiverr")
        assertEquals("LAUNCH_APP", c.action)
        assertEquals("ali 3", c.args["profileName"])
        assertEquals("3", c.args["profile"])
        assertTrue((c.args["url"] ?: "").contains("fiverr.com"))
    }

    @Test
    fun `profile with no page just opens that profile`() {
        val c = pc("open my ali 2 chrome profile")
        assertEquals("LAUNCH_APP", c.action)
        assertEquals("ali 2", c.args["profileName"])
        assertTrue("no page requested", c.args["url"] == null)
    }

    // ---- open a site AND search on it ----

    @Test
    fun `open domain and search query on it builds the site search`() {
        val c = pc("open youtube.com and search funny cats on it")
        assertEquals("OPEN_URL", c.action)
        assertEquals("https://www.youtube.com/results?search_query=funny+cats", c.args["url"])
    }

    @Test
    fun `unknown domain falls back to a site-restricted google search`() {
        val c = pc("open example-shop.com and search running shoes")
        assertEquals("OPEN_URL", c.action)
        val u = c.args["url"] ?: ""
        assertTrue("got $u", u.startsWith("https://www.google.com/search?q=running+shoes+site%3A"))
    }

    @Test
    fun `search query on a bare domain word uses that site`() {
        val c = pc("search logo design on fiverr.com")
        assertEquals("OPEN_URL", c.action)
        assertTrue("got ${c.args["url"]}", (c.args["url"] ?: "").contains("fiverr.com"))
    }

    // ---- regressions: the plain forms must still parse the way they did ----

    @Test
    fun `plain open still resolves known sites`() {
        val c = pc("open fiverr")
        assertEquals("OPEN_URL", c.action)
        assertEquals("https://www.fiverr.com", c.args["url"])
    }

    @Test
    fun `plain app launch still works`() {
        val c = pc("open chrome")
        assertEquals("LAUNCH_APP", c.action)
        assertEquals("chrome", c.args["app"])
        assertTrue("plain launch must not claim a profile", c.args["profile"] == null)
    }

    @Test
    fun `search on youtube by word still works`() {
        val c = pc("search funny cats on youtube")
        assertEquals("OPEN_URL", c.action)
        assertTrue("got ${c.args["url"]}",
            (c.args["url"] ?: "").startsWith("https://www.youtube.com/results?search_query="))
    }
}
