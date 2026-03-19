# EyezOn UNO Hubitat Integration
![Hubitat](https://img.shields.io/badge/Hubitat-Compatible-orange)
![Protocol](https://img.shields.io/badge/Protocol-UNO%20TPI-green)
![Install](https://img.shields.io/badge/Install-HPM-blue)
![License](https://img.shields.io/badge/License-MIT-lightgrey)

A production-grade Hubitat integration for the **EyezOn UNO Alarm System**.

> **Important:** The EyezOn **UNO is a stand-alone alarm system**. It is **not** just an interface to another panel such as DSC or Honeywell. This integration communicates **directly with the UNO itself** over the **UNO TPI** TCP socket protocol on port **4025**.

## Features
- Direct raw TCP integration to the UNO TPI socket
- Full zone monitoring from the UNO zone bitmap
- Automatic zone discovery and reconciliation
- Friendly zone naming from `zoneHints`
- Automatic zone driver assignment from configured hints and best-effort inference
- Partition / alarm device support through **House Alarm**
- Arm Stay / Arm Away / Disarm commands
- Contact, Motion, Smoke, CO, and Water child devices
- Fast location events for low-latency automations: `unoZoneEvent`
- Watchdog reconnect and heartbeat polling
- CID fast-path support for alarm-class events
- REST metadata support for zone labels and partition label
- REST startup sync for immediate zone-state initialization after reconnect
  
## REST metadata and startup sync

The integration can optionally use the UNO local REST interface for:

- zone labels
- partition label
- system name
- startup zone-state synchronization

Default REST username is:

`user`

TPI remains the real-time event and control path.
## Roadmap

### v1.2
Planned improvements:

• alarm event history attributes  
• semantic CID event mapping  
• improved dashboard support  

### v1.3
Potential future improvements:

• zone ignore / disable support  
• optional event noise suppression  
• extended CID event mapping
