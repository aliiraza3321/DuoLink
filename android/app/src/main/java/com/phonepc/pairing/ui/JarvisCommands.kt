package com.phonepc.pairing.ui

import java.net.URLEncoder
import java.util.Locale

/** What the assistant understood. Parsing is fully on-device (no cloud, no account). */
sealed class JarvisIntent {
    /** Media key on the PC (VOLUME_UP, VOLUME_DOWN, VOLUME_MUTE, PLAY_PAUSE, NEXT, PREV). */
    data class Media(val action: String, val reply: String) : JarvisIntent()
    /** A PC_COMMAND handled (and answered) by the PC. */
    data class Pc(val action: String, val args: Map<String, String>) : JarvisIntent()
    /** Run an open/search action ON THE PHONE ("... on phone" routing). */
    data class PhoneOpen(val kind: String, val arg: String, val label: String) : JarvisIntent()
    /** Dial a number or a contact name from the phone ("call Abu G"). */
    data class Call(val target: String) : JarvisIntent()
    data class TypeText(val text: String) : JarvisIntent()
    data class Chat(val text: String) : JarvisIntent()
    data class Key(val name: String) : JarvisIntent()
    data class Click(val button: String, val double: Boolean) : JarvisIntent()
    data class Scroll(val delta: Int) : JarvisIntent()
    data class Torch(val on: Boolean) : JarvisIntent()
    data class Timer(val seconds: Int) : JarvisIntent()
    /** Android alarm clock: hour in 0..23, minute in 0..59. */
    data class Alarm(val hour: Int, val minute: Int) : JarvisIntent()
    /** "restart chrome": close then relaunch one app on the PC (never the PC itself). */
    data class RestartApp(val app: String, val name: String) : JarvisIntent()
    /** Phone's own media volume: action is UP | DOWN | MUTE | SET (level 0..100). */
    data class PhoneVolume(val action: String, val level: Int) : JarvisIntent()
    /** The PC's host name, read from the live stats stream. */
    object PcName : JarvisIntent()
    data class Say(val text: String) : JarvisIntent()
    object Joke : JarvisIntent()
    object Status : JarvisIntent()
    object PhoneBattery : JarvisIntent()
    object TimeNow : JarvisIntent()
    object DateToday : JarvisIntent()
    object Greeting : JarvisIntent()
    object Thanks : JarvisIntent()
    object Help : JarvisIntent()
    data class Unknown(val heard: String) : JarvisIntent()
}

object JarvisParser {

    // ---- Devanagari (Hindi script) -> English keywords, longest first ----
    private val dev: List<Pair<String, String>> = listOf(
        "खोल दो" to "open", "खोलिए" to "open", "खोलो" to "open", "ओपन" to "open",
        "चला दो" to "play", "चलाओ" to "play", "बजाओ" to "play", "प्ले" to "play",
        "सर्च" to "search", "खोजो" to "search", "ढूंढो" to "search",
        "यूट्यूब" to "youtube", "गूगल" to "google", "क्रोम" to "chrome",
        "गाने" to "songs", "गाना" to "songs", "हिंदी" to "hindi", "पंजाबी" to "punjabi",
        "लॉक" to "lock", "स्क्रीनशॉट" to "screenshot",
        "आवाज़" to "volume", "आवाज" to "volume", "वॉल्यूम" to "volume",
        "बढ़ाओ" to "up", "बढाओ" to "up", "कम करो" to "down", "बंद" to "off", "करो" to "",
        "पीसी" to "pc", "कंप्यूटर" to "computer", "म्यूट" to "mute", "अगला" to "next",
        "पिछला" to "previous", "रोको" to "pause", "वीडियो" to "video", "नोटपैड" to "notepad",
        "कैलकुलेटर" to "calculator", "स्पॉटिफाई" to "spotify", "पर" to "on", "में" to "in"
    ).sortedByDescending { it.first.length }

    // ---- Hinglish (Hindi in English letters) ----
    private val hinPhrases = listOf("kam karo" to "down", "kam kar do" to "down", "kam kar" to "down")
    private val hinWords = mapOf(
        "kholo" to "open", "khol" to "open", "chalao" to "play", "chalu" to "play", "bajao" to "play",
        "lagao" to "play", "laga" to "play", "dhundo" to "search", "dhoondo" to "search", "khojo" to "search",
        "gaana" to "songs", "gaane" to "songs", "gana" to "songs", "gane" to "songs",
        "awaaz" to "volume", "awaz" to "volume", "aawaz" to "volume", "badhao" to "up", "ghatao" to "down",
        "band" to "off", "karo" to "", "kijiye" to "", "dijiye" to "", "pe" to "on", "par" to "on",
        "mein" to "in", "agla" to "next", "agli" to "next", "pichla" to "previous", "roko" to "pause"
    )
    private val hinRegex = Regex("\\b(" + hinWords.keys.joinToString("|") + ")\\b")

    // spoken phrase -> app key the PC understands (PC only launches keys from its own whitelist)
    private val apps: List<Pair<String, String>> = listOf(
        "google chrome" to "chrome", "chrome" to "chrome", "browser" to "chrome",
        "microsoft edge" to "edge", "edge" to "edge", "firefox" to "firefox",
        "notepad" to "notepad", "calculator" to "calculator", "calc" to "calculator",
        "file explorer" to "explorer", "explorer" to "explorer", "my files" to "explorer",
        "task manager" to "taskmanager", "settings" to "settings", "spotify" to "spotify",
        "visual studio code" to "vscode", "vs code" to "vscode", "vscode" to "vscode",
        "windows terminal" to "terminal", "command prompt" to "terminal", "terminal" to "terminal",
        "paint" to "paint", "word" to "word", "excel" to "excel", "powerpoint" to "powerpoint",
        "whatsapp" to "whatsapp", "camera" to "camera", "microsoft store" to "store", "store" to "store",
        "snipping tool" to "snipping", "control panel" to "controlpanel", "device manager" to "devicemanager",
        "downloads" to "downloads", "documents" to "documents", "desktop" to "desktopfolder"
    ).sortedByDescending { it.first.length }

    private val closable = setOf("chrome", "edge", "firefox", "notepad", "calculator", "spotify", "vscode", "word", "excel", "powerpoint", "paint")

    private val sites = mapOf(
        "youtube" to "https://www.youtube.com", "google" to "https://www.google.com", "gmail" to "https://mail.google.com",
        "github" to "https://github.com", "maps" to "https://maps.google.com", "whatsapp" to "https://web.whatsapp.com",
        "netflix" to "https://www.netflix.com", "twitter" to "https://x.com", "linkedin" to "https://www.linkedin.com",
        "facebook" to "https://www.facebook.com", "instagram" to "https://www.instagram.com", "reddit" to "https://www.reddit.com",
        "chatgpt" to "https://chatgpt.com", "drive" to "https://drive.google.com", "docs" to "https://docs.google.com",
        "sheets" to "https://sheets.google.com", "outlook" to "https://outlook.live.com", "telegram" to "https://web.telegram.org",
        "amazon" to "https://www.amazon.com", "wikipedia" to "https://www.wikipedia.org", "news" to "https://news.google.com",
        "spotify" to "https://open.spotify.com", "fiverr" to "https://www.fiverr.com", "upwork" to "https://www.upwork.com"
    )

    // site word -> search URL prefix
    private val searchSites: List<Pair<String, String>> = listOf(
        "youtube" to "https://www.youtube.com/results?search_query=",
        "yt" to "https://www.youtube.com/results?search_query=",
        "images" to "https://www.google.com/search?tbm=isch&q=",
        "pictures" to "https://www.google.com/search?tbm=isch&q=",
        "photos" to "https://www.google.com/search?tbm=isch&q=",
        "maps" to "https://www.google.com/maps/search/?api=1&query=",
        "amazon" to "https://www.amazon.com/s?k=",
        "wikipedia" to "https://en.wikipedia.org/w/index.php?search=",
        "wiki" to "https://en.wikipedia.org/w/index.php?search=",
        "spotify" to "https://open.spotify.com/search/",
        "github" to "https://github.com/search?q=",
        "reddit" to "https://www.reddit.com/search/?q=",
        "news" to "https://news.google.com/search?q=",
        "google" to "https://www.google.com/search?q="
    )
    private val searchVerbs = setOf("search", "find", "play", "watch", "listen", "show", "lookup", "look")
    private val stopWords = setOf(
        "search", "find", "play", "open", "show", "watch", "listen", "look", "up", "for", "on", "in", "at", "to",
        "me", "my", "please", "and", "then", "se", "ka", "ki", "ke", "ko"
    )
    private val langs = mapOf(
        "hindi" to "hi", "urdu" to "ur", "english" to "en", "spanish" to "es", "french" to "fr", "arabic" to "ar",
        "german" to "de", "punjabi" to "pa", "chinese" to "zh-CN", "japanese" to "ja", "turkish" to "tr", "russian" to "ru"
    )

    private val hotkeys: List<Pair<Regex, String>> = listOf(
        "show desktop|minimi[sz]e (all|everything)|go to desktop" to "SHOW_DESKTOP",
        "switch (window|app)s?|alt tab|next window" to "ALT_TAB",
        "task view|show (all )?windows|overview" to "TASK_VIEW",
        "close (this |the )?(window|app)\\b" to "CLOSE_WINDOW",
        "close (this |the )?tab" to "CLOSE_TAB",
        "\\bnew tab" to "NEW_TAB",
        "next tab" to "NEXT_TAB",
        "\\b(refresh|reload)\\b" to "REFRESH",
        "full ?screen" to "FULLSCREEN",
        "^copy( that| this| it)?$" to "COPY",
        "^paste( it| here| that)?$" to "PASTE",
        "^cut( that| this| it)?$" to "CUT",
        "select all" to "SELECT_ALL",
        "^undo( that)?$" to "UNDO",
        "^redo( that)?$" to "REDO",
        "^save( it| this| file)?$" to "SAVE",
        "\\bsnip|snipping|screen clip" to "SNIP",
        "^go back$|^back$" to "BACK",
        "maximi[sz]e (this |the )?(window|screen|app)|^maximi[sz]e$" to "MAXIMIZE",
        "minimi[sz]e (this |the )?(window|screen|app)|^minimi[sz]e$" to "MINIMIZE",
        "previous tab|last tab|pichhla tab" to "PREV_TAB",
        "(open |create |new )?new desktop|^new desktop$" to "NEW_DESKTOP",
        "next desktop|agla desktop" to "NEXT_DESKTOP",
        "previous desktop|last desktop|pichhla desktop" to "PREV_DESKTOP"
    ).map { Regex(it.first) to it.second }

    private fun has(t: String, pattern: String) = Regex(pattern).containsMatchIn(t)
    private fun enc(s: String) = URLEncoder.encode(s.trim(), "UTF-8")
    private fun pc(action: String, vararg args: Pair<String, String>) = JarvisIntent.Pc(action, args.toMap())
    private fun url(u: String) = pc("OPEN_URL", "url" to u)

    private fun fmt(x: Double): String =
        if (x == Math.floor(x) && Math.abs(x) < 1e15) x.toLong().toString()
        else String.format(Locale.US, "%.4f", x).trimEnd('0').trimEnd('.')

    /** Lowercase, Hindi/Hinglish -> English keywords, strip symbols, move a trailing verb to the front. */
    private fun normalize(original: String): String {
        var low = original.lowercase()
        for ((k, v) in dev) low = low.replace(k, " $v ")
        low = low.replace(" dot ", ".")
        low = low.replace(Regex("[^a-z0-9 .:/'+*-]"), " ")
        for ((k, v) in hinPhrases) low = low.replace(k, v)
        low = hinRegex.replace(low) { hinWords[it.value] ?: it.value }
        low = low.replace(Regex("\\s+"), " ").trim()
        val parts = low.split(" ")
        val verbs = setOf("open", "play", "search", "close")
        val leading = setOf("open", "play", "search", "close", "launch", "start", "run", "find", "show", "watch")
        // Hindi puts the verb last: "chrome open" -> "open chrome"
        if (parts.size >= 2 && parts.last() in verbs && parts.first() !in leading) {
            low = parts.last() + " " + parts.dropLast(1).joinToString(" ")
        }
        return low
    }

    private fun cleanQuery(t: String, extra: Set<String>): String {
        val q = t.split(" ").filter { it !in stopWords && it !in extra }.joinToString(" ")
        return q.replace(Regex("^of "), "")
    }

    /** Unit table for voice conversions: name -> (dimension, factor to base). */
    private val units: Map<String, Pair<String, Double>> = buildMap {
        listOf(
            "km" to 1000.0, "kilometer" to 1000.0, "kilometers" to 1000.0, "kilometre" to 1000.0, "kilometres" to 1000.0,
            "m" to 1.0, "meter" to 1.0, "meters" to 1.0, "metre" to 1.0, "metres" to 1.0,
            "cm" to 0.01, "centimeter" to 0.01, "centimeters" to 0.01, "centimetre" to 0.01, "centimetres" to 0.01,
            "mm" to 0.001, "millimeter" to 0.001, "millimeters" to 0.001, "millimetre" to 0.001, "millimetres" to 0.001,
            "mile" to 1609.344, "miles" to 1609.344, "yard" to 0.9144, "yards" to 0.9144,
            "feet" to 0.3048, "foot" to 0.3048, "ft" to 0.3048,
            "inch" to 0.0254, "inches" to 0.0254, "in" to 0.0254
        ).forEach { put(it.first, "length" to it.second) }
        listOf(
            "kg" to 1.0, "kilo" to 1.0, "kilos" to 1.0, "kilogram" to 1.0, "kilograms" to 1.0,
            "g" to 0.001, "gram" to 0.001, "grams" to 0.001,
            "lb" to 0.45359237, "lbs" to 0.45359237, "pound" to 0.45359237, "pounds" to 0.45359237,
            "oz" to 0.028349523125, "ounce" to 0.028349523125, "ounces" to 0.028349523125
        ).forEach { put(it.first, "mass" to it.second) }
        listOf(
            "litre" to 1.0, "litres" to 1.0, "liter" to 1.0, "liters" to 1.0, "l" to 1.0,
            "ml" to 0.001, "millilitre" to 0.001, "millilitres" to 0.001, "milliliter" to 0.001, "milliliters" to 0.001,
            "gallon" to 3.785411784, "gallons" to 3.785411784
        ).forEach { put(it.first, "volume" to it.second) }
    }

    /**
     * Splits off an "on phone / on mobile" routing marker so the SAME command
     * can target either device (user request: everything runs on the PC unless
     * "on phone" is said). Returns (routedToPhone, textWithoutMarker).
     */
    private fun splitRoute(text: String): Pair<Boolean, String> {
        val m = Regex("\\b(?:on|from|using|in) (?:the |my )?(phone|mobile|android|handset)\\b", RegexOption.IGNORE_CASE)
            .find(text) ?: return false to text
        val stripped = text.replace(m.value, " ").replace(Regex("\\s+"), " ").trim()
        return true to stripped
    }

    /**
     * Search URL for a bare host or site word: "youtube.com" / "fiverr" + a
     * query. Known sites use their own search page; anything else falls back
     * to a Google `site:` search so "open example.com and search pricing on
     * it" still lands ON that site instead of a generic results page.
     */
    private fun searchUrlForHost(rawTarget: String, query: String): String {
        var host = rawTarget.lowercase().trim().removePrefix("www.").removePrefix(".")
        if ('.' !in host) {
            // Bare word ("fiverr", "youtube") -> derive the host from the site table.
            sites[host]?.let { u ->
                Regex("^https?://(?:www\\.)?([^/]+)").find(u)?.groupValues?.get(1)?.let { host = it }
            }
        }
        val q = enc(query)
        return when {
            host == "youtube.com" || host == "youtu.be" -> "https://www.youtube.com/results?search_query=$q"
            host == "google.com" -> "https://www.google.com/search?q=$q"
            host.endsWith("amazon.com") -> "https://www.amazon.com/s?k=$q"
            host == "github.com" -> "https://github.com/search?q=$q"
            host.endsWith("wikipedia.org") -> "https://en.wikipedia.org/w/index.php?search=$q"
            host.endsWith("reddit.com") -> "https://www.reddit.com/search/?q=$q"
            host == "x.com" || host == "twitter.com" -> "https://x.com/search?q=$q"
            host == "facebook.com" -> "https://www.facebook.com/search/top?q=$q"
            host == "instagram.com" -> "https://www.instagram.com/explore/search/keywords/?q=$q"
            host == "linkedin.com" -> "https://www.linkedin.com/search/results/all/?keywords=$q"
            host == "netflix.com" -> "https://www.netflix.com/search?q=$q"
            host == "spotify.com" -> "https://open.spotify.com/search/$q"
            host == "chatgpt.com" || host == "chat.openai.com" -> "https://chatgpt.com/?q=$q"
            host == "fiverr.com" -> "https://www.fiverr.com/search/gigs?query=$q"
            host == "upwork.com" -> "https://www.upwork.com/nx/search/jobs/?q=$q"
            host == "stackoverflow.com" -> "https://stackoverflow.com/search?q=$q"
            host == "medium.com" -> "https://medium.com/search?q=$q"
            host == "duckduckgo.com" -> "https://duckduckgo.com/?q=$q"
            host == "bing.com" -> "https://www.bing.com/search?q=$q"
            host == "gmail.com" -> "https://mail.google.com/mail/u/0/#search/$q"
            host.isEmpty() -> "https://www.google.com/search?q=$q"
            else -> "https://www.google.com/search?q=$q+site%3A$host"
        }
    }

    /** Best-effort web target: known site -> its URL, raw URL -> https, else search. */
    private fun resolveWeb(target: String): String? {
        if (target.isBlank()) return null
        val low = target.lowercase().trim()
        sites.entries.firstOrNull { Regex("\\b" + Regex.escape(it.key) + "\\b").containsMatchIn(low) }?.let { return it.value }
        // Fuzzy site match: "fiver" -> fiverr, "you tube" won't false-hit.
        sites.entries.firstOrNull { it.key.length >= 4 && low.length >= 3 && (low.startsWith(it.key) || it.key.startsWith(low)) }?.let { return it.value }
        Regex("([a-z0-9-]+\\.)+[a-z]{2,}(/\\S*)?").find(low)?.let { return "https://" + it.value }
        return "https://www.google.com/search?q=" + enc(low)
    }

    fun parse(raw: String): JarvisIntent {
        val (onPhone, routed) = splitRoute(raw)
        val base = parseInternal(routed)
        if (!onPhone || base !is JarvisIntent.Pc) return base
        // Only open/search actions have a phone-side equivalent; hotkeys,
        // power, volume etc. stay PC-bound no matter what suffix is used.
        return when (base.action) {
            "LAUNCH_APP" -> (base.args["app"]?.let { JarvisIntent.PhoneOpen("APP", it, it) }) ?: base
            "OPEN_URL" -> (base.args["url"]?.let { JarvisIntent.PhoneOpen("URL", it, it) }) ?: base
            else -> base
        }
    }

    private fun parseInternal(raw: String): JarvisIntent {
        // keep the original (for typed/chat text), build a clean lowercase copy for matching
        val heard = raw.trim()
        // Wake word: "Veltrix" is the assistant's name ("Gulu Lulu" kept as an
        // alias) — it is stripped before parsing so "Veltrix open chrome" works
        // exactly like "open chrome".
        val original = heard
            .replace(Regex("^(?i)(hey |ok |okay |hi )?(vel[ -]?trix|weltrix|feltrix|gulu[ -]?lulu|gulu gulu|gulululu|gulu)[,.! ]*"), "")
            .replace(Regex("^(?i)(hey |ok |okay )?jarvis[,. ]*"), "")
            .replace(Regex("^(?i)please[ ,]*"), "")
            .trim()
        val t = normalize(original)
        // A bare wake word with nothing after it is a prompt, not a failure.
        if (t.isEmpty()) return if (heard.isNotEmpty()) JarvisIntent.Say("Yes?") else JarvisIntent.Unknown(raw)

        // ---- complex commands (checked BEFORE plain "write"/"open" so they
        //      are never swallowed as typed text or a generic launch) ----

        // WhatsApp: "send a whatsapp message to 0300 1234567 saying hello"
        //           / "whatsapp abu g and say hi" / "message abu g on whatsapp say ..."
        Regex("^(?:send|write|text|msg|message)?\\s*(?:a\\s+)?(?:whatsapp|whats ?app|wa)\\s+(?:message|msg|text)?\\s*(?:to\\s+)?(.+?)(?:\\s+(?:and\\s+)?(?:saying|say|with message|message|body|text)\\s*(?:is\\s+)?[:,]?\\s*(.+))?$", RegexOption.IGNORE_CASE)
            .find(original)?.let { m ->
                val to = m.groupValues[1].trim().removePrefix("to ").trim()
                val msg = m.groupValues[2].trim()
                if (to.isNotEmpty()) {
                    val num = to.filter { it.isDigit() }
                    val u = when {
                        num.length >= 7 -> "https://wa.me/" + num + (if (msg.isNotEmpty()) "?text=" + enc(msg) else "")
                        msg.isNotEmpty() -> "https://wa.me/?text=" + enc(msg)
                        else -> "https://web.whatsapp.com"
                    }
                    return url(u)
                }
            }

        // Email: "write an email to ali@example.com message how are you"
        Regex("^(?:write|compose|draft|send)(?:\\s+an?|\\s+the)?\\s+e-?mail\\s+(?:to\\s+)?(.+?)(?:\\s+(?:message|saying|say|body|with message|that says)\\s*(?:is\\s+)?[:,]?\\s*(.+))?$", RegexOption.IGNORE_CASE)
            .find(original)?.let { m ->
                val to = m.groupValues[1].trim().removePrefix("to ").trim()
                val body = m.groupValues[2].trim()
                if (to.isNotEmpty()) {
                    return url(
                        "https://mail.google.com/mail/?view=cm&fs=1&to=" + enc(to) +
                            (if (body.isNotEmpty()) "&body=" + enc(body) else "")
                    )
                }
            }

        // Chrome profile chain: "open fiverr in ali 2 chrome profile",
        // "open ali 3 chrome profile and open fiverr", "open my ali 2 chrome
        // profile". The page and the profile words are split apart so the site
        // AND the right profile land in ONE PC command (the old pattern opened
        // the profile but silently dropped the page).
        Regex("^(?:open|launch|start)(?:\\s+the|\\s+my)?\\s+(.*?)\\s+chrome\\s+profile(?:\\s+and\\s+(?:then\\s+)?(?:open|launch|show|go to)?\\s*(.+))?$", RegexOption.IGNORE_CASE)
            .find(original)?.let { m ->
                val head = m.groupValues[1].trim()
                val tail = m.groupValues[2].trim()
                    .removePrefix("open ").removePrefix("launch ").removePrefix("show ")
                    .removeSuffix(" in it").removeSuffix(" on it")
                    .removeSuffix(" in there").removeSuffix(" there").trim()
                // "fiverr in ali 2" -> page=fiverr, profile="ali 2";
                // no "in" -> the whole head is the profile, tail is the page.
                val inSplit = Regex("^(.*?)\\s+in\\s+(?:my\\s+|the\\s+)?(.+)$", RegexOption.IGNORE_CASE).find(head)
                val profileRaw = when {
                    inSplit != null -> inSplit.groupValues[2].trim()
                    else -> head.replace(Regex("^my\\s+", RegexOption.IGNORE_CASE), "").trim()
                }
                val pageRaw = when {
                    inSplit != null -> inSplit.groupValues[1].trim()
                    else -> tail
                }
                if (profileRaw.isNotEmpty() || tail.isNotEmpty()) {
                    val digits = Regex("\\d+").find(profileRaw)?.value ?: ""
                    val page = resolveWeb(pageRaw)
                    val args = mutableMapOf("app" to "chrome")
                    if (profileRaw.isNotEmpty()) args["profileName"] = profileRaw
                    if (digits.isNotEmpty()) args["profile"] = digits
                    if (page != null) args["url"] = page
                    return pc("LAUNCH_APP", *args.map { it.key to it.value }.toTypedArray())
                }
            }

        // Dial: "call abu g", "call 0300 1234567" (resolved from contacts on
        // the phone; the dialer always shows a confirm screen first).
        Regex("^call(?:\\s+(?:the|my)\\s+)?(.+)$").find(t)?.let { m ->
            val target = m.groupValues[1].trim()
            if (target.isEmpty() || target !in setOf("log", "history", "recorder", "of duty", "duty", "center", "option")) {
                return JarvisIntent.Call(target)
            }
        }

        // ---- text-carrying commands first (they must keep the user's own words) ----
        Regex("^(type|write)\\s+(.+)$", RegexOption.IGNORE_CASE).find(original)
            ?.let { return JarvisIntent.TypeText(it.groupValues[2].trim()) }
        Regex("^(send (a )?message( to (the )?pc)?|message( the)? (pc|computer)|tell (my |the )?(pc|computer))[: ]+(.+)$", RegexOption.IGNORE_CASE)
            .find(original)?.let { return JarvisIntent.Chat(it.groupValues.last().trim()) }

        // ---- small talk ----
        if (t.split(" ").size <= 4 && has(t, "^(hi|hello|hey|good (morning|afternoon|evening)|wake up)\\b")) return JarvisIntent.Greeting
        if (has(t, "\\bthank(s| you)\\b")) return JarvisIntent.Thanks
        if (has(t, "what can you do|^help$|who are you|your commands")) return JarvisIntent.Help
        if (has(t, "\\bjoke\\b|make me laugh")) return JarvisIntent.Joke

        // ---- calculator ----
        Regex("^(what is |what's |calculate |solve |how much is |compute )?(-?\\d+(?:\\.\\d+)?) ?(plus|\\+|minus|-|times|\\*|x|multiplied by|into|divided by|/|over) ?(-?\\d+(?:\\.\\d+)?)$")
            .find(t)?.let { m ->
                val a = m.groupValues[2].toDouble()
                val b = m.groupValues[4].toDouble()
                val result: Double
                val word: String
                when (m.groupValues[3]) {
                    "plus", "+" -> { result = a + b; word = "plus" }
                    "minus", "-" -> { result = a - b; word = "minus" }
                    "divided by", "/", "over" -> {
                        if (b == 0.0) return JarvisIntent.Say("I can't divide by zero.")
                        result = a / b; word = "divided by"
                    }
                    else -> { result = a * b; word = "times" }
                }
                return JarvisIntent.Say("${fmt(a)} $word ${fmt(b)} is ${fmt(result)}.")
            }

        // ---- percent: "what is 15 percent of 200" ----
        Regex("^(?:what (?:is|'s) )?(\\d+(?:\\.\\d+)?) percent of (\\d+(?:\\.\\d+)?)$").find(t)?.let {
            val p = it.groupValues[1].toDouble()
            val n = it.groupValues[2].toDouble()
            return JarvisIntent.Say("${fmt(p)} percent of ${fmt(n)} is ${fmt(p / 100.0 * n)}.")
        }

        // ---- random number: "random number between 1 and 50" ----
        Regex("^(?:random number|pick a number|give me a number|roll a number)(?: between| from)? (\\d+)(?: to| and|-| through) (\\d+)$")
            .find(t)?.let {
                val a = it.groupValues[2].toInt()
                val b = it.groupValues[3].toInt()
                val lo = minOf(a, b); val hi = maxOf(a, b)
                return JarvisIntent.Say("Between $lo and $hi: ${(lo..hi).random()}.")
            }

        // ---- unit conversion: "convert 5 km to miles" / "10 kg in pounds" ----
        Regex("^(?:convert )?(-?\\d+(?:\\.\\d+)?) ([a-z]+) (?:to|in|into) ([a-z]+)$").find(t)?.let { m ->
            val n = m.groupValues[1].toDouble()
            val from = units[m.groupValues[2]]
            val to = units[m.groupValues[3]]
            if (from != null && to != null) {
                if (from.first != to.first) {
                    return JarvisIntent.Say("I can't convert ${m.groupValues[2]} to ${m.groupValues[3]} — they measure different things.")
                }
                val result = n * from.second / to.second
                return JarvisIntent.Say("${fmt(n)} ${m.groupValues[2]} is ${fmt(result)} ${m.groupValues[3]}.")
            }
        }

        // ---- phone alarm: "set an alarm for 7:30 am", "wake me at 6 am" ----
        (Regex("^(?:set |create |add )?(?:an |the )?alarm(?: for| at)? (\\d{1,2})(?::(\\d{2}))? ?(am|pm)?$").find(t)
            ?: Regex("^wake me(?: up)? at (\\d{1,2})(?::(\\d{2}))? ?(am|pm)$").find(t))?.let { m ->
            val hRaw = m.groupValues[1].toIntOrNull()
            val min = (m.groupValues[2].toIntOrNull() ?: 0).coerceIn(0, 59)
            val suffix = m.groupValues[3]
            if (hRaw != null) {
                var h = hRaw
                if (suffix == "pm" && h in 1..11) h += 12
                if (suffix == "am" && hRaw == 12) h = 0
                if (h in 0..23) return JarvisIntent.Alarm(h, min)
            }
        }

        // ---- restart one app (NOT the PC): "restart chrome" must never reach POWER ----
        Regex("^(?:restart|relaunch|reopen)(?: the| my)? (.+)$").find(t)?.let { m ->
            apps.firstOrNull { has(m.groupValues[1], "\\b" + Regex.escape(it.first) + "\\b") && it.second in closable }
                ?.let { return JarvisIntent.RestartApp(it.second, it.first) }
        }

        // ---- switch back to an app: "switch to chrome", "bring up whatsapp" ----
        Regex("^(?:switch to|go back to|bring up|switch back to)(?: the| my)? (.+)$").find(t)?.let { m ->
            apps.firstOrNull { has(m.groupValues[1], "\\b" + Regex.escape(it.first) + "\\b") }
                ?.let { return pc("LAUNCH_APP", "app" to it.second) }
        }

        // ---- power ----
        if (has(t, "\\block\\b") && (has(t, "\\b(pc|computer|laptop|windows|screen|it|everything)\\b") || t == "lock"))
            return pc("POWER", "kind" to "LOCK")
        if (has(t, "\\b(sleep|hibernate)\\b") &&
            (has(t, "\\b(pc|computer|laptop)\\b") || has(t, "^(go to |pc |computer )?(sleep|hibernate)$")))
            return pc("POWER", "kind" to "SLEEP")
        if (has(t, "\\b(restart|reboot)\\b")) return pc("POWER", "kind" to "RESTART")
        if (has(t, "shut ?down|power off|turn off (the |my )?(pc|computer|laptop)|(pc|computer|laptop) off"))
            return pc("POWER", "kind" to "SHUTDOWN")

        // ---- screenshot ----
        if (has(t, "screen ?shot|capture (my |the )?screen|picture of (my |the )?screen")) return pc("SCREENSHOT")

        // ---- phone-local helpers ----
        if (has(t, "flash ?light|torch")) return JarvisIntent.Torch(!has(t, "\\boff\\b"))
        (Regex("timer (?:for )?(\\d+) ?(second|sec|minute|min|hour|hr)").find(t)
            ?: Regex("(\\d+) ?(second|sec|minute|min|hour|hr)s? timer").find(t))?.let { m ->
            val n = m.groupValues[1].toInt()
            val secs = when (m.groupValues[2]) { "hour", "hr" -> n * 3600; "minute", "min" -> n * 60; else -> n }
            return JarvisIntent.Timer(secs)
        }

        // ---- keyboard shortcuts, keys, mouse ----
        for ((re, name) in hotkeys) if (re.containsMatchIn(t)) return pc("HOTKEY", "name" to name)
        Regex("^press (enter|return|tab|escape|esc|backspace|space|up|down|left|right|home|end|delete|page ?up|page ?down|insert)$").find(t)?.let {
            val k = when (val g = it.groupValues[1]) {
                "return" -> "ENTER"; "esc" -> "ESCAPE"; "pageup" -> "PAGEUP"; "pagedown" -> "PAGEDOWN"
                else -> g.uppercase()
            }
            return JarvisIntent.Key(k)
        }
        if (has(t, "^double click$")) return JarvisIntent.Click("left", true)
        if (has(t, "^right click$")) return JarvisIntent.Click("right", false)
        if (has(t, "^(left )?click$")) return JarvisIntent.Click("left", false)
        Regex("^scroll (down|up)( a lot)?$").find(t)?.let {
            val amt = if (it.groupValues[2].isNotEmpty()) 20 else 8
            return JarvisIntent.Scroll(if (it.groupValues[1] == "down") amt else -amt)
        }

        // ---- the PHONE's own volume (only when the phone is named) ----
        Regex("^(?:phone|mobile|this phone|is phone) .{0,16}?(?:volume|sound|awaaz|avaz)(?: (up|down|mute|unmute))?(?: (?:to )?(\\d{1,3})%)?$").find(t)?.let { m ->
            val level = m.groupValues[3].toIntOrNull()
            val action = when {
                level != null -> "SET"
                m.groupValues[1] == "up" -> "UP"
                m.groupValues[1] == "down" -> "DOWN"
                m.groupValues[1] in setOf("mute", "unmute") -> "MUTE"
                else -> "UP"
            }
            return JarvisIntent.PhoneVolume(action, level ?: 0)
        }

        // ---- PC volume / brightness LEVEL: "volume 40", "set brightness to 60" ----
        Regex("^(?:(?:set|change) )?(?:the )?volume (?:to |at )?(\\d{1,3})%?$").find(t)?.let {
            return pc("VOLUME_SET", "level" to (it.groupValues[1].toInt().coerceIn(0, 100)).toString())
        }
        Regex("^(?:(?:set|change) )?(?:the )?brightness (?:to |at )?(\\d{1,3})%?$").find(t)?.let {
            return pc("BRIGHTNESS", "level" to (it.groupValues[1].toInt().coerceIn(0, 100)).toString())
        }

        // ---- media keys ----
        if (has(t, "volume up|louder|turn (it )?up|increase (the )?volume")) return JarvisIntent.Media("VOLUME_UP", "Volume up.")
        if (has(t, "volume down|quieter|turn (it )?down|lower (the )?volume|decrease (the )?volume")) return JarvisIntent.Media("VOLUME_DOWN", "Volume down.")
        if (has(t, "\\b(mute|unmute)\\b|volume off")) return JarvisIntent.Media("VOLUME_MUTE", "Toggled mute.")
        if (has(t, "next (song|track|video)|\\bskip\\b")) return JarvisIntent.Media("NEXT", "Next track.")
        if (has(t, "previous (song|track|video)|last (song|track)|go back a (song|track)")) return JarvisIntent.Media("PREV", "Previous track.")
        if (has(t, "^(pause|resume|play|stop)( the)?( music| songs?| video| playback)?$|^(music|songs?|video) (play|pause|off)$|stop (the )?(music|songs?|video)"))
            return JarvisIntent.Media("PLAY_PAUSE", "Done.")

        // ---- translate / weather / maps ----
        Regex("^translate (.+) (?:to|in|into) ([a-z]+)$").find(t)?.let {
            val code = langs[it.groupValues[2]]
            if (code != null) return url("https://translate.google.com/?sl=auto&tl=$code&text=${enc(it.groupValues[1])}&op=translate")
        }
        Regex("^(?:what's |what is )?(?:the )?weather(?: like)?(?: in| at| for)?(?: (.+))?$").find(t)?.let {
            val place = it.groupValues[1].trim()
            return url("https://www.google.com/search?q=" + enc(("weather $place").trim()))
        }
        Regex("^(?:navigate|directions?|route)(?: to)? (.+)$").find(t)?.let {
            return url("https://www.google.com/maps/dir/?api=1&destination=" + enc(it.groupValues[1]))
        }
        Regex("^where is (.+)$").find(t)?.let {
            return url("https://www.google.com/maps/search/?api=1&query=" + enc(it.groupValues[1]))
        }

        // ---- close an app ----
        Regex("^(?:close|quit|exit)(?: the| my)? (.+)$").find(t)?.let { m ->
            apps.firstOrNull { has(m.groupValues[1], "\\b" + Regex.escape(it.first) + "\\b") && it.second in closable }
                ?.let { return pc("CLOSE_APP", "app" to it.second) }
        }

        // ---- "search X on YouTube/Amazon/Wikipedia/..." in any word order ----
        val words = t.split(" ")
        var siteHit: Pair<String, String>? = null
        loop@ for (w in words) {
            for (s in searchSites) if (s.first == w) { siteHit = s; break@loop }
        }
        if (siteHit != null &&
            (words.any { it in searchVerbs } || words[0] == siteHit.first) &&
            !has(t, "^(open|launch|start|run) " + siteHit.first + "$")
        ) {
            val q = cleanQuery(t, searchSites.map { it.first }.toSet())
            if (q.isNotEmpty()) return url(siteHit.second + enc(q))
        }

        // "play despacito" -> YouTube search
        Regex("^play (.+)$").find(t)?.let {
            val q = cleanQuery(it.groupValues[1], emptySet())
            if (q.isNotEmpty() && q !in setOf("music", "song", "songs", "video", "it", "something"))
                return url("https://www.youtube.com/results?search_query=" + enc(q))
        }
        Regex("^(search (google )?for|search|google|look up) (.+)$").find(t)?.let {
            return url("https://www.google.com/search?q=" + enc(it.groupValues[3]))
        }

        // ---- "open X and search Y (on it)" / "search Y on X.com" ----
        // Checked before the plain open so the query is never dropped: the
        // site gets its OWN search page (Google `site:` fallback for the rest).
        Regex(
            "^(?:open|launch|go to|take me to)\\s+([a-z][a-z0-9.-]*)\\s+and\\s+(?:search|find|look for|look up|query)\\s+(.+?)(?:\\s+(?:on|in)\\s+(?:it|there|the (?:site|page)))?$",
            RegexOption.IGNORE_CASE
        ).find(original)?.let { m ->
            val q = m.groupValues[2].trim()
            if (q.isNotEmpty()) return url(searchUrlForHost(m.groupValues[1], q))
        }
        Regex(
            "^(?:search|find|look up|google)\\s+(.+?)\\s+on\\s+([a-z][a-z0-9.-]*)$",
            RegexOption.IGNORE_CASE
        ).find(original)?.let { m ->
            val q = m.groupValues[1].trim()
                .removeSuffix(" on it").removeSuffix(" in it").trim()
            if (q.isNotEmpty()) return url(searchUrlForHost(m.groupValues[2], q))
        }

        // ---- open / launch ----
        Regex("^(open|launch|start|run|go to|take me to)( the| my)? (.+)$").find(t)?.let { m ->
            val target = m.groupValues[3].trim()
            apps.firstOrNull { has(target, "\\b" + Regex.escape(it.first) + "\\b") }?.let { return pc("LAUNCH_APP", "app" to it.second) }
            sites.entries.firstOrNull { has(target, "\\b" + it.key + "\\b") }?.let { return url(it.value) }
            Regex("([a-z0-9-]+\\.)+[a-z]{2,}(/\\S*)?").find(target)?.let { return url("https://" + it.value) }
            return JarvisIntent.Unknown(raw)
        }

        // ---- info ----
        if (has(t, "\\bbattery\\b")) return JarvisIntent.PhoneBattery
        if (has(t, "what('s| is) the time|what time|time is it|\\bthe time\\b")) return JarvisIntent.TimeNow
        if (has(t, "what('s| is) (the )?date|what day|today's date")) return JarvisIntent.DateToday
        if (has(t, "^(?:what('s| is) )?(?:my |the )?(?:pc|computer|laptop)'?s? (?:name|hostname|called)|name of (?:my |the )?(?:pc|computer)")) return JarvisIntent.PcName
        if (has(t, "flip (a )?coin|toss (a )?coin|heads or tails"))
            return JarvisIntent.Say(if (java.util.Random().nextBoolean()) "Heads." else "Tails.")
        if (has(t, "roll (a |the )?(dice|die)|throw (a )?dice"))
            return JarvisIntent.Say("You rolled " + (1..6).random() + ".")
        if (has(t, "status|how is my (pc|computer)|how('s| is) (it|everything)|system|\\bcpu\\b|\\bram\\b|memory|performance|diagnostic"))
            return JarvisIntent.Status
        Regex("^(who is|who was|what is|what's|what are|tell me about|explain|define|meaning of) (.+)$").find(t)?.let {
            return url("https://www.google.com/search?q=" + enc(it.groupValues[2]))
        }

        // ---- last resort: free-form speech ----
        // Anything that is not a recognised command used to fall straight to
        // "Apologies, I didn't catch that", which made the assistant feel like it
        // knew four tricks. Two or more words is a real request: small talk gets
        // a spoken reply, everything else is looked up on the PC's browser so a
        // sentence like "best laptops under 80 thousand" still does something
        // useful instead of apologising.
        val spoken = t.trim()
        if (spoken.split(" ").count { it.isNotBlank() } >= 2) {
            if (has(t, "how are you|how('s| is) it going|what('s| is) up|kaise ho|kya haal"))
                return JarvisIntent.Say("Running at full capacity. What are we building?")
            if (has(t, "what can you do|what do you do|commands|capabilities|help me|madad"))
                return JarvisIntent.Help
            if (has(t, "good ?night|goodbye|bye|see you|shutdown yourself"))
                return JarvisIntent.Say("Standing by. Call me when you need me.")
            if (has(t, "who are you|your name|tum kaun"))
                return JarvisIntent.Say("I'm Jarvis — your DuoLink assistant. I run your PC and this phone by voice.")
            return url("https://www.google.com/search?q=" + enc(spoken))
        }

        return JarvisIntent.Unknown(raw)
    }
}
