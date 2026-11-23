package com.psphere.backup

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.provider.Settings
import com.psphere.core.net.Knock

/**
 * Works out why linked devices can't find or reach this phone or tablet, in plain words. Android
 * has no firewall to fix, so the usual causes are: not on Wi-Fi, a VPN, the Wi-Fi keeping devices
 * apart (guest and office networks), PSphere not running on the other device, or the other
 * device (often a Windows computer) blocking connections.
 */
object ConnectionCheck {
    enum class State { OK, WARN, FAIL, INFO }

    data class Step(val title: String, val detail: String, val state: State)

    /** Opens a system screen where the user can fix something. */
    enum class Fix(val label: String, val action: String) {
        WIFI("Open Wi-Fi settings", Settings.ACTION_WIFI_SETTINGS),
        VPN("Open VPN settings", Settings.ACTION_VPN_SETTINGS),
    }

    /** The answer at the top of the screen: what's wrong (or that all is well) and what to do. */
    data class Verdict(val title: String, val detail: String, val ok: Boolean, val fixes: List<Fix> = emptyList())

    data class Result(val verdict: Verdict, val steps: List<Step>, val at: Long = System.currentTimeMillis())

    /** What kind of network this device is on. */
    data class Link(val wifi: Boolean, val vpn: Boolean)

    fun link(ctx: Context): Link {
        val cm = ctx.getSystemService(ConnectivityManager::class.java) ?: return Link(wifi = false, vpn = false)
        val all = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
        return Link(
            wifi = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || it.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) },
            vpn = all.any { it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) },
        )
    }

    fun open(ctx: Context, fix: Fix) {
        // From the app's own screen, so Back returns to PSphere.
        fun go(action: String) = ctx.startActivity(Intent(action).apply { if (ctx !is android.app.Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        runCatching { go(fix.action) }.onFailure { runCatching { go(Settings.ACTION_SETTINGS) } }
    }

    /** Runs every check. Takes a few seconds (it asks the linked devices); call off the main thread. */
    fun run(ctx: Context): Result {
        val node = Engine.node
        val steps = ArrayList<Step>()
        val net = link(ctx)
        val addresses = Engine.localAddresses()
        val thisDevice = deviceWord(ctx)

        if (!net.wifi || addresses.isEmpty()) {
            val v = Verdict(
                "This $thisDevice isn't on Wi-Fi",
                "PSphere works between devices on the same Wi-Fi. Connect to the Wi-Fi your other devices use, then check again.",
                ok = false, fixes = listOf(Fix.WIFI),
            )
            return Result(v, listOf(Step("Wi-Fi", "Not connected", State.FAIL)))
        }
        steps += Step("Wi-Fi", "Connected · " + addresses.joinToString(", "), State.OK)
        if (net.vpn) steps += Step("VPN", "A VPN is on. Some VPNs keep this $thisDevice from reaching devices on your Wi-Fi", State.WARN)

        val linked = Engine.linked()
        val online = Engine.onlineLinked()
        if (linked.isEmpty()) {
            steps += Step("Linked devices", "None yet", State.INFO)
        } else {
            steps += Step(
                "Linked devices on this Wi-Fi",
                if (online.isEmpty()) "None found" else online.keys.mapNotNull { node.device(it)?.name }.joinToString(", "),
                if (online.isEmpty()) State.WARN else State.OK,
            )
        }

        // Outgoing: can this device reach its devices? Also at their last known address, because
        // finding devices by their announcements is the part some networks get in the way of.
        val candidates = linked.filter { it.id in online || it.lastHost != null }.map { it.id }
        val reachable = candidates.filter { node.canReach(it, online) }
        // Devices that seem to be on this Wi-Fi but didn't answer, and what a plain knock said.
        val silent = LinkedHashMap<String, Knock>()
        candidates.forEach { id ->
            val name = node.device(id)?.name ?: "A device"
            steps += when {
                id in reachable && id in online -> Step("Reach $name", "Connected", State.OK)
                id in reachable -> Step("Reach $name", "Connected at its last known address (it wasn't found by its announcements)", State.OK)
                id in online -> {
                    val k = Knock.at(online.getValue(id)).also { silent[name] = it }
                    when (k) {
                        Knock.REFUSED -> Step("Reach $name", "$name is on this Wi-Fi, but PSphere isn't running on it", State.WARN)
                        Knock.GONE -> Step("Reach $name", "$name doesn't answer at all: it may be off or asleep", State.INFO)
                        Knock.SILENT -> Step("Reach $name", "$name didn't answer: PSphere may be closed on it, or something on it blocks PSphere", State.WARN)
                    }
                }
                else -> Step("Reach $name", "Not at its last known address: it may be off, or on another network", State.INFO)
            }
        }

        // Incoming: ask a reachable device to connect back.
        var incoming: Boolean? = null
        var backName = ""
        for (id in reachable) {
            incoming = node.askToConnectBack(id, online, addresses, node.serverPort) ?: continue
            backName = node.device(id)?.name ?: "Your device"
            steps += if (incoming == true) Step("$backName can reach this $thisDevice", "Connected back", State.OK)
            else Step("$backName can reach this $thisDevice", "It tried and couldn't get through", State.FAIL)
            break
        }
        val lastIn = linked.mapNotNull { d -> node.lastIncomingFrom(d.id)?.let { d.name to it } }.maxByOrNull { it.second }
        if (incoming == null && lastIn != null) {
            incoming = true
            val mins = ((System.currentTimeMillis() - lastIn.second) / 60_000).toInt()
            steps += Step("Your devices can reach this $thisDevice", "${lastIn.first} connected here " + (if (mins < 1) "just now" else if (mins == 1) "1 min ago" else "$mins min ago"), State.OK)
        } else if (incoming == null && reachable.isNotEmpty()) {
            val names = reachable.mapNotNull { node.device(it)?.name }.joinToString(", ")
            steps += Step("$names can reach this $thisDevice", "Couldn't test: update PSphere on $names to check this direction too", State.INFO)
        }

        // Receiving while PSphere is closed.
        val receives = node.state.backupLocation != null
        if (receives) {
            steps += if (Prefs.stayReady(ctx)) Step("Receiving backups", "Stay ready to receive is on, so devices can back up here any time", State.OK)
            else Step("Receiving backups", "Only while PSphere is open. Turn on Stay ready to receive in Settings to receive any time", State.INFO)
        }

        val v = when {
            linked.isEmpty() -> Verdict("Ready to link", "This $thisDevice is on Wi-Fi. Link a device to start backing up.", ok = true)
            incoming == false -> Verdict(
                "$backName can't connect back to this $thisDevice",
                "This $thisDevice can reach $backName, but not the other way round. " +
                    (if (net.vpn) "Try turning the VPN off. " else "") +
                    "Some Wi-Fi networks only let devices connect one way; it will work on your home Wi-Fi.",
                ok = false, fixes = if (net.vpn) listOf(Fix.VPN) else emptyList(),
            )
            silent.isNotEmpty() && reachable.isEmpty() -> silentVerdict(silent, thisDevice)
            online.isEmpty() && reachable.isEmpty() -> Verdict(
                "No linked devices found on this Wi-Fi",
                "Open PSphere on your other devices and check they're on the same Wi-Fi. On a computer, also open Settings › Check connection there. " +
                    (if (net.vpn) "A VPN on this $thisDevice can also get in the way. " else "") +
                    "If it still doesn't work, this Wi-Fi may keep devices apart: that's common on guest and office networks.",
                ok = false, fixes = if (net.vpn) listOf(Fix.VPN) else emptyList(),
            )
            incoming == true -> Verdict("Everything looks fine", "Your devices and this $thisDevice can reach each other.", ok = true)
            else -> Verdict(
                "This $thisDevice can reach your devices",
                "The other direction couldn't be tested, because your other devices need a newer PSphere for that. Update them and check again.",
                ok = true,
            )
        }
        return Result(v, steps)
    }

    private fun silentVerdict(silent: Map<String, Knock>, thisDevice: String): Verdict {
        val (name, knock) = silent.entries.firstOrNull { it.value == Knock.SILENT } ?: silent.entries.firstOrNull { it.value == Knock.REFUSED } ?: silent.entries.first()
        return when (knock) {
            Knock.REFUSED -> Verdict(
                "PSphere isn't running on $name",
                "$name is on this Wi-Fi, but PSphere isn't open on it. Open PSphere on $name, then check again.",
                ok = false,
            )
            Knock.GONE -> Verdict(
                "$name seems to be off or asleep",
                "It was on this Wi-Fi a moment ago but doesn't answer now. Wake it up, check PSphere is open on it, then check again.",
                ok = false,
            )
            Knock.SILENT -> Verdict(
                "$name isn't answering",
                "$name is on this Wi-Fi, but PSphere on it didn't answer. Check PSphere is open on $name. If it is, open PSphere › Settings › Check connection on $name: " +
                    "on a computer it finds and fixes what's blocking it (often Windows Firewall on a network Windows treats as public).",
                ok = false,
            )
        }
    }

    private fun deviceWord(ctx: Context): String =
        if (ctx.resources.configuration.smallestScreenWidthDp >= 600) "tablet" else "phone"
}
