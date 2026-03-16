metadata { definition(name: "UNO Zone Smoke", namespace: "uno", author: "Eric Hess") { capability "Sensor"; capability "SmokeDetector"; attribute "zoneStateDigit", "string" } }
def installed() { sendEvent(name: "smoke", value: "clear"); sendEvent(name: "zoneStateDigit", value: "0") }
