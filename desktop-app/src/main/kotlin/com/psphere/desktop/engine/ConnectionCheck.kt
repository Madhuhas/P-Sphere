package com.psphere.desktop.engine

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Works out why linked devices can't find or reach this computer, in plain words, with a fix
 * where there is one. The usual causes: Windows treats the network as Public and its firewall
 * blocks PSphere, the firewall was told to block PSphere, the Wi-Fi keeps devices apart (guest
 * and office networks), or the other device isn't on this network.
 */
object ConnectionCheck {
    enum class State { OK, WARN, FAIL, INFO }

    data class Step(val title: String, val detail: String, val state: State)

    enum class Fix(val label: String) {
        ALLOW("Allow PSphere"),
        MAKE_PRIVATE("Make this a private network"),
    }

    /** The answer at the top of the screen: what's wrong (or that all is well) and what to do. */
    data class Verdict(val title: String, val detail: String, val ok: Boolean, val fixes: List<Fix> = emptyList())

    data class Result(val verdict: Verdict, val steps: List<Step>, val at: Long = System.currentTimeMillis())

    /** A Windows network and how Windows treats it. */
    data class Network(val index: Int, val name: String, val category: String) {
        val isPublic: Boolean get() = category.equals("Public", ignoreCase = true)
    }

    enum class Firewall { ALLOWED, BLOCKED, NO_RULE, UNKNOWN }

    /** Runs every check. Takes a few seconds (it asks Windows and the linked devices); call off the UI thread. */
    fun run(): Result {
        val node = Engine.node
        val steps = ArrayList<Step>()
        val addresses = Discovery.localAddresses().map { it.hostAddress }
        if (addresses.isEmpty()) {
            val v = Verdict("This computer isn't on a network", "Connect to the same Wi-Fi as your other devices, then check again.", ok = false)
            return Result(v, listOf(Step("Network", "Not connected", State.FAIL)))
        }

        val windows = DesktopSystem.os == Os.WINDOWS
        val network = if (windows) windowsNetwork() else null
        steps += Step(
            "Network",
            (network?.name?.let { "\"$it\" · " } ?: "") + addresses.joinToString(", "),
            State.OK,
        )
        if (network != null) {
            steps += Step(
                "Network type",
                if (network.isPublic) "Public: Windows is careful with networks it doesn't know, and blocks programs that haven't been allowed"
                else "${network.category}: devices on it can see each other",
                if (network.isPublic) State.INFO else State.OK,
            )
        }

        val firewall = if (windows) firewallFor(exePath()) else Firewall.UNKNOWN
        if (windows) steps += when (firewall) {
            Firewall.ALLOWED -> Step("Windows Firewall", "PSphere is allowed to receive connections", State.OK)
            Firewall.BLOCKED -> Step("Windows Firewall", "PSphere is blocked (this happens if Windows asked once and Cancel was chosen)", State.FAIL)
            Firewall.NO_RULE -> Step("Windows Firewall", "PSphere hasn't been allowed to receive connections yet", State.WARN)
            Firewall.UNKNOWN -> Step("Windows Firewall", "Couldn't read the firewall settings", State.INFO)
        }

        val linked = Engine.linked()
        val online = Engine.onlineLinked()
        if (linked.isEmpty()) {
            steps += Step("Linked devices", "None yet", State.INFO)
        } else {
            steps += Step(
                "Linked devices on this network",
                if (online.isEmpty()) "None found" else online.keys.mapNotNull { node.device(it)?.name }.joinToString(", "),
                if (online.isEmpty()) State.WARN else State.OK,
            )
        }

        // Outgoing: can this computer reach its devices? Also at their last known address, because
        // finding devices by their announcements is the part some networks get in the way of.
        val candidates = linked.filter { it.id in online || it.lastHost != null }.map { it.id }
        val reachable = candidates.filter { node.canReach(it, online) }
        candidates.forEach { id ->
            val name = node.device(id)?.name ?: "A device"
            steps += when {
                id in reachable && id in online -> Step("Reach $name", "Connected", State.OK)
                id in reachable -> Step("Reach $name", "Connected at its last known address (it wasn't found by its announcements)", State.OK)
                id in online -> Step("Reach $name", "It's on this network but didn't answer", State.WARN)
                else -> Step("Reach $name", "Not at its last known address: it may be off, or on another network", State.INFO)
            }
        }

        // Incoming: ask a reachable device to connect back. That's what a firewall blocks.
        var incoming: Boolean? = null
        val port = node.serverPort
        for (id in reachable) {
            incoming = node.askToConnectBack(id, online, addresses, port) ?: continue
            val name = node.device(id)?.name ?: "Your device"
            steps += if (incoming == true) Step("$name can reach this computer", "Connected back", State.OK)
            else Step("$name can reach this computer", "It tried and was blocked", State.FAIL)
            break
        }
        if (incoming == null && linked.any { node.lastIncomingFrom(it.id) != null }) {
            incoming = true
            steps += Step("Your devices can reach this computer", "A linked device connected here recently", State.OK)
        } else if (incoming == null && reachable.isNotEmpty()) {
            val names = reachable.mapNotNull { node.device(it)?.name }.joinToString(", ")
            steps += Step("$names can reach this computer", "Couldn't test: update PSphere on $names to check this direction too", State.INFO)
        }

        return Result(verdict(firewall, network, linked.isNotEmpty(), online.isNotEmpty() || reachable.isNotEmpty(), incoming), steps)
    }

    private fun verdict(firewall: Firewall, network: Network?, hasLinked: Boolean, seesDevices: Boolean, incoming: Boolean?): Verdict {
        val fixes = buildList {
            add(Fix.ALLOW)
            if (network?.isPublic == true) add(Fix.MAKE_PRIVATE)
        }
        val where = network?.name?.let { "\"$it\"" } ?: "this network"
        return when {
            firewall == Firewall.BLOCKED -> Verdict(
                "Windows Firewall is blocking PSphere",
                "Your other devices can't connect to this computer, so backups to it can't start. Allow PSphere and Windows will ask you once for permission.",
                ok = false, fixes = fixes,
            )
            incoming == false -> Verdict(
                "Your devices can't reach this computer",
                (if (network?.isPublic == true) "Windows treats $where as a public network, and its firewall blocks PSphere. " else "Something on this computer blocks incoming connections. ") +
                    "This computer can reach your devices, but not the other way round.",
                ok = false, fixes = fixes,
            )
            firewall == Firewall.NO_RULE && incoming != true -> Verdict(
                "PSphere may be blocked here",
                "Windows hasn't allowed PSphere to receive connections" + (if (network?.isPublic == true) " on public networks like $where" else "") +
                    ". If your devices can't back up to this computer, allow PSphere.",
                ok = false, fixes = fixes,
            )
            hasLinked && !seesDevices -> Verdict(
                "No linked devices found on $where",
                "Open PSphere on your phone or tablet and check it's on the same Wi-Fi. If it is and still isn't found, this Wi-Fi may keep devices apart: that's common on guest and office networks. It will work again on your home Wi-Fi.",
                ok = false,
            )
            !hasLinked -> Verdict("Ready to link", "Nothing is blocking PSphere. Link a device to start backing up.", ok = true)
            incoming == true -> Verdict("Everything looks fine", "Your devices and this computer can reach each other.", ok = true)
            seesDevices -> Verdict(
                "This computer can reach your devices",
                "The other direction couldn't be tested, because your other devices need a newer PSphere for that. Update them and check again.",
                ok = true, fixes = if (firewall == Firewall.ALLOWED) emptyList() else fixes,
            )
            else -> Verdict("Everything looks fine", "Nothing is blocking PSphere here.", ok = true)
        }
    }

    /** The program Windows Firewall rules point at: the installed app, or the Java runtime when run from source. */
    fun exePath(): String = DesktopSystem.installedExe() ?: ProcessHandle.current().info().command().orElse("")

    /** The network this computer uses, as Windows sees it. Null if it couldn't be read. */
    fun windowsNetwork(): Network? {
        val out = powershell("Get-NetConnectionProfile | ForEach-Object { \"$(\$_.InterfaceIndex)|$(\$_.NetworkCategory)|$(\$_.Name)\" }") ?: return null
        return out.lines().mapNotNull { line ->
            val p = line.trim().split('|', limit = 3)
            if (p.size == 3) p[0].toIntOrNull()?.let { Network(it, p[2], p[1]) } else null
        }.firstOrNull()
    }

    /** Whether Windows Firewall lets [exe] receive connections on the current network. */
    fun firewallFor(exe: String): Firewall {
        if (exe.isBlank()) return Firewall.UNKNOWN
        // Windows only lets administrators query rules through PowerShell ("Access is denied"),
        // but the rules themselves are stored in the registry, which anyone can read.
        val out = runCatching {
            val p = ProcessBuilder("reg", "query", FIREWALL_RULES_KEY).redirectErrorStream(true).start()
            val text = p.inputStream.bufferedReader().readText()
            if (!p.waitFor(20, TimeUnit.SECONDS) || p.exitValue() != 0) null else text
        }.getOrNull() ?: return Firewall.UNKNOWN
        val rules = parseFirewallRules(out).filter { it.app.equals(exe, ignoreCase = true) && it.inbound && it.active }
        val profile = windowsNetwork()?.category ?: "Public"
        val relevant = rules.filter { it.covers(profile) }
        return when {
            relevant.any { !it.allow } -> Firewall.BLOCKED
            relevant.any { it.allow } -> Firewall.ALLOWED
            else -> Firewall.NO_RULE
        }
    }

    /** One firewall rule as Windows stores it: "v2.30|Action=Allow|Active=TRUE|Dir=In|Profile=Public|App=…|Name=…|". */
    internal data class FirewallRule(val allow: Boolean, val active: Boolean, val inbound: Boolean, val profiles: Set<String>, val app: String) {
        /** No Profile entry means the rule applies on every type of network. */
        fun covers(profile: String) = profiles.isEmpty() || profiles.any { it.equals(profile, true) } ||
            (profile.startsWith("Domain", true) && profiles.any { it.equals("Domain", true) })
    }

    internal fun parseFirewallRules(regOutput: String): List<FirewallRule> = regOutput.lines().mapNotNull { line ->
        val data = line.substringAfter("REG_SZ", "").trim().takeIf { it.startsWith("v2.") } ?: return@mapNotNull null
        val fields = data.split('|').mapNotNull { f -> f.split('=', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        fun one(k: String) = fields.firstOrNull { it.first.equals(k, true) }?.second
        FirewallRule(
            allow = one("Action").equals("Allow", true),
            active = one("Active").equals("TRUE", true),
            inbound = one("Dir").equals("In", true),
            profiles = fields.filter { it.first.equals("Profile", true) }.map { it.second }.toSet(),
            app = one("App") ?: return@mapNotNull null,
        )
    }

    private const val FIREWALL_RULES_KEY = """HKLM\SYSTEM\CurrentControlSet\Services\SharedAccess\Parameters\FirewallPolicy\FirewallRules"""

    /**
     * Applies [fix] with a Windows permission prompt (it changes system settings). Returns
     * false if it couldn't run or the prompt was declined.
     */
    /** What happened when a fix was applied: [error] says why it didn't work. */
    data class FixResult(val ok: Boolean, val error: String? = null)

    fun apply(fix: Fix): FixResult {
        val log = File.createTempFile("psphere-fix", ".log").apply { delete() }
        val logPath = log.absolutePath.replace("'", "''")
        val change = when (fix) {
            Fix.ALLOW -> {
                val exe = exePath().takeIf { it.isNotBlank() }?.replace("'", "''") ?: return FixResult(false, "Couldn't tell where PSphere is installed.")
                // Drop rules that block PSphere (left by a Cancel on Windows' own prompt) and any
                // earlier "PSphere" rule for it, so pressing Allow twice doesn't pile up copies; then allow it.
                "Get-NetFirewallApplicationFilter -Program '$exe' -ErrorAction SilentlyContinue | Get-NetFirewallRule | " +
                    "Where-Object { \$_.Action -eq 'Block' -or \$_.DisplayName -eq 'PSphere' } | Remove-NetFirewallRule; " +
                    "New-NetFirewallRule -DisplayName 'PSphere' -Description 'Lets your linked devices back up to this computer' -Direction Inbound -Program '$exe' -Action Allow -Profile Any -ErrorAction Stop | Out-Null"
            }
            Fix.MAKE_PRIVATE -> {
                val net = windowsNetwork() ?: return FixResult(false, "Couldn't read this computer's network settings.")
                "Set-NetConnectionProfile -InterfaceIndex ${net.index} -NetworkCategory Private -ErrorAction Stop"
            }
        }
        // The change runs as administrator and writes "OK" or the error to a log we can read back.
        val elevated = "try { $change; Set-Content -LiteralPath '$logPath' -Value 'OK' } catch { Set-Content -LiteralPath '$logPath' -Value (\"ERROR: \" + \$_.Exception.Message) }"
        val elevatedEncoded = java.util.Base64.getEncoder().encodeToString(elevated.toByteArray(Charsets.UTF_16LE))
        // Start-Process -Verb RunAs shows Windows' permission prompt; -Wait returns when the change is done.
        val launcher = "try { Start-Process powershell -Verb RunAs -Wait -WindowStyle Hidden -ArgumentList '-NoProfile','-EncodedCommand','$elevatedEncoded'; 'STARTED' } catch { 'DECLINED' }"
        return try {
            val out = powershell(launcher, timeoutSeconds = 180) ?: return FixResult(false, "Windows didn't respond in time.")
            if ("DECLINED" in out) return FixResult(false, "Windows didn't get permission. Try again and choose Yes.")
            val result = runCatching { log.readText().trim() }.getOrDefault("")
            when {
                result == "OK" -> FixResult(true)
                result.startsWith("ERROR:") -> FixResult(false, "Windows couldn't make the change: " + result.removePrefix("ERROR:").trim())
                else -> FixResult(false, "Windows didn't make the change.")
            }
        } finally {
            log.delete()
        }
    }

    /**
     * Runs [command] in PowerShell. It's passed encoded (Base64 of UTF-16LE), because Java's
     * Windows command line mangles the double quotes PowerShell commands are full of.
     */
    private fun powershell(command: String, timeoutSeconds: Long = 20): String? = runCatching {
        val encoded = java.util.Base64.getEncoder().encodeToString(command.toByteArray(Charsets.UTF_16LE))
        val p = ProcessBuilder("powershell", "-NoProfile", "-NonInteractive", "-EncodedCommand", encoded).redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        if (!p.waitFor(timeoutSeconds, TimeUnit.SECONDS)) { p.destroyForcibly(); return null }
        out
    }.getOrNull()
}
