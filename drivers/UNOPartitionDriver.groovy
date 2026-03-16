metadata {
    definition(name: "UNO Partition", namespace: "uno", author: "Eric Hess") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "Alarm"
        attribute "alarmSystemStatus", "string"
        attribute "partitionState", "string"
        attribute "securitySystemStatus", "string"
        attribute "lastUserAction", "string"
        command "armStay"
        command "armAway"
        command "disarm", [[name: "Code", type: "STRING"]]
        command "off"
        command "strobe"
        command "siren"
        command "both"
    }
}

def installed() {
    sendEvent(name: "alarmSystemStatus", value: "unknown")
    sendEvent(name: "partitionState", value: "unknown")
    sendEvent(name: "securitySystemStatus", value: "unknown")
    sendEvent(name: "alarm", value: "off")
}

def refresh() { parent?.refreshAll() }
def armStay() { parent?.armStay(); sendEvent(name: "lastUserAction", value: "armStay", isStateChange: true) }
def armAway() { parent?.armAway(); sendEvent(name: "lastUserAction", value: "armAway", isStateChange: true) }
def disarm(String code = null) { parent?.disarm(code); sendEvent(name: "lastUserAction", value: "disarm", isStateChange: true) }
def off() { disarm() }
def strobe() {}
def siren() {}
def both() {}

def setAlarmState(String state) {
    String value = state ?: "unknown"
    sendEvent(name: "alarmSystemStatus", value: value, isStateChange: true)
    sendEvent(name: "partitionState", value: value, isStateChange: true)
    sendEvent(name: "securitySystemStatus", value: mapSecurityState(value), isStateChange: true)
    sendEvent(name: "alarm", value: mapAlarmCapability(value), isStateChange: true)
}

private String mapSecurityState(String state) {
    switch (state) {
        case "disarmed": return "disarmed"
        case "armedStay": return "armed home"
        case "armedAway": return "armed away"
        case "alarm": return "alarm"
        case "entryDelay":
        case "exitDelay": return "arming"
        case "notReady": return "not ready"
        default: return state ?: "unknown"
    }
}

private String mapAlarmCapability(String state) {
    switch (state) {
        case "alarm": return "both"
        default: return "off"
    }
}
