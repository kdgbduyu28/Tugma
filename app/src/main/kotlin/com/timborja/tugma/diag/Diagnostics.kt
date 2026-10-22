package com.timborja.tugma.diag

import android.content.Context
import android.os.Build
import com.timborja.tugma.AppContainer
import com.timborja.tugma.BuildConfig
import com.timborja.tugma.core.Brand
import com.timborja.tugma.core.LogEntry
import com.timborja.tugma.data.MulticastLockDiscoverer
import com.timborja.tugma.upnp.SsdpDiscoverer
import com.timborja.tugma.upnp.SsdpResponse

enum class CheckStatus(val mark: String) { PASS("OK"), WARN("WARN"), FAIL("FAIL"), SKIP("SKIP") }

data class Check(val name: String, val status: CheckStatus, val detail: String, val hint: String? = null, val millis: Long = 0)

/** What an `ssdp:all` search saw, reduced to what the hints need. */
data class SsdpSummary(val responses: Int, val hosts: Int, val sonosHosts: Int, val servers: List<String>) {
    companion object {
        fun of(r: List<SsdpResponse>): SsdpSummary {
            val byHost = r.groupBy { it.host }
            val sonos = byHost.count { (_, rs) -> rs.any { it.server?.contains("Sonos", ignoreCase = true) == true || it.usn?.contains("RINCON_") == true } }
            return SsdpSummary(r.size, byHost.size, sonos, r.mapNotNull { it.server }.distinct().sorted().take(12))
        }
    }
}

/** Turns raw results into checks with plain-language hints. Pure, so it's unit-tested. */
object DiagnosticRules {
    fun network(onLan: Boolean) =
        if (onLan) Check("Network", CheckStatus.PASS, "Connected to Wi-Fi or Ethernet")
        else Check("Network", CheckStatus.FAIL, "Not on Wi-Fi", "Speakers are only reachable on the same local network. Mobile data and most guest Wi-Fi networks can't see them.")

    fun ssdp(s: SsdpSummary, millis: Long) = when {
        s.hosts == 0 -> Check(
            "Local discovery (SSDP)", CheckStatus.FAIL, "Nothing answered an ssdp:all search",
            "The router may block multicast (\"AP/client isolation\", \"IGMP snooping\", a separate 5 GHz or IoT network), " +
                "or Wi-Fi power saving drops it. Put the phone on the speakers' network, or add a speaker by IP on the Speakers tab.",
            millis,
        )
        else -> Check("Local discovery (SSDP)", CheckStatus.PASS, "${s.hosts} devices answered (${s.responses} replies)", millis = millis)
    }

    fun source(brand: Brand, found: Int, error: String?, ssdp: SsdpSummary?, millis: Long): Check {
        val name = "${brand.displayName} speakers"
        return when {
            error != null && found == 0 -> Check(name, CheckStatus.FAIL, error, hintFor(brand, ssdp), millis)
            found > 0 -> Check(name, if (error != null) CheckStatus.WARN else CheckStatus.PASS, "$found found" + (error?.let { " ($it)" } ?: ""), millis = millis)
            brand == Brand.SONOS && (ssdp?.sonosHosts ?: 0) > 0 -> Check(
                name, CheckStatus.WARN, "SSDP saw ${ssdp!!.sonosHosts} Sonos devices, but none loaded",
                "The speakers answered discovery but not port 1400. A firewall or VPN on the phone may be blocking it. Open the Debug log to see the failing request.",
                millis,
            )
            else -> Check(name, CheckStatus.SKIP, "None on this network", millis = millis)
        }
    }

    private fun hintFor(brand: Brand, ssdp: SsdpSummary?): String = when (brand) {
        Brand.SONOS -> if ((ssdp?.sonosHosts ?: 0) > 0) "Sonos answered discovery but not the topology request. Check the Debug log." else "No Sonos replies. Try adding one speaker by IP (Sonos app → Settings → System → About)."
        Brand.CAST -> "Cast devices are found with mDNS. Some routers block it between Wi-Fi bands."
        Brand.LINKPLAY -> "Linkplay speakers are found through DLNA discovery, then probed over HTTP and HTTPS."
        Brand.UPNP -> "Generic renderers answer SSDP; if SSDP found nothing, this can't either."
    }

    fun report(meta: List<Pair<String, String>>, checks: List<Check>, log: List<LogEntry>, servers: List<String>): String = buildString {
        appendLine("Tugma diagnostics")
        meta.forEach { (k, v) -> appendLine("$k: $v") }
        appendLine()
        checks.forEach { c ->
            appendLine("[${c.status.mark}] ${c.name}: ${c.detail}" + if (c.millis > 0) " (${c.millis} ms)" else "")
            c.hint?.let { appendLine("      $it") }
        }
        if (servers.isNotEmpty()) {
            appendLine()
            appendLine("SSDP server strings:")
            servers.forEach { appendLine("  $it") }
        }
        val failures = log.filter { it.isError }.take(15)
        if (failures.isNotEmpty()) {
            appendLine()
            appendLine("Recent failed requests:")
            // URLs only (LAN addresses); bodies stay out of shared reports.
            failures.forEach { appendLine("  ${it.method} ${it.url} → ${it.status ?: "-"} ${it.error.orEmpty()}") }
        }
    }
}

/** Runs the checks against the real network, reporting progress as each one finishes. */
class DiagnosticsRunner(private val context: Context, private val c: AppContainer) {
    var lastSsdp: SsdpSummary? = null
        private set

    suspend fun run(onProgress: (List<Check>) -> Unit): List<Check> {
        val out = mutableListOf<Check>()
        fun add(check: Check) { out += check; onProgress(out.toList()) }

        add(DiagnosticRules.network(c.network.isOnLanNow()))
        if (c.settings.demoMode.value) {
            add(Check("Demo household", CheckStatus.WARN, "Demo mode is on", "Turn it off in About to test your real speakers."))
            return out
        }

        val t0 = System.currentTimeMillis()
        val ssdp = runCatching { SsdpSummary.of(MulticastLockDiscoverer(context, SsdpDiscoverer("ssdp:all")).search()) }
        lastSsdp = ssdp.getOrNull()
        add(
            ssdp.fold(
                onSuccess = { DiagnosticRules.ssdp(it, System.currentTimeMillis() - t0) },
                onFailure = { Check("Local discovery (SSDP)", CheckStatus.FAIL, it.message ?: "Search failed", "The phone couldn't open a UDP socket; check that Tugma isn't restricted by a firewall app.") },
            ),
        )

        for (source in c.sources) {
            val t = System.currentTimeMillis()
            runCatching { source.refresh() }
            val st = source.summaries.value
            add(DiagnosticRules.source(source.brand, st.speakers.count { !it.hidden }, st.error, lastSsdp, System.currentTimeMillis() - t))
        }
        return out
    }

    fun report(checks: List<Check>): String = DiagnosticRules.report(
        listOf(
            "App" to "${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
            "Android" to "${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            "Device" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "Speakers added by IP" to c.settings.manualHosts.value.size.toString(),
        ),
        checks,
        c.log.entries.value,
        lastSsdp?.servers.orEmpty(),
    )
}
