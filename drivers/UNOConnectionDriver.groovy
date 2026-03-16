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

def installed(){ initialize() }
def updated(){ unschedule(); initialize() }

def initialize(){
    state.rxBuffer=""; state.commandQueue=[]; state.commandInFlight=null; state.lastZoneBitmap=null; state.lastPartitionBitmap=null; state.lastBypassBitmap=null; state.lastTroubleBitmap=null; state.loggedIn=false; state.loginSent=false; state.lastFrameTs=0L; state.lastCidByZone=[:]; state.lastCidEvent=""
    sendEvent(name:"connectionState", value:"disconnected")
    if(!device.currentValue("alarmSystemStatus")) sendEvent(name:"alarmSystemStatus", value:"unknown")
    if(!device.currentValue("troubleState")) sendEvent(name:"troubleState", value:"unknown")
    scheduleHealthChecks(); reconnect()
}

private void scheduleHealthChecks(){ String hb=(settings.heartbeatMinutes?:"1").toString(); if(hb=="1") runEvery1Minute("heartbeatCheck") else if(hb=="5") runEvery5Minutes("heartbeatCheck") else runEvery10Minutes("heartbeatCheck"); runEvery30Minutes("watchdogCheck") }

def reconnect(){ try{ interfaces.rawSocket.close() } catch(ignored){}; state.loggedIn=false; state.loginSent=false; state.commandInFlight=null; Integer p=safePort(); sendEvent(name:"connectionState", value:"connecting"); interfaces.rawSocket.connect(settings.ip, p, byteInterface:false) }

def socketStatus(String message){ String m=(message?:"").toLowerCase(); if(m.contains("connect")){ sendEvent(name:"connectionState", value:"connected"); return }; if(m.contains("close")||m.contains("disconnected")||m.contains("error")||m.contains("failure")){ sendEvent(name:"connectionState", value:"disconnected"); state.loggedIn=false; state.loginSent=false; state.commandInFlight=null; runIn(5,"reconnect") } }

def login(){ if(!settings.password) return; state.loginSent=true; rawSend(settings.password+"\r") }
def refresh(){ refreshAll() }
def refreshAll(){ rawSend(tpiCommand("0C","")+"\r\n") }
def poll(){ rawSend(tpiCommand("00","")+"\r\n") }
def armStay(){ enqueueCommand("08","1","armStay") }
def armAway(){ enqueueCommand("09","1","armAway") }
def disarm(String suppliedCode=null){ String code=suppliedCode?:settings.masterCode; if(!code) return; enqueueCommand("12","1,${code}","disarm") }
def clearCommandQueue(){ state.commandQueue=[]; state.commandInFlight=null }
def heartbeatCheck(){ if(device.currentValue("connectionState")=="authenticated") poll() }
def watchdogCheck(){ long lastTs=(state.lastFrameTs?:0L) as Long; Integer wd=safeInt(settings.watchdogMinutes,3); if(device.currentValue("connectionState")!="authenticated"||lastTs==0L) return; long age=(now()-lastTs)/1000L; if(age>(wd*60)) reconnect() }

private Integer safePort(){ try{ return (settings.port?:4025) as Integer } catch(ignored){ return 4025 } }
private Integer safeInt(def val,Integer fallback){ try{ return (val?:fallback) as Integer } catch(ignored){ return fallback } }
private Integer safeParseInt(String s,Integer fallback=0){ try{ return Integer.parseInt(s) } catch(ignored){ return fallback } }
private Integer configuredZoneCount(){ try{ return (settings.zoneCount?:27) as Integer } catch(ignored){ return 27 } }
private String tpiCommand(String cc,String data=""){ return "^"+cc+","+(data?:"")+ '$' }
private void enqueueCommand(String cc,String data,String name){ state.commandQueue=state.commandQueue?:[]; state.commandQueue << [cc:cc,data:(data?:""),name:name,ts:now()]; processQueue() }
private void processQueue(){ if(!state.loggedIn||state.commandInFlight||!(state.commandQueue instanceof List)||state.commandQueue.isEmpty()) return; def next=state.commandQueue.remove(0); state.commandInFlight=[cc:next.cc,data:next.data,name:next.name,sentAt:now(),retries:0]; rawSend(tpiCommand(next.cc as String,next.data as String)+"\r\n"); runIn(4,"commandTimeoutCheck") }
def commandTimeoutCheck(){ def inflight=state.commandInFlight; if(!inflight) return; long ageMs=now()-(inflight.sentAt as Long); if(ageMs<3500L) return; if((inflight.retries as Integer)<1){ inflight.retries=((inflight.retries as Integer)+1); inflight.sentAt=now(); state.commandInFlight=inflight; rawSend(tpiCommand(inflight.cc as String,inflight.data as String)+"\r\n"); runIn(4,"commandTimeoutCheck") } else { sendEvent(name:"lastAck", value:"timeout:${inflight.name}"); state.commandInFlight=null; processQueue() } }
private void rawSend(String msg){ interfaces.rawSocket.sendMessage(msg) }

def parse(String message){ if(message==null) return; String decoded=decodeIncoming(message); if(decoded==null) return; state.lastFrameTs=now(); sendEvent(name:"lastHeartbeat", value:new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)); state.rxBuffer=(state.rxBuffer?:"")+decoded; if(!state.loggedIn&&!state.loginSent&&state.rxBuffer.contains("Login:")){ sendEvent(name:"connectionState", value:"loginPrompt"); login() }; while(true){ String buffer=state.rxBuffer?:""; int idxDollar=buffer.indexOf('$'); int idxLf=buffer.indexOf('\n'); int idxCr=buffer.indexOf('\r'); def candidates=[idxDollar,idxLf,idxCr].findAll{it>=0}; if(!candidates) break; int endIdx=candidates.min(); String frame=buffer.substring(0,endIdx).trim(); state.rxBuffer=buffer.substring(endIdx+1); if(frame) parseFrame(frame) } }
private String decodeIncoming(String message){ try{ byte[] data=hubitat.helper.HexUtils.hexStringToByteArray(message); return new String(data) } catch(ignored){ return message } }
private void parseFrame(String frame){ sendEvent(name:"lastRawMessage", value:frame.take(1024)); if(frame.equalsIgnoreCase("OK")){ state.loggedIn=true; state.loginSent=false; sendEvent(name:"connectionState", value:"authenticated"); rawSend(tpiCommand("0D","")+"\r\n"); rawSend(tpiCommand("0C","")+"\r\n"); processQueue(); return }; if(frame.equalsIgnoreCase("FAILED")){ state.loggedIn=false; state.loginSent=false; sendEvent(name:"connectionState", value:"auth_failed"); return }; if(frame.equalsIgnoreCase("Timed Out!")||frame.equalsIgnoreCase("Timed Out")){ state.loginSent=false; sendEvent(name:"connectionState", value:"loginTimedOut"); return }; if(frame.equalsIgnoreCase("Login:")) return; if(frame.startsWith("^")){ parseAckFrame(frame); return }; if(!frame.startsWith("%")) return; List<String> parts=frame.substring(1).split(",",-1) as List<String>; if(!parts||parts.isEmpty()) return; switch(parts[0]){ case "01": handleZoneBitmap(parts); break; case "02": handlePartitionFrame(parts); break; case "03": handleCidEvent(parts); break; case "04": handleBypassBitmap(parts); break; case "05": handlePanelInfo(parts); break; case "06": handleTroubleBitmap(parts); break; case "09": handleSystemMessage(parts); break; } }
private void parseAckFrame(String frame){ String body=frame.substring(1); if(body.endsWith('$')) body=body.substring(0,body.length()-1); List<String> parts=body.split(",",-1) as List<String>; if(parts.size()<2) return; sendEvent(name:"lastAck", value:parts[0]+","+parts[1]); if(state.commandInFlight) state.commandInFlight=null; processQueue() }
private void handleZoneBitmap(List<String> parts){ if(parts.size()<2) return; String bitmapHex=cleanPayload(parts[1]).toUpperCase(); sendEvent(name:"zoneBitmapLength", value:bitmapHex.length()); String previous=state.lastZoneBitmap; state.lastZoneBitmap=bitmapHex; Map<Integer,Boolean> currentMap=decodeZoneBitmap(bitmapHex); Map<Integer,Boolean> previousMap=previous?decodeZoneBitmap(previous):[:]; int absoluteMax=currentMap.keySet()?.max()?:configuredZoneCount(); int loopMax=previous==null?configuredZoneCount():absoluteMax; for(int zone=1; zone<=loopMax; zone++){ boolean newState=currentMap.get(zone,false); boolean oldState=previousMap.get(zone,false); if(previous==null||newState!=oldState){ parent?.upsertDiscoveredZone(zone,null,null); emitZoneFastEvent(zone,newState); routeZoneUpdate(zone,newState,newState?"1":"0") } } }
private Map<Integer,Boolean> decodeZoneBitmap(String hex){ Map<Integer,Boolean> result=[:]; if(!hex) return result; int zoneNumber=1; for(int byteIndex=0; byteIndex<hex.length(); byteIndex+=2){ if(byteIndex+2>hex.length()) break; int value=Integer.parseInt(hex.substring(byteIndex,byteIndex+2),16); for(int bit=0; bit<8; bit++){ result[zoneNumber]=(((value>>bit)&0x01)==1); zoneNumber++ } } return result }
private void handlePartitionFrame(List<String> parts){ if(parts.size()<2) return; String bitmap=cleanPayload(parts[1]).toUpperCase(); state.lastPartitionBitmap=bitmap; String status=decodePartitionStatus(bitmap); if(status!=device.currentValue("alarmSystemStatus")) sendEvent(name:"alarmSystemStatus", value:status); parent?.notifyPartitionState(status) }
private String decodePartitionStatus(String bitmap){ if(!bitmap||bitmap.length()<2) return "unknown"; switch(bitmap.substring(0,2)){ case "00": return "unused"; case "01": return "disarmed"; case "02": return "readyBypassed"; case "03": return "notReady"; case "04": return "armedStay"; case "05": return "armedAway"; case "08": return "exitDelay"; case "09": return "armedAwayZeroEntry"; case "0C": return "entryDelay"; case "11": return "alarm"; default: return "unknown" } }
private void handleCidEvent(List<String> parts){ if(parts.size()<2) return; String cid=cleanPayload(parts[1]); if(!cid||cid.length()<10) return; String qualifier=cid.substring(0,1); String eventCode=cid.substring(1,4); Integer zone=safeParseInt(cid.substring(6,9),0); boolean isRestore=(qualifier=="3"); boolean isEvent=(qualifier=="1"); sendEvent(name:"lastCidEvent", value:("CID q=${qualifier} code=${eventCode} zone=${zone}").take(1024)); if(zone<=0) return; String suggestedType=suggestTypeFromCid(eventCode); parent?.upsertDiscoveredZone(zone,suggestedType,null); def fast=["110","113","130","131","132","134","137","144","154","162"] as Set; if(!fast.contains(eventCode)||!(isEvent||isRestore)) return; boolean active=isEvent&&!isRestore; state.lastCidByZone=state.lastCidByZone?:[:]; state.lastCidByZone["${zone}"]=now(); emitZoneFastEvent(zone,active); routeZoneUpdate(zone,active,active?"1":"0") }
private String suggestTypeFromCid(String eventCode){ switch(eventCode){ case "110": return "smoke"; case "154": return "water"; case "162": return "co"; default: return null } }
private void handleBypassBitmap(List<String> parts){ if(parts.size()<2) return; state.lastBypassBitmap=cleanPayload(parts[1]).toUpperCase() }
private void handlePanelInfo(List<String> parts){ if(parts.size()<6) return; String mac=cleanPayload(parts[1]); String model=cleanPayload(parts[2]); String fw=cleanPayload(parts[3]).trim(); String panelTime=cleanPayload(parts[5]); if(mac) sendEvent(name:"panelMac", value:mac); if(model) sendEvent(name:"panelModel", value:model); if(fw) sendEvent(name:"firmwareVersion", value:fw); if(panelTime) sendEvent(name:"panelTime", value:panelTime) }
private void handleTroubleBitmap(List<String> parts){ if(parts.size()<2) return; String bitmap=cleanPayload(parts[1]).toUpperCase(); state.lastTroubleBitmap=bitmap; String trouble=(bitmap==("0"*bitmap.length()))?"clear":"trouble"; if(trouble!=device.currentValue("troubleState")) sendEvent(name:"troubleState", value:trouble) }
private void handleSystemMessage(List<String> parts){ sendEvent(name:"lastSystemMessage", value:parts.drop(1).join(",").take(1024)) }
private void emitZoneFastEvent(Integer zoneNum, boolean active){ sendLocationEvent(name:"unoZoneEvent", value:zoneNum+":"+(active?"open":"closed"), descriptionText:"UNO zone ${zoneNum} ${active?'opened':'closed'}", isStateChange:true) }
private void routeZoneUpdate(Integer zoneNum, boolean active, String digit){ String dni="uno-zone-${zoneNum}"; parent?.upsertDiscoveredZone(zoneNum,null,null); def child=null; try{ child=parent?.getChildDevice(dni) }catch(ignored){}; if(!child){ try{ child=parent?.getChildDevices()?.find{ it.deviceNetworkId==dni } }catch(ignored){} } ; if(!child) return; updateZoneChild(child,zoneNum,active,digit) }
private void updateZoneChild(child,Integer zoneNum,boolean active,String digit){ try{ child.sendEvent(name:"zoneStateDigit", value:digit, isStateChange:true) }catch(ignored){}; if(hasCapabilitySafe(child,"ContactSensor")) child.sendEvent(name:"contact", value:(active?"open":"closed"), type:"physical", descriptionText:"Zone ${zoneNum} ${active?'open':'closed'}", isStateChange:true); if(hasCapabilitySafe(child,"MotionSensor")) child.sendEvent(name:"motion", value:(active?"active":"inactive"), type:"physical", descriptionText:"Zone ${zoneNum} motion ${active?'active':'inactive'}", isStateChange:true); if(hasCapabilitySafe(child,"SmokeDetector")) child.sendEvent(name:"smoke", value:(active?"detected":"clear"), type:"physical", descriptionText:"Zone ${zoneNum} smoke ${active?'detected':'clear'}", isStateChange:true); if(hasCapabilitySafe(child,"CarbonMonoxideDetector")) child.sendEvent(name:"carbonMonoxide", value:(active?"detected":"clear"), type:"physical", descriptionText:"Zone ${zoneNum} carbon monoxide ${active?'detected':'clear'}", isStateChange:true); if(hasCapabilitySafe(child,"WaterSensor")) child.sendEvent(name:"water", value:(active?"wet":"dry"), type:"physical", descriptionText:"Zone ${zoneNum} water ${active?'wet':'dry'}", isStateChange:true) }
private boolean hasCapabilitySafe(child,String cap){ try{ return child.hasCapability(cap) } catch(ignored){ return false } }
private String cleanPayload(String s){ return (s?:"").replace('$',' ').trim() }
