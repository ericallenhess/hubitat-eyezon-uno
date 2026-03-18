metadata {
    definition(name: "UNO Zone Water", namespace: "uno", author: "Eric Hess") {
        capability "Sensor"
        capability "WaterSensor"

        attribute "zoneStateDigit", "string"
    }
}

def installed() {
    sendEvent(name: "water", value: "dry")
    sendEvent(name: "zoneStateDigit", value: "0")
}
