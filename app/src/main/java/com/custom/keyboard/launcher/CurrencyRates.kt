package com.custom.keyboard.launcher

import android.content.Context
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Currency
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Real exchange rates for search answers, from open.er-api.com (free, no account), cached for
 * 12 hours. Rates are "units per 1 US dollar". Only fetched when you type a currency question.
 */
object CurrencyRates {
    private const val URL_LATEST = "https://open.er-api.com/v6/latest/USD"
    private const val MAX_AGE_MS = 12 * 3_600_000L
    private val io = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())
    @Volatile private var rates: Map<String, Double>? = null
    @Volatile private var fetchedAt = 0L
    @Volatile private var loading = false

    /** The phone's local currency, e.g. "INR" or "SAR". */
    fun home(): String = runCatching { Currency.getInstance(Locale.getDefault()).currencyCode }.getOrDefault("USD")

    /** Cached rates (possibly stale); starts a refresh when old. [onFresh] runs if new ones arrive. */
    fun get(context: Context, onFresh: () -> Unit): Map<String, Double>? {
        if (rates == null) load(context)
        if (System.currentTimeMillis() - fetchedAt > MAX_AGE_MS && !loading) refresh(context, onFresh)
        return rates
    }

    private fun load(context: Context) {
        val prefs = context.getSharedPreferences("currency_rates", Context.MODE_PRIVATE)
        val raw = prefs.getString("rates", null) ?: return
        rates = parse(raw)
        fetchedAt = prefs.getLong("at", 0L)
    }

    private fun refresh(context: Context, onFresh: () -> Unit) {
        loading = true
        val app = context.applicationContext
        io.execute {
            val body = runCatching {
                val c = URL(URL_LATEST).openConnection() as HttpURLConnection
                c.connectTimeout = 6000
                c.readTimeout = 6000
                c.inputStream.bufferedReader().use { it.readText() }.also { c.disconnect() }
            }.getOrNull()
            val parsed = body?.let { parse(it) }
            main.post {
                loading = false
                if (parsed != null && parsed.isNotEmpty()) {
                    rates = parsed
                    fetchedAt = System.currentTimeMillis()
                    app.getSharedPreferences("currency_rates", Context.MODE_PRIVATE).edit()
                        .putString("rates", body).putLong("at", fetchedAt).apply()
                    onFresh()
                }
            }
        }
    }

    private fun parse(json: String): Map<String, Double>? = runCatching {
        val o = JSONObject(json).getJSONObject("rates")
        o.keys().asSequence().associateWith { o.getDouble(it) }
    }.getOrNull()
}
