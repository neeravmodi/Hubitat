/**
 * Leakbot Device
 * Child driver for the Leakbot Connect parent app
 *
 * Represents a single Leakbot unit and exposes its cloud-reported
 * attributes as Hubitat device attributes.
 *
 * Attributes surfaced:
 *   battery          - 0-100 number (satisfies Battery capability)
 *   batteryStatus    - raw API string e.g. GoodBattery, LowBattery
 *   leakStatus       - e.g. ok, Leak Inactive, Leak Active, high_usage
 *   deviceStatus     - raw device_status string from API (same as leakStatus)
 *   leakFreeDays     - integer count of consecutive leak-free days
 *   signalStrength   - e.g. HasSignal, NoSignal
 *   statusChangedAt  - when device_status last changed (ISO timestamp from API)
 *   lastMessage      - ISO timestamp of most recent device message
 *   lastMessageType  - msg_type of most recent message
 *   lastUpdated      - ISO timestamp of last successful poll
 *
 * temperature: the Leakbot API does NOT expose raw pipe or air temperature
 * readings in any confirmed endpoint. Temperature is used internally by
 * the Leakbot firmware for leak detection only. If this changes (e.g.
 * after traffic capture confirms a field), add here:
 *   // attribute "temperature", "number"
 *
 * Copyright 2026 Neerav Modi
 * Apache License 2.0
 *
 * Acknowledgements:
 *   API endpoints and data structures discovered with reference to:
 *     homeassistant-leakbot by sHedC
 *     https://github.com/sHedC/homeassistant-leakbot
 *   Developed with the assistance of Claude AI (Anthropic)
 *     https://claude.ai
 *
 * Changelog:
 *   1.0 - initial release
 */

metadata {
    definition(
        name:      "Leakbot Device",
        namespace: "neeravmodi",
        author:    "Neerav Modi",
        importUrl: "https://github.com/neeravmodi/Hubitat/blob/main/Integrations/Leakbot/leakbot-device.groovy"
    ) {
        capability "Sensor"
        capability "Battery"       // standard battery attribute (0-100 number)
        capability "Refresh"

        attribute "battery",          "number"    // mapped 0-100 (satisfies Battery capability)
        attribute "batteryStatus",    "string"    // raw API string e.g. GoodBattery, LowBattery
        attribute "leakStatus",       "string"    // e.g. ok, leak_inactive, leakdetected, high_usage
        attribute "leakFreeDays",     "number"
        attribute "deviceStatus",     "string"    // raw device_status from MyView
        attribute "signalStrength",   "string"    // message_frequency_sm e.g. HasSignal, NoSignal
        attribute "lastMessage",      "string"    // ISO timestamp of most recent message
        attribute "lastMessageType",  "string"    // msg_type of most recent message
        attribute "statusChangedAt",  "string"    // device_status_timestamp - when device_status last changed
        attribute "lastUpdated",      "string"    // ISO timestamp of last successful poll
    }

    preferences {
        input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logDebug "installed"
}

def updated() {
    logDebug "updated"
}

// ---------------------------------------------------------------------------
// Capability: Refresh
// ---------------------------------------------------------------------------

def refresh() {
    logDebug "refresh requested - delegating to parent"
    parent?.refreshDevice(device.getDataValue("deviceId"))
}

// ---------------------------------------------------------------------------
// Called by parent app
// ---------------------------------------------------------------------------

/**
 * updateStatus(Map status)
 * Called by the parent app with the response from /Device/Device/MyView/
 *
 * Expected fields (from DeviceStatusResponse type definition):
 *   battery_sm              - e.g. "goodbattery"
 *   device_status           - e.g. "ok"
 *   device_status_timestamp - ISO string
 *   leak_count_summary      - { leak_free_days, fix_leak_days, paused }
 */
def updateStatus(Map status) {
    logDebug "updateStatus: ${status}"

    // battery_sm
    def battRaw = status.battery_sm?.toString() ?: ""
    if (battRaw) {
        sendEvent(name: "battery",       value: batteryToLevel(battRaw))
        sendEvent(name: "batteryStatus", value: battRaw)
    }

    // device_status (Leakbot puts leak/event status here)
    def devStatus = status.device_status?.toString() ?: ""
    if (devStatus) {
        sendEvent(name: "deviceStatus", value: devStatus)
        sendEvent(name: "leakStatus",   value: devStatus)
    }

    // device_status_timestamp - when device_status last changed
    def statusChangedAt = status.device_status_timestamp?.toString() ?: ""
    if (statusChangedAt) {
        sendEvent(name: "statusChangedAt", value: statusChangedAt)
    }

    // message_frequency_sm - signal strength e.g. HasSignal, NoSignal
    def signal = status.message_frequency_sm?.toString() ?: ""
    if (signal) {
        sendEvent(name: "signalStrength", value: signal)
    }

    // leak_count_summary
    def summary = status.leak_count_summary
    if (summary) {
        def days = summary.leak_free_days?.toString()?.isInteger() ?
                   summary.leak_free_days.toString().toInteger() : null
        if (days != null) {
            sendEvent(name: "leakFreeDays", value: days)
        }
    }

    sendEvent(name: "lastUpdated", value: now().toString())
    logDebug "Status updated: battery=${batteryToLevel(battRaw)}% (${battRaw}), leakStatus=${devStatus}, signal=${signal}"
}

/**
 * updateMessages(Map msgs)
 * Called by the parent app with the response from /Device/Device/MyListMessagesForDevice
 *
 * Expected fields (from ListDevicesMessagesResponse type definition):
 *   list.record[].event_type
 *   list.record[].messageTimestamp
 *   list.record[].msg_type
 */
def updateMessages(Map msgs) {
    logDebug "updateMessages: ${msgs}"
    def records = msgs?.list?.record
    if (!records || records.isEmpty()) return

    def latest = records[0]
    if (latest.messageTimestamp) {
        sendEvent(name: "lastMessage", value: latest.messageTimestamp.toString())
    }
    if (latest.msg_type) {
        sendEvent(name: "lastMessageType", value: latest.msg_type.toString())
    }
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

/**
 * Map the battery_sm string to a conventional 0-100 integer.
 * Known values from the HA integration: "goodbattery"
 * Assumed: "lowbattery", "criticalbattery", "deadbattery"
 * Update as new values are observed.
 */
private int batteryToLevel(String battSm) {
    switch (battSm?.toLowerCase()) {
        case "goodbattery":     return 100
        case "mediumbattery":   return 50
        case "lowbattery":      return 10
        case "criticalbattery": return 5
        case "deadbattery":     return 0
        default:                return 100   // unknown → assume OK
    }
}

private String now() {
    return new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'", TimeZone.getTimeZone("UTC"))
}

def logDebug(msg) {
    if (logEnable) log.debug "Leakbot Device [${device.label ?: device.name}]: ${msg}"
}
