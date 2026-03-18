metadata {
    definition(name: "UNO Zone Motion", namespace: "uno", author: "Eric Hess") {
        capability "Sensor"
        capability "MotionSensor"

        attribute "zoneStateDigit", "string"
    }
}

def installed() {
    sendEvent(name: "motion", value: "inactive")
    sendEvent(name: "zoneStateDigit", value: "0")
}
