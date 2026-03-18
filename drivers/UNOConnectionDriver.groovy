metadata {
    definition(name: "UNO Connection", namespace: "uno", author: "Eric Hess") {
        capability "Initialize"
        capability "Refresh"
        capability "Actuator"
        capability "Sensor"

        attribute "connectionState", "string"
        attribute "alarmSystemStatus", "string"
        attribute "troubleState", "string"
        attribute "panelModel", "string"
        attribute "firmwareVersion", "string"
        attribute "panelTime", "string"
        attribute "panelMac", "string"
        attribute "lastAck", "string"
        attribute "lastSystemMessage", "string"
        attribute "lastRawMessage", "string"
        attribute "zoneBitmapLength", "number"
        attribute "lastHeartbeat", "string"
        attribute "lastCidEvent", "string"

        command "armStay"
        command "armAway"
        command "disarm", [[name: "Master Code", type: "STRING"]]
        command "reconnect"
        command "refreshAll"
        command "login"
        command "clearCommandQueue"
        command "poll"
    }

    preferences {
        input name: "ip", type: "text", title: "UNO IP Address", required: true
        input name: "port", type: "number", title: "UNO Port", defaultValue: 4025, required: true
        input name: "password", type: "text", title: "UNO TPI Password", required: true
        input name: "masterCode", type: "text", title: "UNO Master Code", required: false
        input name: "zoneCount", type: "number", title: "Configured Zone Count", defaultValue: 27, required: true
        input name: "debugLogging", type: "bool", title: "Enable debug logging", defaultValue: false
        input name: "traceLogging", type: "bool", title: "Enable trace logging", defaultValue: false
        input name: "heartbeatMinutes", type: "enum", title: "Heartbeat interval", options: ["1":"Every 1 minute", "5":"Every 5 minutes", "10":"Every 10 minutes"], defaultValue: "1", required: true
        input name: "watchdogMinutes", type: "enum", title: "Reconnect if no frames seen for", options: ["3":"3 minutes", "5":"5 minutes", "10":"10 minutes"], defaultValue: "3", required: true
    }
}

def installed() {
    logInfo("Installed")
    initialize()
}

def updated() {
    logInfo("Updated")
    unschedule()
    initialize()
}

def initialize() {
    state.rxBuffer = ""
    state.commandQueue = []
    state.commandInFlight = null
    state.lastZoneBitmap = null
    state.lastPartitionBitmap = null
    state.lastBypassBitmap = null
    state.lastTroubleBitmap = null
    state.loggedIn = false
    state.loginSent = false
    state.lastFrameTs = 0L
    state.lastCidByZone = [:]
    state.lastCidEvent = ""

    sendEvent(name: "connectionState", value: "disconnected")
    if (!device.currentValue("alarmSystemStatus")) sendEvent(name: "alarmSystemStatus", value: "unknown")
    if (!device.currentValue("troubleState")) sendEvent(name: "troubleState", value: "unknown")

    scheduleHealthChecks()
    reconnect()
}

private void scheduleHealthChecks() {
    String hb = (settings.heartbeatMinutes ?: "1").toString()

    if (hb == "1") {
        runEvery1Minute("heartbeatCheck")
    } else if (hb == "5") {
        runEvery5Minutes("heartbeatCheck")
    } else {
        runEvery10Minutes("heartbeatCheck")
    }

    runEvery30Minutes("watchdogCheck")
    logDebug("Scheduled heartbeat=${hb}m watchdog=${settings.watchdogMinutes ?: '3'}m")
}

def reconnect() {
    try {
        interfaces.rawSocket.close()
    } catch (ignored) {
    }

    state.loggedIn = false
    state.loginSent = false
    state.commandInFlight = null

    Integer p = safePort()
    logInfo("Opening raw socket to ${settings.ip}:${p}")
    sendEvent(name: "connectionState", value: "connecting")

    try {
        interfaces.rawSocket.connect(settings.ip, p, byteInterface: false)
    } catch (Exception e) {
        log.error "Socket connect failed: ${e}"
        sendEvent(name: "connectionState", value: "error")
    }
}

def socketStatus(String message) {
    logDebug("socketStatus: ${message}")

    String m = (message ?: "").toLowerCase()

    if (m.contains("connect")) {
        sendEvent(name: "connectionState", value: "connected")
        return
    }

    if (m.contains("close") || m.contains("disconnected") || m.contains("error") || m.contains("failure")) {
        logWarn("Socket disconnected: ${message}")
        sendEvent(name: "connectionState", value: "disconnected")
        state.loggedIn = false
        state.loginSent = false
        state.commandInFlight = null
        runIn(5, "reconnect")
    }
}

def login() {
    if (!settings.password) {
        log.warn "No UNO TPI password configured"
        return
    }

    logInfo("Sending UNO login password")
    state.loginSent = true
    rawSend(settings.password + "\r")
}

def refresh() {
    refreshAll()
}

def refreshAll() {
    logDebug("Sending refreshAll")
    rawSend(tpiCommand("0C", "") + "\r\n")
}

def poll() {
    logDebug("Sending poll")
    rawSend(tpiCommand("00", "") + "\r\n")
}

def armStay() {
    enqueueCommand("08", "1", "armStay")
}

def armAway() {
    enqueueCommand("09", "1", "armAway")
}

def disarm(String suppliedCode = null) {
    String code = suppliedCode ?: settings.masterCode
    if (!code) {
        log.warn "Disarm requested without master code"
        return
    }
    enqueueCommand("12", "1,${code}", "disarm")
}

def clearCommandQueue() {
    state.commandQueue = []
    state.commandInFlight = null
    logInfo("Cleared command queue")
}

def heartbeatCheck() {
    if (device.currentValue("connectionState") == "authenticated") {
        poll()
    }
}

def watchdogCheck() {
    long lastTs = (state.lastFrameTs ?: 0L) as Long
    Integer wdMinutes = safeInt(settings.watchdogMinutes, 3)

    if (device.currentValue("connectionState") != "authenticated") return
    if (lastTs == 0L) return

    long ageSeconds = (now() - lastTs) / 1000L
    if (ageSeconds > (wdMinutes * 60)) {
        logWarn("Watchdog reconnect: no frames for ${ageSeconds}s")
        reconnect()
    }
}

private Integer safePort() {
    try {
        return (settings.port ?: 4025) as Integer
    } catch (ignored) {
        return 4025
    }
}

private Integer safeInt(def val, Integer fallback) {
    try {
        return (val ?: fallback) as Integer
    } catch (ignored) {
        return fallback
    }
}

private Integer safeParseInt(String s, Integer fallback = 0) {
    try {
        return Integer.parseInt(s)
    } catch (ignored) {
        return fallback
    }
}

private Integer configuredZoneCount() {
    try {
        return (settings.zoneCount ?: 27) as Integer
    } catch (ignored) {
        return 27
    }
}

private String tpiCommand(String cc, String data = "") {
    return "^" + cc + "," + (data ?: "") + '$'
}

private void enqueueCommand(String cc, String data, String name) {
    state.commandQueue = state.commandQueue ?: []
    state.commandQueue << [cc: cc, data: (data ?: ""), name: name, ts: now()]
    logDebug("Queued ${name}")
    processQueue()
}

private void processQueue() {
    if (!state.loggedIn) {
        logDebug("Queue paused until login completes")
        return
    }

    if (state.commandInFlight) {
        logDebug("Queue paused; waiting for ack")
        return
    }

    if (!(state.commandQueue instanceof List) || state.commandQueue.isEmpty()) {
        return
    }

    def next = state.commandQueue.remove(0)
    state.commandInFlight = [
        cc     : next.cc,
        data   : next.data,
        name   : next.name,
        sentAt : now(),
        retries: 0
    ]

    String cmd = tpiCommand(next.cc as String, next.data as String)
    logInfo("Sending ${next.name}")
    rawSend(cmd + "\r\n")
    runIn(4, "commandTimeoutCheck")
}

def commandTimeoutCheck() {
    def inflight = state.commandInFlight
    if (!inflight) return

    long ageMs = now() - (inflight.sentAt as Long)
    if (ageMs < 3500L) return

    if ((inflight.retries as Integer) < 1) {
        inflight.retries = ((inflight.retries as Integer) + 1)
        inflight.sentAt = now()
        state.commandInFlight = inflight

        String retryCmd = tpiCommand(inflight.cc as String, inflight.data as String)
        logWarn("Retrying ${inflight.name}")
        rawSend(retryCmd + "\r\n")
        runIn(4, "commandTimeoutCheck")
    } else {
        logWarn("Command timeout: ${inflight.name}")
        sendEvent(name: "lastAck", value: "timeout:${inflight.name}")
        state.commandInFlight = null
        processQueue()
    }
}

private void rawSend(String msg) {
    if (settings.traceLogging) {
        log.debug "TX >>> ${msg}"
    }
    interfaces.rawSocket.sendMessage(msg)
}

def parse(String message) {
    if (message == null) return

    String decoded = decodeIncoming(message)
    if (decoded == null) return

    state.lastFrameTs = now()
    sendEvent(name: "lastHeartbeat", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))

    state.rxBuffer = (state.rxBuffer ?: "") + decoded

    if (!state.loggedIn && !state.loginSent && state.rxBuffer.contains("Login:")) {
        logInfo("UNO presented login prompt")
        sendEvent(name: "connectionState", value: "loginPrompt")
        login()
    }

    while (true) {
        String buffer = state.rxBuffer ?: ""
        int idxDollar = buffer.indexOf('$')
        int idxLf = buffer.indexOf('\n')
        int idxCr = buffer.indexOf('\r')

        List<Integer> candidates = [idxDollar, idxLf, idxCr].findAll { it >= 0 }
        if (!candidates) break

        int endIdx = candidates.min()
        String frame = buffer.substring(0, endIdx).trim()
        state.rxBuffer = buffer.substring(endIdx + 1)

        if (frame) parseFrame(frame)
    }
}

private String decodeIncoming(String message) {
    try {
        byte[] data = hubitat.helper.HexUtils.hexStringToByteArray(message)
        String decoded = new String(data)
        if (settings.traceLogging) {
            log.debug "RX HEX <<< ${message}"
            log.debug "RX TXT <<< ${decoded}"
        }
        return decoded
    } catch (ignored) {
        if (settings.traceLogging) {
            log.debug "RX RAW <<< ${message}"
        }
        return message
    }
}

private void parseFrame(String frame) {
    sendEvent(name: "lastRawMessage", value: frame.take(1024))

    if (frame.equalsIgnoreCase("OK")) {
        state.loggedIn = true
        state.loginSent = false
        logInfo("UNO login successful")
        sendEvent(name: "connectionState", value: "authenticated")
        rawSend(tpiCommand("0D", "") + "\r\n")
        rawSend(tpiCommand("0C", "") + "\r\n")
        processQueue()
        return
    }

    if (frame.equalsIgnoreCase("FAILED")) {
        state.loggedIn = false
        state.loginSent = false
        logWarn("UNO login failed")
        sendEvent(name: "connectionState", value: "auth_failed")
        return
    }

    if (frame.equalsIgnoreCase("Timed Out!") || frame.equalsIgnoreCase("Timed Out")) {
        state.loginSent = false
        logWarn("UNO login timed out")
        sendEvent(name: "connectionState", value: "loginTimedOut")
        return
    }

    if (frame.equalsIgnoreCase("Login:")) {
        logDebug("Ignoring login prompt frame after handling")
        return
    }

    if (frame.startsWith("^")) {
        parseAckFrame(frame)
        return
    }

    if (!frame.startsWith("%")) {
        logDebug("Ignoring non-frame message: ${frame}")
        return
    }

    List<String> parts = frame.substring(1).split(",", -1) as List<String>
    if (!parts || parts.isEmpty()) return

    String code = parts[0]
    switch (code) {
        case "01":
            handleZoneBitmap(parts)
            break
        case "02":
            handlePartitionFrame(parts)
            break
        case "03":
            handleCidEvent(parts)
            break
        case "04":
            handleBypassBitmap(parts)
            break
        case "05":
            handlePanelInfo(parts)
            break
        case "06":
            handleTroubleBitmap(parts)
            break
        case "09":
            handleSystemMessage(parts)
            break
        case "0A":
            handlePersistentSound(parts)
            break
        case "10":
            handleChime(parts)
            break
        default:
            logDebug("Unhandled UNO frame ${code}: ${parts}")
            break
    }
}

private void parseAckFrame(String frame) {
    String body = frame.substring(1)
    if (body.endsWith('$')) {
        body = body.substring(0, body.length() - 1)
    }

    List<String> parts = body.split(",", -1) as List<String>
    if (parts.size() < 2) return

    String cc = parts[0]
    String ee = parts[1]

    String ack = cc + "," + ee
    sendEvent(name: "lastAck", value: ack)
    logDebug("ACK: ${ack}")

    if (state.commandInFlight) {
        if (ee != "00" && ee != "0") {
            logWarn("Command returned code ${ee} for ${state.commandInFlight.name}")
        }
        state.commandInFlight = null
    }

    processQueue()
}

private void handleZoneBitmap(List<String> parts) {
    if (parts.size() < 2) return

    String bitmapHex = cleanPayload(parts[1]).toUpperCase()
    sendEvent(name: "zoneBitmapLength", value: bitmapHex.length())

    String previous = state.lastZoneBitmap
    state.lastZoneBitmap = bitmapHex

    Map<Integer, Boolean> currentMap = decodeZoneBitmap(bitmapHex)
    Map<Integer, Boolean> previousMap = previous ? decodeZoneBitmap(previous) : [:]

    int absoluteMax = currentMap.keySet()?.max() ?: configuredZoneCount()
    int loopMax = previous == null ? configuredZoneCount() : absoluteMax

    for (int zone = 1; zone <= loopMax; zone++) {
        boolean newState = currentMap.get(zone, false)
        boolean oldState = previousMap.get(zone, false)

        if (previous == null || newState != oldState) {
            boolean suppressDebug = false
            Long lastCidTs = null

            if (state.lastCidByZone instanceof Map) {
                lastCidTs = state.lastCidByZone["${zone}"] as Long
            }

            if (lastCidTs != null) {
                long ageMs = now() - lastCidTs
                if (ageMs >= 0 && ageMs <= 1500L) {
                    suppressDebug = true
                }
            }

            if (!suppressDebug) {
                logDebug("Zone ${zone} -> ${newState ? 'active/open' : 'inactive/closed'}")
            }

            parent?.upsertDiscoveredZone(zone, null, null)
            emitZoneFastEvent(zone, newState)
            routeZoneUpdate(zone, newState, newState ? "1" : "0")
        }
    }
}

private Map<Integer, Boolean> decodeZoneBitmap(String hex) {
    Map<Integer, Boolean> result = [:]
    if (!hex) return result

    int zoneNumber = 1
    for (int byteIndex = 0; byteIndex < hex.length(); byteIndex += 2) {
        if (byteIndex + 2 > hex.length()) break
        String byteHex = hex.substring(byteIndex, byteIndex + 2)
        int value = Integer.parseInt(byteHex, 16)

        for (int bit = 0; bit < 8; bit++) {
            boolean open = ((value >> bit) & 0x01) == 1
            result[zoneNumber] = open
            zoneNumber++
        }
    }

    return result
}

private void handlePartitionFrame(List<String> parts) {
    if (parts.size() < 2) return

    String bitmap = cleanPayload(parts[1]).toUpperCase()
    state.lastPartitionBitmap = bitmap

    String status = decodePartitionStatus(bitmap)
    if (status != device.currentValue("alarmSystemStatus")) {
        sendEvent(name: "alarmSystemStatus", value: status)
        logInfo("Alarm status -> ${status}")
    }

    parent?.notifyPartitionState(status)
}

private String decodePartitionStatus(String bitmap) {
    if (!bitmap || bitmap.length() < 2) return "unknown"

    String p1 = bitmap.substring(0, 2)
    switch (p1) {
        case "00": return "unused"
        case "01": return "disarmed"
        case "02": return "readyBypassed"
        case "03": return "notReady"
        case "04": return "armedStay"
        case "05": return "armedAway"
        case "08": return "exitDelay"
        case "09": return "armedAwayZeroEntry"
        case "0C": return "entryDelay"
        case "11": return "alarm"
        default:   return "unknown"
    }
}

private void handleCidEvent(List<String> parts) {
    if (parts.size() < 2) return

    String cid = cleanPayload(parts[1])
    if (!cid || cid.length() < 10) {
        logDebug("Ignoring malformed CID event: ${cid}")
        return
    }

    String qualifier = cid.substring(0, 1)
    String eventCode = cid.substring(1, 4)
    Integer partition = safeParseInt(cid.substring(4, 6), 0)
    Integer zone = safeParseInt(cid.substring(6, 9), 0)
    boolean isRestore = (qualifier == "3")
    boolean isEvent = (qualifier == "1")

    String summary = "CID q=${qualifier} code=${eventCode} partition=${partition} zoneOrUser=${zone}"
    state.lastCidEvent = summary
    sendEvent(name: "lastCidEvent", value: summary.take(1024))
    logDebug(summary)

    if (zone <= 0) return

    String suggestedType = suggestTypeFromCid(eventCode)
    parent?.upsertDiscoveredZone(zone, suggestedType, null)

    Set<String> fastPathZoneCodes = [
        "110", // fire
        "113", // burglary/alarm style
        "130", // perimeter
        "131", // interior
        "132", // 24-hour burglary
        "134", // entry/exit
        "137", // tamper
        "144", // sensor fault/tamper style
        "154", // water/leakage (common CID)
        "162"  // carbon monoxide
    ] as Set

    if (!fastPathZoneCodes.contains(eventCode)) return
    if (!(isEvent || isRestore)) return

    boolean active = isEvent && !isRestore

    state.lastCidByZone = state.lastCidByZone ?: [:]
    state.lastCidByZone["${zone}"] = now()

    logInfo("CID fast path: zone ${zone} -> ${active ? 'active/open' : 'restore/closed'} (code ${eventCode})")
    emitZoneFastEvent(zone, active)
    routeZoneUpdate(zone, active, active ? "1" : "0")
}

private String suggestTypeFromCid(String eventCode) {
    switch (eventCode) {
        case "110":
            return "smoke"
        case "154":
            return "water"
        case "162":
            return "co"
        default:
            return null
    }
}

private void handleBypassBitmap(List<String> parts) {
    if (parts.size() < 2) return
    String bitmap = cleanPayload(parts[1]).toUpperCase()
    state.lastBypassBitmap = bitmap
    logDebug("Bypass bitmap updated")
}

private void handlePanelInfo(List<String> parts) {
    if (parts.size() < 6) return

    String mac = cleanPayload(parts[1])
    String model = cleanPayload(parts[2])
    String fw = cleanPayload(parts[3]).trim()
    String defaultPartition = cleanPayload(parts[4])
    String panelTime = cleanPayload(parts[5])

    if (mac) sendEvent(name: "panelMac", value: mac)
    if (model) sendEvent(name: "panelModel", value: model)
    if (fw) sendEvent(name: "firmwareVersion", value: fw)
    if (panelTime) sendEvent(name: "panelTime", value: panelTime)

    logDebug("Panel info: model=${model}, fw=${fw}, partition=${defaultPartition}, mac=${mac}, time=${panelTime}")
}

private void handleTroubleBitmap(List<String> parts) {
    if (parts.size() < 2) return

    String bitmap = cleanPayload(parts[1]).toUpperCase()
    state.lastTroubleBitmap = bitmap

    String trouble = (bitmap == ("0" * bitmap.length())) ? "clear" : "trouble"
    if (trouble != device.currentValue("troubleState")) {
        sendEvent(name: "troubleState", value: trouble)
        logInfo("Trouble state -> ${trouble}")
    }
}

private void handleSystemMessage(List<String> parts) {
    String msg = parts.drop(1).join(",")
    sendEvent(name: "lastSystemMessage", value: msg.take(1024))
    logDebug("System message: ${msg}")
}

private void handlePersistentSound(List<String> parts) {
    logDebug("Persistent sound frame received")
}

private void handleChime(List<String> parts) {
    logDebug("Chime frame received")
}

private void emitZoneFastEvent(Integer zoneNum, boolean active) {
    try {
        String value = zoneNum + ":" + (active ? "open" : "closed")
        sendLocationEvent(
            name: "unoZoneEvent",
            value: value,
            descriptionText: "UNO zone ${zoneNum} ${active ? 'opened' : 'closed'}",
            isStateChange: true
        )
    } catch (e) {
        logDebug("Failed to emit unoZoneEvent for zone ${zoneNum}: ${e}")
    }
}

private void routeZoneUpdate(Integer zoneNum, boolean active, String digit) {
    String dni = "uno-zone-${zoneNum}"

    // Ensure the app has had a chance to create the device.
    parent?.upsertDiscoveredZone(zoneNum, null, null)

    def child = null
    try {
        child = parent?.getChildDevice(dni)
    } catch (ignored) {
    }

    if (!child) {
        try {
            child = parent?.getChildDevices()?.find { it.deviceNetworkId == dni }
        } catch (ignored) {
        }
    }

    if (!child) {
        logWarn("Zone ${zoneNum} device not found via parent app: ${dni}")
        return
    }

    updateZoneChild(child, zoneNum, active, digit)
}

private void updateZoneChild(child, Integer zoneNum, boolean active, String digit) {
    try {
        child.sendEvent(name: "zoneStateDigit", value: digit, isStateChange: true)
    } catch (ignored) {
    }

    if (hasCapabilitySafe(child, "ContactSensor")) {
        String value = active ? "open" : "closed"
        child.sendEvent(
            name: "contact",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} ${value}",
            isStateChange: true
        )
    }

    if (hasCapabilitySafe(child, "MotionSensor")) {
        String value = active ? "active" : "inactive"
        child.sendEvent(
            name: "motion",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} motion ${value}",
            isStateChange: true
        )
    }

    if (hasCapabilitySafe(child, "SmokeDetector")) {
        String value = active ? "detected" : "clear"
        child.sendEvent(
            name: "smoke",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} smoke ${value}",
            isStateChange: true
        )
    }

    if (hasCapabilitySafe(child, "CarbonMonoxideDetector")) {
        String value = active ? "detected" : "clear"
        child.sendEvent(
            name: "carbonMonoxide",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} carbon monoxide ${value}",
            isStateChange: true
        )
    }

    if (hasCapabilitySafe(child, "WaterSensor")) {
        String value = active ? "wet" : "dry"
        child.sendEvent(
            name: "water",
            value: value,
            type: "physical",
            descriptionText: "Zone ${zoneNum} water ${value}",
            isStateChange: true
        )
    }
}

private boolean hasCapabilitySafe(child, String cap) {
    try {
        return child.hasCapability(cap)
    } catch (ignored) {
        return false
    }
}

private String cleanPayload(String s) {
    return (s ?: "").replace('$', ' ').trim()
}

private void logInfo(String msg) {
    log.info msg
}

private void logWarn(String msg) {
    log.warn msg
}

private void logDebug(String msg) {
    if (settings.debugLogging) {
        log.debug msg
    }
}
