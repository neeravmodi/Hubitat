/**
 * Tuya WiFi Access Control Keypad Driver (TFLCD) — Persistent Socket Edition
 * For Hubitat Elevation
 *
 * Copyright 2026 Neerav Modi
 *
 * Maintains a persistent TCP connection to the device so that unlock events,
 * doorbell presses, and alarm events are received instantly without polling.
 *
 * Supports: Fingerprint, RFID Card, PIN Code, Temporary Code, App Remote, Doorbell
 *
 * Based on iholand's Tuya Generic Device driver pattern.
 * Local LAN — Tuya protocol 3.3
 *
 * Developed with the assistance of Claude.ai (Anthropic)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Confirmed DP map for TFLCD device (all values confirmed via live testing):
 *   DP  9  = App remote unlock      (Base64 encoded, byte[3] = user slot)
 *   DP 10  = Fingerprint unlock     (Base64 encoded, byte[3] = user slot)
 *   DP 11  = PIN/password unlock    (Base64 encoded, byte[3] = user slot)
 *   DP 12  = RFID card unlock       (Base64 encoded, byte[3] = user slot)
 *   DP 22  = App session token      (informational, ignored)
 *   DP 25  = Unlock mode            (single_unlock, finger_password, finger_card,
 *                                    finger_face, password_card, password_face)
 *   DP 26  = Volume                 (mute, low, middle, high)
 *   DP 27  = Language               (english, chinese_simplified)
 *   DP 30  = Automatic lock         (true=on, false=off)
 *   DP 31  = Auto lock time         (seconds, 1-99)
 *   DP 34  = Failed attempt counter (integer, resets on reboot)
 *   DP 35  = Lock state indicator   (AQAB=unlocking, AQAC=relocking)
 *   DP 40  = Relay                  (true=open, false=closed)
 *
 * Cloud-only settings (not accessible via local protocol):
 *   Remote unlock setting, Permission settings, Alarm time, Multiple verification
 *
 * Changelog:
 * v1.0     - Initial public release. Full development history available on GitHub.
 *            Key features: persistent TCP socket, auto-reconnect, heartbeat keepalive,
 *            fingerprint/card/PIN/app unlock detection with user slot identification,
 *            device settings control (volume, language, unlock mode, auto lock),
 *            Base64 unlock record decoder, complete confirmed DP map for TFLCD devices.
 */

import javax.crypto.spec.SecretKeySpec
import javax.crypto.Cipher
import java.security.MessageDigest
import groovy.json.JsonSlurper

// -------------------------------------------------------
//  Version — update this on every release
// -------------------------------------------------------
def getVersion() { return "1.0" }

metadata {
    definition(name: "Tuya Access Control Keypad", namespace: "neeravmodi", author: "Neerav Modi",
            description: "Local control driver for Tuya WiFi Access Control Keypads paired via the Tuya app or Smart Life app. Supports fingerprint, RFID card, PIN code, and app remote unlock methods with real-time event reporting via persistent TCP connection.",
            importUrl: "https://raw.githubusercontent.com/neeravmodi/Hubitat/refs/heads/main/Drivers/Tuya-Access-Control-Keypad.groovy") {
        capability "Actuator"
        capability "Lock"
        capability "Sensor"
        capability "Refresh"
        capability "Initialize"

        command "unlockDoor"
        command "connectSocket"
        command "disconnectSocket"
        command "setVolume",        [[name: "volume*",   type: "ENUM",   description: "Keyboard volume",         constraints: ["mute", "low", "middle", "high"]]]
        command "setLanguage",      [[name: "language*", type: "ENUM",   description: "Keypad language",         constraints: ["english", "chinese_simplified"]]]
        command "setAutomaticLock", [[name: "enabled*",  type: "ENUM",   description: "Automatic lock on/off",  constraints: ["true", "false"]]]
        command "setRelayOpenTime", [[name: "seconds*",  type: "NUMBER", description: "Auto lock time (1-99s)"]]
        command "setUnlockMode",    [[name: "mode*",     type: "ENUM",   description: "Select unlock mode",      constraints: ["single_unlock", "finger_password", "finger_card", "finger_face", "password_card", "password_face"]]]

        attribute "unlockMethod",    "enum", ["fingerprint", "card", "password", "temporary", "remote", "dynamic", "unknown"]
        attribute "unlockUserId",    "number"
        attribute "lastUnlockInfo",  "string"
        attribute "alarmStatus",     "string"
        attribute "doorbell",        "enum", ["pushed", "idle"]
        attribute "connectionStatus","enum", ["connected", "disconnected", "connecting"]
        attribute "unlockMode",      "string"   // DP 25
        attribute "volume",          "string"   // DP 26 — mute, low, middle, high
        attribute "language",        "string"   // DP 27 — english, chinese_simplified
        attribute "automaticLock",   "string"   // DP 30 — true/false
        attribute "autoLockTime",    "number"   // DP 31 — seconds
        attribute "failedAttempts",  "number"   // DP 34
    }
}

preferences {
    section("Device Connection") {
        input "ipaddress", "text",   title: "Device IP Address:", required: true
        input "devId",     "text",   title: "Device ID:",         required: true
        input "localKey",  "text",   title: "Device Local Key:",  required: true
    }
    section("Device Behaviour") {
        input "relockDelay",   "number", title: "Hubitat remote unlock relock delay (seconds) — applies to unlockDoor command only; physical unlocks use the device's own auto lock time (DP 31):", defaultValue: 5,    required: true
        input "relayDpNum",    "text",   title: "Relay DP number (confirmed: 40):",                                      defaultValue: "40", required: true
        input "doorbellDpNum", "text",   title: "Doorbell DP number (0 = auto-detect):",                                 defaultValue: "0",  required: true
        input "heartbeatSecs", "number", title: "Heartbeat interval (seconds — do not exceed 28, device timeout ~33s):", defaultValue: 25,   required: true
        input "reconnectSecs", "number", title: "Reconnect delay on disconnect (seconds):",                              defaultValue: 3,    required: true
    }
    section("Logging") {
        input "logEnable", "bool", title: "Enable debug logging (auto-disables after 30 minutes)", defaultValue: false
    }
}

// Confirmed unlock record DPs for this TFLCD device
// Value is Base64 encoded binary — byte[3] of decoded payload = user slot number
@groovy.transform.Field static final Map UNLOCK_DPS = [
    "9"  : "remote",      // App remote unlock
    "10" : "fingerprint", // Fingerprint unlock
    "11" : "password",    // PIN/password unlock
    "12" : "card",        // RFID card unlock
]

// Confirmed DP 25 unlock mode values mapped to human-readable labels
@groovy.transform.Field static final Map UNLOCK_MODE_LABELS = [
    "single_unlock"  : "Disability (no verification)",
    "finger_password": "Unlock by code",
    "finger_card"    : "Unlock by card",
    "finger_face"    : "Unlock by card/fingerprint + code",
    "password_card"  : "Unlock by card or fingerprint or code",
    "password_face"  : "Unlock by fingerprint",
]

// Accumulate partial TCP frames between parse() calls
@groovy.transform.Field static final java.util.concurrent.ConcurrentHashMap<String, String> socketBuffer = [:]

// -------------------------------------------------------
//  Lifecycle
// -------------------------------------------------------
def installed() {
    log.info "Installed — initializing"
    sendEvent(name: "lock",            value: "locked")
    sendEvent(name: "unlockMethod",    value: "unknown")
    sendEvent(name: "unlockUserId",    value: 0)
    sendEvent(name: "lastUnlockInfo",  value: "No unlock events yet")
    sendEvent(name: "doorbell",        value: "idle")
    sendEvent(name: "connectionStatus",value: "disconnected")
    initialize()
}

def updated() {
    log.info "Updated — reconnecting"
    unschedule()
    if (logEnable) runIn(1800, logsOff)
    initialize()
}

def initialize() {
    if (!settings.ipaddress || !settings.devId || !settings.localKey) {
        log.warn "Device not configured — enter IP Address, Device ID, and Local Key in preferences"
        sendEvent(name: "connectionStatus", value: "disconnected")
        return
    }
    log.info "Initializing persistent socket connection — v${getVersion()}"
    sendEvent(name: "connectionStatus", value: "connecting")
    socketBuffer[device.id] = ""
    connectSocket()
}

def logsOff() {
    log.warn "Debug logging disabled after 30 minutes"
    device.updateSetting("logEnable", [value: "false", type: "bool"])
}

// -------------------------------------------------------
//  Socket management
// -------------------------------------------------------
def connectSocket() {
    // Validate required settings before attempting connection
    if (!settings.ipaddress || !settings.devId || !settings.localKey) {
        log.warn "Cannot connect — Device IP Address, Device ID, and Device Local Key must all be configured in preferences"
        sendEvent(name: "connectionStatus", value: "disconnected")
        return
    }
    try {
        if (logEnable) log.debug "Opening persistent socket to ${settings.ipaddress}:6668"
        interfaces.rawSocket.connect(settings.ipaddress, 6668,
            byteInterface: true,
            readDelay: 0)
        sendEvent(name: "connectionStatus", value: "connected")
        log.info "Socket connected to ${settings.ipaddress}"

        pauseExecution(300)
        requestStatus()
        scheduleHeartbeat()

    } catch (e) {
        log.error "Socket connect failed: ${e.message}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        scheduleReconnect()
    }
}

def disconnectSocket() {
    unschedule("sendHeartbeat")
    unschedule("scheduleHeartbeat")
    unschedule("scheduleReconnect")
    unschedule("connectSocket")
    try {
        interfaces.rawSocket.close()
    } catch (e) { /* ignore */ }
    sendEvent(name: "connectionStatus", value: "disconnected")
    log.info "Socket disconnected"
}

def scheduleReconnect() {
    // Don't reconnect if settings are incomplete
    if (!settings.ipaddress || !settings.devId || !settings.localKey) return
    int delay = (settings.reconnectSecs ?: 3).toInteger()
    log.info "Reconnecting in ${delay}s"
    runIn(delay, "connectSocket")
}

// -------------------------------------------------------
//  Heartbeat — keeps TCP session alive
// -------------------------------------------------------
def scheduleHeartbeat() {
    int hbSecs = (settings.heartbeatSecs ?: 25).toInteger()
    runIn(hbSecs, "sendHeartbeat")
}

def sendHeartbeat() {
    if (logEnable) log.debug "Sending heartbeat"
    try {
        interfaces.rawSocket.sendMessage(
            hubitat.helper.HexUtils.byteArrayToHexString(generate_payload("status")))
        if (device.currentValue("connectionStatus") == "connected") {
            scheduleHeartbeat()
        }
    } catch (e) {
        if (logEnable) log.debug "Heartbeat failed (already disconnected)"
    }
}

// -------------------------------------------------------
//  socketStatus — called by Hubitat on connect/disconnect
// -------------------------------------------------------
def socketStatus(String message) {
    log.info "Socket status: ${message}"
    if (message.contains("disconnect") || message.contains("error") || message.contains("closed")) {
        sendEvent(name: "connectionStatus", value: "disconnected")
        unschedule("sendHeartbeat")
        unschedule("scheduleHeartbeat")
        scheduleReconnect()
    } else if (message.contains("connect")) {
        sendEvent(name: "connectionStatus", value: "connected")
    }
}

// -------------------------------------------------------
//  Lock capability commands
// -------------------------------------------------------
def lock() {
    String dp = settings.relayDpNum ?: "40"
    if (logEnable) log.debug "lock() — DP ${dp} = false"
    sendTuya(generate_payload("set", [(dp): false]))
    sendEvent(name: "lock", value: "locked")
}

def unlock() { unlockDoor() }

def unlockDoor() {
    String dp = settings.relayDpNum ?: "40"
    if (logEnable) log.debug "unlockDoor() — DP ${dp} = true"
    sendTuya(generate_payload("set", [(dp): true]))
    sendEvent(name: "lock", value: "unlocked")
    // relockDelay only applies here — physical unlocks use device's own auto lock time (DP 31)
    runIn((settings.relockDelay ?: 5).toInteger(), "relock")
}

def relock() {
    sendEvent(name: "lock", value: "locked", descriptionText: "Auto-relocked")
}

def refresh() { requestStatus() }

def requestStatus() {
    if (logEnable) log.debug "Requesting device status"
    sendTuya(generate_payload("status"))
}

// -------------------------------------------------------
//  Device settings commands
// -------------------------------------------------------
def setVolume(String volume) {
    List valid = ["mute", "low", "middle", "high"]
    if (!valid.contains(volume)) {
        log.warn "setVolume: invalid value '${volume}' — must be one of: ${valid.join(', ')}"
        return
    }
    log.info "Setting volume to: ${volume}"
    sendTuya(generate_payload("set", ["26": volume]))
    sendEvent(name: "volume", value: volume)
}

def setLanguage(String language) {
    List valid = ["english", "chinese_simplified"]
    if (!valid.contains(language)) {
        log.warn "setLanguage: invalid value '${language}' — must be english or chinese_simplified"
        return
    }
    log.info "Setting language to: ${language}"
    sendTuya(generate_payload("set", ["27": language]))
    sendEvent(name: "language", value: language)
}

def setAutomaticLock(String enabled) {
    boolean val = (enabled == "true")
    log.info "Setting automatic lock to: ${val}"
    sendTuya(generate_payload("set", ["30": val]))
    sendEvent(name: "automaticLock", value: enabled)
}

def setRelayOpenTime(BigDecimal seconds) {
    int secs = seconds.toInteger()
    if (secs < 1 || secs > 99) {
        log.warn "setRelayOpenTime: value must be between 1 and 99 seconds"
        return
    }
    log.info "Setting auto lock time to: ${secs}s"
    sendTuya(generate_payload("set", ["31": secs]))
    sendEvent(name: "autoLockTime", value: secs)
}

def setUnlockMode(String mode) {
    List valid = ["single_unlock", "finger_password", "finger_card", "finger_face", "password_card", "password_face"]
    if (!valid.contains(mode)) {
        log.warn "setUnlockMode: invalid value '${mode}' — must be one of: ${valid.join(', ')}"
        return
    }
    String label = UNLOCK_MODE_LABELS[mode] ?: mode
    log.info "Setting unlock mode to: ${label}"
    sendTuya(generate_payload("set", ["25": mode]))
    sendEvent(name: "unlockMode", value: mode, descriptionText: label)
}

// -------------------------------------------------------
//  Send over the persistent socket
// -------------------------------------------------------
def sendTuya(byte[] message) {
    String msg = hubitat.helper.HexUtils.byteArrayToHexString(message)
    if (logEnable) log.debug "TX → ${msg}"
    try {
        interfaces.rawSocket.sendMessage(msg)
    } catch (e) {
        log.error "Send failed: ${e.message}"
        sendEvent(name: "connectionStatus", value: "disconnected")
        scheduleReconnect()
    }
}

// -------------------------------------------------------
//  parse() — accumulates TCP stream into complete frames
// -------------------------------------------------------
def parse(String description) {
    if (!description) return

    String buffered = (socketBuffer[device.id] ?: "") + description
    socketBuffer[device.id] = buffered

    while (true) {
        int startIdx = buffered.indexOf("000055AA")
        if (startIdx < 0) {
            socketBuffer[device.id] = ""
            break
        }

        if (startIdx > 0) {
            buffered = buffered.substring(startIdx)
            socketBuffer[device.id] = buffered
        }

        if (buffered.length() < 48) break

        int payloadLen = Integer.parseInt(buffered.substring(24, 32), 16)
        int totalFrameHexLen = (4 + 4 + 4 + 4 + payloadLen) * 2

        if (buffered.length() < totalFrameHexLen) break

        String frameHex = buffered.substring(0, totalFrameHexLen)
        buffered = buffered.substring(totalFrameHexLen)
        socketBuffer[device.id] = buffered

        processFrame(frameHex)
    }
}

// -------------------------------------------------------
//  Process one complete Tuya frame
// -------------------------------------------------------
private void processFrame(String frameHex) {
    try {
        byte[] msg = hubitat.helper.HexUtils.hexStringToByteArray(frameHex)
        if (msg.size() < 16) return

        int cmdByte = msg[11].toInteger() & 0xFF

        if (cmdByte == 9) return

        String statusStr = extractAndDecrypt(msg, cmdByte)
        if (!statusStr) return

        def obj
        try {
            obj = new JsonSlurper().parseText(statusStr)
        } catch (e) {
            if (logEnable) log.debug "Non-JSON payload (ignored): ${statusStr?.take(50)}"
            return
        }

        if (obj?.type == "query") return

        if (logEnable) log.debug "Decrypted payload: ${statusStr}"

        def dps = obj?.dps
        if (dps == null || dps.isEmpty()) return

        if (logEnable) log.debug "DPS: ${dps}"

        dps.each { key, value -> processDP(key.toString(), value) }

    } catch (e) {
        log.warn "processFrame error — ${e.class.simpleName}: ${e.message}"
        if (logEnable) log.debug "Frame that failed: ${frameHex}"
    }
}

// -------------------------------------------------------
//  Extract and decrypt payload from a frame
// -------------------------------------------------------
private String extractAndDecrypt(byte[] msg, int cmdByte) {
    try {
        String protocol_version = ""
        int message_start = 0

        switch (cmdByte) {
            case 7:
                if (msg.size() >= 51) {
                    protocol_version = new String([msg[48], msg[49], msg[50]] as byte[])
                }
                message_start = (protocol_version == "3.1") ? 67 : 63
                if (!protocol_version) protocol_version = "3.3"
                break
            case 8:
                if (msg.size() >= 23) {
                    protocol_version = new String([msg[20], msg[21], msg[22]] as byte[])
                }
                message_start = 35
                if (!protocol_version) protocol_version = "3.3"
                break
            case 10:
                message_start    = 20
                protocol_version = "3.3"
                break
            default:
                if (logEnable) log.debug "Unhandled cmd type ${cmdByte} — attempting offset 20"
                message_start    = 20
                protocol_version = "3.3"
        }

        if (message_start >= msg.size()) return null

        String peek = new String(msg[message_start..-1] as byte[], "UTF-8")
        if (peek.startsWith("{")) return peek

        int end_of_message = 0
        for (int u = message_start; u < msg.size() - 1; u++) {
            if (msg[u] == (byte)0xAA && msg[u+1] == (byte)0x55) {
                end_of_message = u - message_start - 6
                break
            }
        }
        if (end_of_message <= 0) return null

        ByteArrayOutputStream buf = new ByteArrayOutputStream()
        for (int i = message_start; i < end_of_message + message_start; i++) {
            buf.write(msg[i])
        }

        byte[] encPayload = buf.toByteArray()
        if (encPayload.size() == 0) return null

        return decrypt_bytes(encPayload, settings.localKey, false)

    } catch (e) {
        if (logEnable) log.debug "extractAndDecrypt error: ${e.message}"
        return null
    }
}

// -------------------------------------------------------
//  DP processor
// -------------------------------------------------------
private void processDP(String dpKey, def value) {

    // Confirmed unlock record DPs
    if (UNLOCK_DPS.containsKey(dpKey)) {
        handleUnlock(UNLOCK_DPS[dpKey], decodeUserSlot(value.toString()))
        return
    }

    // Relay DP
    if (dpKey == (settings.relayDpNum ?: "40")) {
        String lockState = (value == true || value == "true") ? "unlocked" : "locked"
        sendEvent(name: "lock", value: lockState, descriptionText: "Relay: ${lockState}")
        if (logEnable) log.debug "Lock state: ${lockState}"
        return
    }

    // User-configured doorbell DP
    String dbDp = settings.doorbellDpNum ?: "0"
    if (dbDp != "0" && dpKey == dbDp) {
        handleDoorbell()
        return
    }

    switch (dpKey) {
        case "35":
            if (logEnable) log.debug "Lock state indicator: ${value}"
            break
        case "22":
            if (logEnable) log.debug "App token (ignored)"
            break
        case "25":
            String label = UNLOCK_MODE_LABELS[value.toString()] ?: value.toString()
            if (logEnable) log.debug "Unlock mode: ${label}"
            sendEvent(name: "unlockMode", value: value.toString(), descriptionText: label)
            break
        case "26":
            if (logEnable) log.debug "Volume: ${value}"
            sendEvent(name: "volume", value: value.toString())
            break
        case "27":
            if (logEnable) log.debug "Language: ${value}"
            sendEvent(name: "language", value: value.toString())
            break
        case "30":
            if (logEnable) log.debug "Automatic lock: ${value}"
            sendEvent(name: "automaticLock", value: value.toString())
            break
        case "31":
            if (logEnable) log.debug "Auto lock time: ${value}s"
            sendEvent(name: "autoLockTime", value: value.toInteger())
            break
        case "34":
            if (logEnable) log.debug "Failed attempts: ${value}"
            sendEvent(name: "failedAttempts", value: value.toInteger())
            break
        case "7":
        case "16":
        case "50":
            sendEvent(name: "alarmStatus", value: value.toString(),
                      descriptionText: "Access alarm: ${value}")
            log.warn "ACCESS ALARM: ${value}"
            break
        case "38":
        case "41":
        case "107":
            handleDoorbell()
            break
        default:
            log.info "Unknown DP ${dpKey} = ${value}"
    }
}

// -------------------------------------------------------
//  Decode user slot from Base64 unlock record payload
//
//  Confirmed structure from live testing:
//    byte[0-2] = 0x00 0x00 0x00 (padding)
//    byte[3]   = user slot number
//    byte[4-5] = 0x00 0x00 (padding)
//  Example: "AAAABwAA" → [0,0,0,7,0,0] → user slot 7
// -------------------------------------------------------
private int decodeUserSlot(String base64Value) {
    try {
        byte[] decoded = base64Value.decodeBase64()
        if (decoded.size() >= 4) {
            return Byte.toUnsignedInt(decoded[3])
        }
    } catch (e) {
        if (logEnable) log.debug "Could not decode user slot from: ${base64Value}"
    }
    return 0
}

def resetDoorbell() {
    sendEvent(name: "doorbell", value: "idle")
}

private void handleDoorbell() {
    log.info "DOORBELL pressed"
    sendEvent(name: "doorbell", value: "pushed", descriptionText: "Doorbell pressed", isStateChange: true)
    runIn(2, "resetDoorbell")
}

private void handleUnlock(String method, int userId) {
    String info = "${method.capitalize()} - User slot ${userId}"

    sendEvent(name: "lock",           value: "unlocked",  descriptionText: "Unlocked via ${info}", isStateChange: true)
    sendEvent(name: "unlockMethod",   value: method,      descriptionText: "Method: ${method}",    isStateChange: true)
    sendEvent(name: "unlockUserId",   value: userId,      descriptionText: "User slot: ${userId}", isStateChange: true)
    sendEvent(name: "lastUnlockInfo", value: info,        descriptionText: info,                   isStateChange: true)

    log.info "ACCESS GRANTED — ${method.toUpperCase()}  User slot: ${userId}"

    runIn((settings.relockDelay ?: 5).toInteger(), "relock")
}

// -------------------------------------------------------
//  Tuya payload builder
// -------------------------------------------------------
def generate_payload(String command, Map data = null) {

    if (command == "heartbeat") {
        byte[] header = hubitat.helper.HexUtils.hexStringToByteArray("000055AA000000000000000900000000")
        ByteArrayOutputStream hb = new ByteArrayOutputStream()
        hb.write(header)
        hb.write(hubitat.helper.HexUtils.hexStringToByteArray("000000000000AA55"))
        byte[] full = hb.toByteArray()
        long crc = CRC32b(full, full.size() - 8) & 0xFFFFFFFFL
        String crcHex = Long.toHexString(crc).padLeft(8, '0')
        byte[] crcBytes = hubitat.helper.HexUtils.hexStringToByteArray(crcHex)
        full[full.size()-8] = crcBytes[0]
        full[full.size()-7] = crcBytes[1]
        full[full.size()-6] = crcBytes[2]
        full[full.size()-5] = crcBytes[3]
        return full
    }

    def json_data = payloadTemplates()[command]["command"].clone()

    if (json_data.containsKey("gwId"))  json_data["gwId"]  = settings.devId
    if (json_data.containsKey("devId")) json_data["devId"] = settings.devId
    if (json_data.containsKey("uid"))   json_data["uid"]   = settings.devId
    if (json_data.containsKey("t"))     json_data["t"]     = (new Date().getTime() / 1000).toInteger().toString()
    if (data != null)                   json_data["dps"]   = data

    String json_payload = new groovy.json.JsonBuilder(json_data).toString()
    if (logEnable) log.debug "Payload: ${json_payload}"

    ByteArrayOutputStream output = new ByteArrayOutputStream()
    String encrypted = encrypt(json_payload, settings.localKey, false)

    if (command != "status") {
        output.write("3.3".getBytes("UTF-8"))
        output.write(new byte[12])
        output.write(hubitat.helper.HexUtils.hexStringToByteArray(encrypted))
    } else {
        output.write(hubitat.helper.HexUtils.hexStringToByteArray(encrypted))
    }
    output.write(hubitat.helper.HexUtils.hexStringToByteArray("000000000000aa55"))

    byte[] postfix = output.toByteArray()

    ByteArrayOutputStream final_out = new ByteArrayOutputStream()
    final_out.write(hubitat.helper.HexUtils.hexStringToByteArray("000055aa00000000000000"))
    final_out.write(hubitat.helper.HexUtils.hexStringToByteArray(payloadTemplates()[command]["hexByte"]))
    final_out.write(hubitat.helper.HexUtils.hexStringToByteArray("000000"))
    final_out.write(postfix.size())
    final_out.write(postfix)

    byte[] buf = final_out.toByteArray()

    long crc32     = CRC32b(buf, buf.size() - 8) & 0xFFFFFFFFL
    String hex_crc = Long.toHexString(crc32).padLeft(8, '0')
    byte[] crc_b   = hubitat.helper.HexUtils.hexStringToByteArray(hex_crc)

    buf[buf.size()-8] = crc_b[0]
    buf[buf.size()-7] = crc_b[1]
    buf[buf.size()-6] = crc_b[2]
    buf[buf.size()-5] = crc_b[3]

    return buf
}

def payloadTemplates() {
    return [
        "status": [
            "hexByte": "0a",
            "command": ["devId": "", "gwId": "", "uid": "", "t": ""]
        ],
        "set": [
            "hexByte": "07",
            "command": ["devId": "", "uid": "", "t": ""]
        ]
    ]
}

// -------------------------------------------------------
//  Crypto helpers
// -------------------------------------------------------
def encrypt(String plainText, String secret, boolean encodeB64 = true) {
    def cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(secret.getBytes("UTF-8"), "AES"))
    if (encodeB64) {
        return cipher.doFinal(plainText.getBytes("UTF-8")).encodeBase64().toString()
    } else {
        return cipher.doFinal(plainText.getBytes("UTF-8")).encodeHex().toString()
    }
}

def decrypt_bytes(byte[] cypherBytes, String secret, boolean decodeB64 = false) {
    def cipher = Cipher.getInstance("AES/ECB/PKCS5Padding")
    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(secret.getBytes("UTF-8"), "AES"))
    if (decodeB64) cypherBytes = cypherBytes.decodeBase64()
    return new String(cipher.doFinal(cypherBytes), "UTF-8")
}

def CRC32b(byte[] bytes, int length) {
    long crc = 0xFFFFFFFFL
    for (int i = 0; i < length; i++) {
        long b = Byte.toUnsignedInt(bytes[i])
        crc ^= b
        for (int j = 7; j >= 0; j--) {
            long mask = -(crc & 1L)
            crc = (crc >> 1) ^ (0xEDB88320L & mask)
        }
    }
    return ~crc
}
