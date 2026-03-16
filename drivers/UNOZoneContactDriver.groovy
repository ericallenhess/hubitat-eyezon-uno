metadata { definition(name: "UNO Zone Contact", namespace: "uno", author: "Eric Hess") { capability "Sensor"; capability "ContactSensor"; attribute "zoneStateDigit", "string" } }
def installed() { sendEvent(name: "contact", value: "closed"); sendEvent(name: "zoneStateDigit", value: "0") }
