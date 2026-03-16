# EyezOn UNO Hubitat Integration

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
