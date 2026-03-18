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

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", install: true, uninstall: true) {
        section("UNO Connection") {
            input "ip", "text", title: "UNO IP Address", required: true
            input "port", "number", title: "UNO Port", defaultValue: 4025, required: true
            input "password", "text", title: "UNO Local/TPI Password", required: true
            input "masterCode", "text", title: "Master Code (optional, for disarm)", required: false
        }

        section("Alarm Device") {
            input "partitionLabel", "text",
                title: "Alarm device name",
                description: "Friendly name for the partition/alarm device",
                defaultValue: "House Alarm",
                required: false
        }

        section("Zone Discovery") {
            input "zoneCount", "number", title: "Configured Zone Count", defaultValue: 27, required: true
            input "autoCreateConfiguredZones", "bool", title: "Pre-create zones 1..Zone Count at install", defaultValue: true
            input "defaultUnknownZoneType", "enum",
                title: "Default type for unknown zones",
                options: [
                    "contact": "Contact",
                    "motion" : "Motion",
                    "smoke"  : "Smoke",
                    "co"     : "CO",
                    "water"  : "Water"
                ],
                defaultValue: "contact",
                required: true
        }

        section("Zone Hints (optional but recommended)") {
            paragraph """One zone per line.
Format:
zoneNumber:Label:type

Examples:
1:Interconnected Smokes:smoke
2:Dining Room PIR:motion
17:Family Patio Door:contact
22:Kitchen CO:co
23:Kitchen Heat:smoke
25:Office PIR:motion
26:Master Hall PIR:motion
27:Office Door:contact"""
            input "zoneHints", "textarea", title: "Zone Hints", required: false
        }

        section("Logging / Health") {
            input "debugLogging", "bool", title: "Enable debug logging", defaultValue: false
            input "traceLogging", "bool", title: "Enable trace logging", defaultValue: false
            input "heartbeatMinutes", "enum",
                title: "Heartbeat interval",
                options: ["1":"Every 1 minute", "5":"Every 5 minutes", "10":"Every 10 minutes"],
                defaultValue: "1",
                required: true
            input "watchdogMinutes", "enum",
                title: "Reconnect if no frames seen for",
                options: ["3":"3 minutes", "5":"5 minutes", "10":"10 minutes"],
                defaultValue: "3",
                required: true
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    initialize()
}

def initialize() {
    logInfo("Initializing UNO integration")

    state.zoneHintsMap = parseZoneHints(settings.zoneHints)

    ensureConnectionDevice()
    ensurePartitionDevice()

    if (settings.autoCreateConfiguredZones != false) {
        Integer maxZones = safeInt(settings.zoneCount, 27)
        (1..maxZones).each { Integer zoneNum ->
            upsertDiscoveredZone(zoneNum, null, null)
        }
    }

    reconcileExistingZones()
    applyConnectionSettings()
    runIn(1, "initializeConnection")
}

def initializeConnection() {
    def conn = getChildDevice("uno-connection")
    if (conn) {
        conn.initialize()
    }
}

private void ensureConnectionDevice() {
    String dni = "uno-connection"
    def dev = getChildDevice(dni)

    if (!dev) {
        addChildDevice(
            "uno",
            "UNO Connection",
            dni,
            [
                name       : "UNO Connection",
                label      : "UNO Connection",
                isComponent: false
            ]
        )
        logInfo("Created UNO Connection device")
    }
}

private void ensurePartitionDevice() {
    String dni = "uno-partition-1"
    String label = settings.partitionLabel ?: "House Alarm"

    def dev = getChildDevice(dni)

    if (!dev) {
        addChildDevice(
            "uno",
            "UNO Partition",
            dni,
            [
                name       : label,
                label      : label,
                isComponent: true
            ]
        )
        logInfo("Created partition device: ${label}")
    } else {
        try {
            if (dev.label != label) {
                dev.setLabel(label)
                logInfo("Updated partition label to ${label}")
            }
        } catch (e) {
            log.warn "Unable to update partition label: ${e}"
        }
    }
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

def refreshAll() {
    getChildDevice("uno-connection")?.refreshAll()
}

def armStay() {
    getChildDevice("uno-connection")?.armStay()
}

def armAway() {
    getChildDevice("uno-connection")?.armAway()
}

def disarm(String code = null) {
    if (code) {
        getChildDevice("uno-connection")?.disarm(code)
    } else {
        getChildDevice("uno-connection")?.disarm()
    }
}

def notifyPartitionState(String status) {
    def partition = getChildDevice("uno-partition-1")
    if (partition) {
        partition.setAlarmState(status)
    }
}

def upsertDiscoveredZone(Integer zoneNum, String suggestedType = null, String suggestedName = null) {
    if (zoneNum == null || zoneNum <= 0) return

    Map hints = parseZoneHints(settings.zoneHints)
    state.zoneHintsMap = hints

    Map hint = hints["${zoneNum}"] ?: [:]

    String hintedLabel = hint.label?.trim()
    String hintedType  = hint.type?.trim()

    String inferredName = suggestedName?.trim()
    String inferredType = suggestedType?.trim()

    String resolvedType = canonicalType(
        hintedType ?:
        inferredType ?:
        inferTypeFromName(hintedLabel ?: inferredName) ?:
        settings.defaultUnknownZoneType ?:
        "contact"
    )

    String desiredDriver = driverNameForType(resolvedType)
    String dni = "uno-zone-${zoneNum}"
    def existing = getChildDevice(dni)

    String finalLabel = hintedLabel ?: inferredName ?: existing?.label ?: "Zone ${zoneNum}"

    if (!existing) {
        addChildDevice(
            "uno",
            desiredDriver,
            dni,
            [
                name       : "UNO Zone ${zoneNum}",
                label      : finalLabel,
                isComponent: true
            ]
        )
        logDebug("Created zone ${zoneNum}: ${finalLabel} (${desiredDriver})")
        return
    }

    boolean needsRecreate = false
    String existingTypeName = null

    try {
        existingTypeName = existing.getTypeName()
    } catch (ignored) {
        try {
            existingTypeName = existing.typeName
        } catch (ignored2) {
        }
    }

    if (existingTypeName && existingTypeName != desiredDriver) {
        needsRecreate = true
    }

    if (needsRecreate) {
        try {
            deleteChildDevice(dni)
        } catch (e) {
            log.warn "Could not recreate zone ${zoneNum}; device is in use: ${e}"
            return
        }

        addChildDevice(
            "uno",
            desiredDriver,
            dni,
            [
                name       : "UNO Zone ${zoneNum}",
                label      : finalLabel,
                isComponent: true
            ]
        )
        logInfo("Recreated zone ${zoneNum}: ${finalLabel} (${desiredDriver})")
        return
    }

    try {
        if (existing.label != finalLabel) {
            existing.setLabel(finalLabel)
            logDebug("Updated zone ${zoneNum} label to ${finalLabel}")
        }
    } catch (e) {
        log.warn "Unable to relabel zone ${zoneNum}: ${e}"
    }
}

private void reconcileExistingZones() {
    Map hints = parseZoneHints(settings.zoneHints)

    getChildDevices()?.each { dev ->
        String dni = dev.deviceNetworkId ?: ""
        if (!dni.startsWith("uno-zone-")) return

        Integer zoneNum = safeParseZoneFromDni(dni)
        if (!zoneNum) return

        Map hint = hints["${zoneNum}"] ?: [:]
        String hintedLabel = hint.label?.trim()
        String hintedType  = canonicalType(hint.type?.trim())

        String desiredLabel = hintedLabel ?: "Zone ${zoneNum}"
        String desiredDriver = driverNameForType(
            hintedType ?: inferTypeFromName(hintedLabel) ?: settings.defaultUnknownZoneType ?: "contact"
        )

        boolean needsRecreate = false
        String existingTypeName = null

        try {
            existingTypeName = dev.getTypeName()
        } catch (ignored) {
            try {
                existingTypeName = dev.typeName
            } catch (ignored2) {
            }
        }

        if (existingTypeName && existingTypeName != desiredDriver) {
            needsRecreate = true
        }

        if (needsRecreate) {
            try {
                deleteChildDevice(dni)
                addChildDevice(
                    "uno",
                    desiredDriver,
                    dni,
                    [
                        name       : "UNO Zone ${zoneNum}",
                        label      : desiredLabel,
                        isComponent: true
                    ]
                )
                logInfo("Recreated ${dni} as ${desiredDriver}")
            } catch (e) {
                log.warn "Could not recreate ${dni}: ${e}"
            }
        } else {
            try {
                if (dev.label != desiredLabel) {
                    dev.setLabel(desiredLabel)
                    logDebug("Relabeled ${dni} to ${desiredLabel}")
                }
            } catch (e) {
                log.warn "Could not relabel ${dni}: ${e}"
            }
        }
    }
}

private Integer safeParseZoneFromDni(String dni) {
    try {
        return dni.replace("uno-zone-", "") as Integer
    } catch (ignored) {
        return null
    }
}

private String driverNameForType(String zoneType) {
    switch ((zoneType ?: "contact").toLowerCase()) {
        case "motion":
            return "UNO Zone Motion"
        case "smoke":
            return "UNO Zone Smoke"
        case "co":
            return "UNO Zone CO"
        case "water":
            return "UNO Zone Water"
        default:
            return "UNO Zone Contact"
    }
}

private String canonicalType(String raw) {
    String t = (raw ?: "contact").toLowerCase().trim()

    switch (t) {
        case "pir":
        case "interior":
            return "motion"

        case "fire":
        case "heat":
            return "smoke"

        case "carbonmonoxide":
        case "carbon_monoxide":
            return "co"

        case "leak":
        case "flood":
            return "water"

        default:
            return t
    }
}

private String inferTypeFromName(String name) {
    String n = (name ?: "").toLowerCase()

    if (n.contains("pir") || n.contains("motion")) return "motion"
    if (n.contains("smoke") || n.contains("heat")) return "smoke"
    if (n.contains("carbon monoxide") || n == "co" || n.contains(" co")) return "co"
    if (n.contains("water") || n.contains("leak") || n.contains("flood")) return "water"
    if (n.contains("door") || n.contains("window") || n.contains("gate") || n.contains("patio")) return "contact"

    return null
}

private Map parseZoneHints(String raw) {
    Map result = [:]
    if (!raw) return result

    raw.split("\\n").each { String line ->
        String cleaned = line?.trim()
        if (!cleaned) return
        if (cleaned.startsWith("#")) return

        List<String> parts = cleaned.split(":") as List<String>
        if (parts.size() < 2) return

        String zone = parts[0]?.trim()
        if (!zone?.isInteger()) return

        String label = parts.size() >= 2 ? parts[1]?.trim() : "Zone ${zone}"
        String type  = parts.size() >= 3 ? parts[2]?.trim() : null

        result[zone] = [
            label: label,
            type : type
        ]
    }

    return result
}

private Integer safeInt(def val, Integer fallback) {
    try {
        return (val ?: fallback) as Integer
    } catch (ignored) {
        return fallback
    }
}

private void logInfo(String msg) {
    log.info msg
}

private void logDebug(String msg) {
    if (settings.debugLogging) {
        log.debug msg
    }
}
