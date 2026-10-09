package com.custom.keyboard.launcher

import android.content.Context
import android.content.pm.ApplicationInfo
import android.os.Build

/**
 * What kind of app something is, from the category the app declares in its manifest, with a fallback on well-known words in its name and package. Used to name
 * folders and stacks automatically and to sort Start into groups.
 */
object AppCategories {

    const val SOCIAL = "Social"
    const val GAMES = "Games"
    const val MUSIC = "Music"
    const val VIDEO = "Video"
    const val PHOTOS = "Photos"
    const val NEWS = "News"
    const val MAPS = "Travel"
    const val WORK = "Work"
    const val TOOLS = "Tools"
    const val SHOPPING = "Shopping"
    const val MONEY = "Money"
    const val HEALTH = "Health"
    const val LEARNING = "Learning"
    const val SYSTEM = "System"

    private val cache = HashMap<String, String?>()

    /** Words that give an app's kind away when it declares no category. Checked in order. */
    private val keywords: List<Pair<String, List<String>>> = listOf(
        SOCIAL to listOf("whatsapp", "telegram", "messenger", "message", "sms", "chat", "signal", "instagram", "facebook", "katana", "twitter", "snapchat", "discord", "reddit", "linkedin", "tiktok", "musically", "threads", "viber", "skype", "wechat", "line.android", "mms"),
        GAMES to listOf("game", "games", "play.games", "pubg", "freefire", "minecraft", "roblox", "clash", "candy", "chess"),
        MUSIC to listOf("music", "spotify", "soundcloud", "audio", "podcast", "radio", "deezer", "gaana", "jiosaavn", "wynk", "shazam"),
        VIDEO to listOf("youtube", "netflix", "video", "primevideo", "hotstar", "player", "tv", "twitch", "vlc", "mxtech", "jiocinema", "zee5"),
        PHOTOS to listOf("camera", "photo", "gallery", "snapseed", "lightroom", "picsart", "lens", "pictures"),
        NEWS to listOf("news", "flipboard", "inshorts", "magazine"),
        MAPS to listOf("maps", "uber", "olacabs", "lyft", "airbnb", "booking", "travel", "transit", "waze", "rapido", "irctc", "flight", "railway"),
        WORK to listOf("mail", "gmail", "outlook", "office", "docs", "sheets", "slides", "drive", "calendar", "teams", "slack", "zoom", "meet", "notion", "excel", "powerpoint", "onedrive", "dropbox", "keep", "evernote", "todo", "tasks"),
        SHOPPING to listOf("amazon", "flipkart", "shop", "myntra", "meesho", "ebay", "aliexpress", "swiggy", "zomato", "blinkit", "zepto", "bigbasket"),
        MONEY to listOf("bank", "pay", "upi", "wallet", "paytm", "phonepe", "gpay", "paisa", "finance", "money", "crypto", "trading", "zerodha", "groww"),
        HEALTH to listOf("fit", "health", "workout", "strava", "steps", "yoga", "meditat", "calm", "headspace"),
        LEARNING to listOf("learn", "duolingo", "classroom", "byju", "unacademy", "coursera", "udemy", "khan", "dictionary", "translate"),
        TOOLS to listOf("calculator", "clock", "files", "file", "recorder", "scanner", "flashlight", "compass", "notes", "weather", "contacts", "dialer", "phone", "browser", "chrome", "firefox", "opera", "brave", "edge"),
        SYSTEM to listOf("settings", "android.vending", "playstore", "security", "cleaner", "launcher", "keyboard")
    )

    /** Category name for an app, or null when nothing gives it away. */
    fun of(context: Context, packageName: String?, label: String = ""): String? {
        packageName ?: return null
        cache[packageName]?.let { return it }
        if (cache.containsKey(packageName)) return null
        val declared = try {
            fromDeclared(context.packageManager.getApplicationInfo(packageName, 0).category)
        } catch (_: Exception) {
            null
        }
        val result = declared ?: guess(packageName, label)
        cache[packageName] = result
        return result
    }

    private fun fromDeclared(category: Int): String? = when (category) {
        ApplicationInfo.CATEGORY_GAME -> GAMES
        ApplicationInfo.CATEGORY_AUDIO -> MUSIC
        ApplicationInfo.CATEGORY_VIDEO -> VIDEO
        ApplicationInfo.CATEGORY_IMAGE -> PHOTOS
        ApplicationInfo.CATEGORY_SOCIAL -> SOCIAL
        ApplicationInfo.CATEGORY_NEWS -> NEWS
        ApplicationInfo.CATEGORY_MAPS -> MAPS
        ApplicationInfo.CATEGORY_PRODUCTIVITY -> WORK
        else -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && category == ApplicationInfo.CATEGORY_ACCESSIBILITY) TOOLS else null
    }

    /** Category from words in the package or label; pure so it can be unit-tested. */
    fun guess(packageName: String, label: String): String? {
        val haystack = (packageName + " " + label).lowercase()
        return keywords.firstOrNull { (_, words) -> words.any { it in haystack } }?.first
    }

    /**
     * A name for a set of apps: their shared category when most of them have one, else a word
     * their names share ("Google"), else [fallback].
     */
    fun nameFor(categories: List<String?>, labels: List<String>, fallback: String = "Apps"): String {
        val known = categories.filterNotNull()
        val top = known.groupingBy { it }.eachCount().maxByOrNull { it.value }
        // Half or more of the apps share a category.
        if (top != null && top.value * 2 >= categories.size) return top.key
        // A brand most names start with, like "Google Maps", "Google Photos".
        val firstWords = labels.mapNotNull { it.trim().split(' ').firstOrNull()?.takeIf { w -> w.length >= 3 } }
        val brand = firstWords.groupingBy { it.lowercase() }.eachCount().maxByOrNull { it.value }
        if (brand != null && brand.value >= 2 && brand.value * 2 >= labels.size) {
            return firstWords.first { it.lowercase() == brand.key }
        }
        if (top != null && top.value >= 2) return top.key
        return fallback
    }

    /** Suggested names to offer when renaming a folder or group. */
    fun suggestions(categories: List<String?>): List<String> {
        val ranked = categories.filterNotNull().groupingBy { it }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
        return (ranked + listOf(WORK, SOCIAL, GAMES, TOOLS, "Favourites")).distinct().take(6)
    }
}
