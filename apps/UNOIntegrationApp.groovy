definition(
    name: "EyezOn UNO Integration",
    namespace: "uno",
    author: "Eric Hess",
    description: "Hubitat integration for the EyezOn UNO alarm system",
    category: "Safety & Security",
    iconUrl: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/icons/app-Coordinator.png",
    iconX2Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/icons/app-Coordinator@2x.png",
    iconX3Url: "https://raw.githubusercontent.com/hubitat/HubitatPublic/master/resources/icons/app-Coordinator@2x.png"
)

preferences { page(name: "mainPage") }

def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section("UNO Connection") {
            input "ip", "text", title: "UNO IP Address", required: true
            input "port", "number", title: "UNO Port", defaultValue: 4025, required: true
            input "password", "text", title: "UNO Local/TPI Password", required: true
            input "masterCode", "text", title: "Master Code (optional, for disarm)", required: false
        }
        section("Alarm Device") {
            input "partitionLabel", "text", title: "Alarm device name", description: "Friendly name for the partition/alarm device", defaultValue: "House Alarm", required: false
        }
        section("Zone Discovery") {
            input "zoneCount", "number", title: "Configured Zone Count", defaultValue: 27, required: true
            input "autoCreateConfiguredZones", "bool", title: "Pre-create zones 1..Zone Count at install", defaultValue: true
            input "defaultUnknownZoneType", "enum", title: "Default type for unknown zones", options: ["contact":"Contact","motion":"Motion","smoke":"Smoke","co":"CO","water":"Water"], defaultValue: "contact", required: true
        }
        section("Zone Hints (optional but recommended)") {
            input "zoneHints", "textarea", title: "Zone Hints", required: false
        }
        section("Logging / Health") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
            input "traceLogging", "bool", title: "Enable trace logging", defaultValue: false
            input "heartbeatMinutes", "enum", title: "Heartbeat interval", options: ["1":"Every 1 minute", "5":"Every 5 minutes", "10":"Every 10 minutes"], defaultValue: "1", required: true
            input "watchdogMinutes", "enum", title: "Reconnect if no frames seen for", options: ["3":"3 minutes", "5":"5 minutes", "10":"10 minutes"], defaultValue: "3", required: true
        }
    }
}

def installed() { initialize() }
def updated() { initialize() }

def initialize() {
    state.zoneHintsMap = parseZoneHints(settings.zoneHints)
    ensureConnectionDevice()
    ensurePartitionDevice()
    if (settings.autoCreateConfiguredZones != false) {
        Integer maxZones = safeInt(settings.zoneCount, 27)
        (1..maxZones).each { Integer zoneNum -> upsertDiscoveredZone(zoneNum, null, null) }
    }
    reconcileExistingZones()
    applyConnectionSettings()
    runIn(1, "initializeConnection")
}

def initializeConnection() { getChildDevice("uno-connection")?.initialize() }
private void ensureConnectionDevice() { if (!getChildDevice("uno-connection")) addChildDevice("uno", "UNO Connection", "uno-connection", [name: "UNO Connection", label: "UNO Connection", isComponent: false]) }
private void ensurePartitionDevice() {
    String label = settings.partitionLabel ?: "House Alarm"
    def dev = getChildDevice("uno-partition-1")
    if (!dev) addChildDevice("uno", "UNO Partition", "uno-partition-1", [name: label, label: label, isComponent: true])
    else if (dev.label != label) dev.setLabel(label)
}
private void applyConnectionSettings() {
    def conn = getChildDevice("uno-connection")
    if (!conn) return
    conn.updateSetting("ip", [value: settings.ip, type: "text"])
    conn.updateSetting("port", [value: "${settings.port ?: 4025}", type: "number"])
    conn.updateSetting("password", [value: settings.password, type: "text"])
    conn.updateSetting("masterCode", [value: settings.masterCode ?: "", type: "text"])
    conn.updateSetting("zoneCount", [value: "${safeInt(settings.zoneCount, 27)}", type: "number"])
    conn.updateSetting("debugLogging", [value: settings.debugLogging == true, type: "bool"])
    conn.updateSetting("traceLogging", [value: settings.traceLogging == true, type: "bool"])
    conn.updateSetting("heartbeatMinutes", [value: (settings.heartbeatMinutes ?: "1").toString(), type: "enum"])
    conn.updateSetting("watchdogMinutes", [value: (settings.watchdogMinutes ?: "3").toString(), type: "enum"])
}

def refreshAll() { getChildDevice("uno-connection")?.refreshAll() }
def armStay() { getChildDevice("uno-connection")?.armStay() }
def armAway() { getChildDevice("uno-connection")?.armAway() }
def disarm(String code = null) { code ? getChildDevice("uno-connection")?.disarm(code) : getChildDevice("uno-connection")?.disarm() }
def notifyPartitionState(String status) { getChildDevice("uno-partition-1")?.setAlarmState(status) }

def upsertDiscoveredZone(Integer zoneNum, String suggestedType = null, String suggestedName = null) {
    if (!zoneNum || zoneNum <= 0) return
    Map hints = parseZoneHints(settings.zoneHints)
    Map hint = hints["${zoneNum}"] ?: [:]
    String hintedLabel = hint.label?.trim()
    String hintedType = hint.type?.trim()
    String finalType = canonicalType(hintedType ?: suggestedType ?: inferTypeFromName(hintedLabel ?: suggestedName) ?: settings.defaultUnknownZoneType ?: "contact")
    String desiredDriver = driverNameForType(finalType)
    String dni = "uno-zone-${zoneNum}"
    def existing = getChildDevice(dni)
    String finalLabel = hintedLabel ?: suggestedName?.trim() ?: existing?.label ?: "Zone ${zoneNum}"
    if (!existing) { addChildDevice("uno", desiredDriver, dni, [name: "UNO Zone ${zoneNum}", label: finalLabel, isComponent: true]); return }
    String existingTypeName = null
    try { existingTypeName = existing.getTypeName() } catch (ignored) { try { existingTypeName = existing.typeName } catch (ignored2) {} }
    if (existingTypeName && existingTypeName != desiredDriver) {
        try { deleteChildDevice(dni); addChildDevice("uno", desiredDriver, dni, [name: "UNO Zone ${zoneNum}", label: finalLabel, isComponent: true]) } catch (e) { log.warn "Could not recreate zone ${zoneNum}; device is in use: ${e}" }
        return
    }
    if (existing.label != finalLabel) existing.setLabel(finalLabel)
}

private void reconcileExistingZones() {
    Map hints = parseZoneHints(settings.zoneHints)
    getChildDevices()?.each { dev ->
        String dni = dev.deviceNetworkId ?: ""
        if (!dni.startsWith("uno-zone-")) return
        Integer zoneNum = safeParseZoneFromDni(dni)
        if (!zoneNum) return
        Map hint = hints["${zoneNum}"] ?: [:]
        String desiredLabel = hint.label?.trim() ?: "Zone ${zoneNum}"
        String desiredDriver = driverNameForType(canonicalType(hint.type?.trim() ?: inferTypeFromName(desiredLabel) ?: settings.defaultUnknownZoneType ?: "contact"))
        String existingTypeName = null
        try { existingTypeName = dev.getTypeName() } catch (ignored) { try { existingTypeName = dev.typeName } catch (ignored2) {} }
        if (existingTypeName && existingTypeName != desiredDriver) {
            try { deleteChildDevice(dni); addChildDevice("uno", desiredDriver, dni, [name: "UNO Zone ${zoneNum}", label: desiredLabel, isComponent: true]) } catch (e) { log.warn "Could not recreate ${dni}: ${e}" }
        } else if (dev.label != desiredLabel) dev.setLabel(desiredLabel)
    }
}
private Integer safeParseZoneFromDni(String dni) { try { return dni.replace("uno-zone-", "") as Integer } catch (ignored) { return null } }
private String driverNameForType(String zoneType) { switch ((zoneType ?: "contact").toLowerCase()) { case "motion": return "UNO Zone Motion"; case "smoke": return "UNO Zone Smoke"; case "co": return "UNO Zone CO"; case "water": return "UNO Zone Water"; default: return "UNO Zone Contact" } }
private String canonicalType(String raw) { switch ((raw ?: "contact").toLowerCase().trim()) { case "pir": case "interior": return "motion"; case "fire": case "heat": return "smoke"; case "carbonmonoxide": case "carbon_monoxide": return "co"; case "leak": case "flood": return "water"; default: return (raw ?: "contact").toLowerCase().trim() } }
private String inferTypeFromName(String name) { String n=(name?:"").toLowerCase(); if(n.contains("pir")||n.contains("motion")) return "motion"; if(n.contains("smoke")||n.contains("heat")) return "smoke"; if(n.contains("carbon monoxide")||n=="co"||n.contains(" co")) return "co"; if(n.contains("water")||n.contains("leak")||n.contains("flood")) return "water"; if(n.contains("door")||n.contains("window")||n.contains("gate")||n.contains("patio")) return "contact"; return null }
private Map parseZoneHints(String raw) { Map result=[:]; if(!raw) return result; raw.split("\n").each { String line -> def cleaned=line?.trim(); if(!cleaned||cleaned.startsWith("#")) return; def parts=cleaned.split(":") as List<String>; if(parts.size()<2) return; def zone=parts[0]?.trim(); if(!zone?.isInteger()) return; result[zone]=[label:(parts.size()>=2?parts[1]?.trim():"Zone ${zone}"), type:(parts.size()>=3?parts[2]?.trim():null)] }; return result }
private Integer safeInt(def val, Integer fallback) { try { return (val ?: fallback) as Integer } catch (ignored) { return fallback } }
