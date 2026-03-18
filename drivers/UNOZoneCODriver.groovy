metadata {
    definition(name: "UNO Zone CO", namespace: "uno", author: "Eric Hess") {
        capability "Sensor"
        capability "CarbonMonoxideDetector"

        attribute "zoneStateDigit", "string"
    }
}

def installed() {
    sendEvent(name: "carbonMonoxide", value: "clear")
    sendEvent(name: "zoneStateDigit", value: "0")
}
