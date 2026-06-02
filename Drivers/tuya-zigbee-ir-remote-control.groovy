/**
 * Tuya Zigbee IR Remote Control Driver for Hubitat Elevation
 *
 * Copyright 2024 Sean Anastasi
 * Copyright 2026 Neerav Modi
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at:
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under
 * the License is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF
 * ANY KIND, either express or implied. See the License for the specific language governing
 * permissions and limitations under the License.
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * Version History
 * ───────────────────────────────────────────────────────────────────────────────
 *  0.0.1 2024        Sean Anastasi  — Initial proof-of-concept (author's own description).
 *                                     Learn and send IR codes; map codes to button numbers.
 *                                     https://github.com/luckygerbils/hubitat-tuya-zigbee-ir
 *
 *  0.1   2024        Sean Anastasi  — First viable release. Added forgetCode, importCode,
 *                                     unmapButton; learnedCodeNames and mappedButtons attributes.
 *
 *  1.0b1 2026-06-01  Neerav Modi    — Major enhancement release (beta).
 *
 *                                     Bug fixes:
 *                                     forgetCode now updates learnedCodeNames attribute.
 *                                     mapButton typo (mappButton) corrected.
 *                                     mappedButtons state lost on driver swap — fixed via
 *                                       updateDataValue; restored in configure().
 *                                     push() now logs a clear warning when state is missing
 *                                       rather than silently doing nothing.
 *                                     numberOfButtons attribute now set in installed() and
 *                                       configure() — required for PushableButton capability.
 *                                     Bind Response and Configure Reporting Response now
 *                                       handled cleanly in parse() rather than logged as errors.
 *                                     Zigbee network cluster messages (0x0000, 0x0002, 0x0013,
 *                                       0x8021) silenced from warn to debug.
 *
 *                                     New features:
 *                                     Battery reporting (cluster 0x0001, BatteryPercentageRemaining).
 *                                     capability Battery, capability Configuration, Configure button.
 *                                     importProntoCode: import Pronto hex or Pronto Base64 codes.
 *                                     generateIRCode: generate codes from Flipper Zero IRDB
 *                                       protocol/address/command values. Supported protocols:
 *                                       Samsung32, NEC, NECext, RC5, RC6, SIRC, SIRC15, SIRC20.
 *                                     sendCode: auto-detect Pronto hex, Pronto Base64, and Tuya
 *                                       learned Base64 — all converted to Tuya compressed format.
 *                                     Pronto to Tuya conversion via FastLZ compression, based on
 *                                       mildsunrise's format documentation.
 *                                     Samsung32 timing constants derived from live capture against
 *                                       Samsung UN55EH6001F with AA59-00666A remote.
 *                                     supportedProtocols attribute.
 *                                     10 manufacturer fingerprints added sourced from
 *                                       zigbee-herdsman-converters (Zigbee2MQTT).
 *                                     capability "Actuator" added.
 *                                     sendCode: optional Reps parameter to send a code
 *                                       multiple times with 500ms between each transmission.
 *                                     Developed with the assistance of Claude AI (Anthropic).
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * Supports Tuya TS1201 / ZS06 Zigbee IR blaster devices.
 *
 * Features:
 *   - Learn IR codes from physical remotes
 *   - Import/send codes in Tuya learned Base64 format
 *   - Import/send codes in Pronto hex or Pronto Base64 format (auto-converted to Tuya format)
 *   - Generate codes from Flipper Zero IRDB protocol/address/command values
 *     Supported protocols: Samsung32, NEC, NECext, RC5, RC6, SIRC, SIRC15, SIRC20
 *   - Map learned codes to button numbers for Rule Machine integration
 *   - Battery reporting
 *
 * IR code sources:
 *   Flipper Zero IRDB: https://github.com/Lucaslhm/Flipper-IRDB
 *   Pronto codes:      https://www.remotecentral.com
 *                      https://irdb.globalcache.com
 *
 * Tuya IR compression format documented by mildsunrise:
 *   https://gist.github.com/mildsunrise/1d576669b63a260d2cff35fda63ec0b5
 *
 * Based on original work by Sean Anastasi integrating with Zigbee2MQTT / zigbee-herdsman:
 *   https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/lib/zosung.ts
 *   https://github.com/Koenkk/zigbee-herdsman/blob/master/src/zcl/definition/cluster.ts#L5260-L5359
 *
 * ───────────────────────────────────────────────────────────────────────────────
 * Zigbee protocol notes
 * ───────────────────────────────────────────────────────────────────────────────
 *
 * Zigbee command payloads for the TS1201 are largely hex encoded structs.
 * The toPayload and toStruct functions convert between a Map of struct data
 * and a hex byte string according to a given struct layout definition.
 *
 * learn sequence:
 *  1. hub sends 0xe004 0x00 (learn) with the JSON {"study":0} (as an ASCII hex byte string)
 *  2. device LED illuminates, user sends IR code to the device using original remote
 *  3. device sends 0xed00 0x00 (start transmit) with a sequence value it generates + the code length
 *     - All subsequent messages generally include this same sequence value
 *  4. hub sends 0xed00 0x01 (start transmit ack)
 *  5. device sends 0xed00 0x0B (ACK) with 0x01 as the command being acked
 *  6. hub sends 0xed00 0x02 (code data request) with a position (initially 0)
 *  7. device sends 0xed00 0x03 (code data response) with a chunk of the code data and a crc checksum
 *  [repeat (5) and (6) until the received data length matches the length given in (3)]
 *  8. hub sends 0xed00 0x04 (done sending)
 *  9. device sends 0xed00 0x05 (done receiving)
 *  10. hub sets "lastLearnedCode" (base64 value),
 *      clears data associated with this sequence,
 *      and sends 0xe004 0x00 (learn) with the JSON {"study":1}
 *  11. device LED turns off
 *
 * sendCode sequence:
 *  1. hub sends 0xed00 0x00 (start transmit) with a generated sequence value + the code length
 *     - All subsequent messages generally include this same sequence value
 *  2. device sends 0xed00 0x01 (start transmit ack)
 *     - We ignore this
 *  3. device sends 0xed00 0x02 (code data request) with a position (initially 0)
 *  4. hub sends 0xed00 0x03 (code data response) with a chunk of the code data and a crc checksum
 *  [repeat (3) and (4) until the device sends 0xed00 0x04 (done sending)]
 *  5. device sends 0xed00 0x04 (done sending)
 *  6. hub sends 0xed00 0x05 (done receiving),
 *     clears data associated with this sequence
 *  7. device emits the IR code
 *
 * There are also various "ACK" messages sent after each command.
 * In general, we do nothing in response to these (and the device doesn't appear to require we
 * send them in response to its messages).
 */

import groovy.transform.Field

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

import java.util.concurrent.ConcurrentHashMap

// These BEGIN and END comments are so this section can be snipped out in unit tests.
// I'm not sure what's necessary to make this syntax work in standard Groovy
// BEGIN METADATA
metadata {
    definition (
        name:      "Tuya Zigbee IR Remote Control",
        namespace: "neeravmodi",
        author:    "Sean Anastasi, Neerav Modi",
        importUrl: "https://raw.githubusercontent.com/neeravmodi/Hubitat/main/Drivers/tuya-zigbee-ir-remote-control.groovy"
    ) {
        capability "PushableButton"
        capability "Battery"
        capability "Configuration"
        capability "Actuator"

        command "learn", [
            [name: "Code Name", type: "STRING", description: "Name for the learned code (optional — if omitted, code is stored in lastLearnedCode only)"]
        ]
        command "importCode", [
            [name: "Code Name*", type: "STRING", description: "Name for the imported code"],
            [name: "Base64 Code*", type: "STRING", description: "Tuya learned Base64 code to import"]
        ]
        command "importProntoCode", [
            [name: "Code Name*", type: "STRING", description: "Name for the imported code"],
            [name: "Pronto Code*", type: "STRING", description: "Pronto hex (0000 006D ...) or Pronto Base64 (JgBG...)"]
        ]
        command "generateIRCode", [
            [name: "Code Name*", type: "STRING", description: "Name for the generated code"],
            [name: "Protocol*",  type: "STRING", description: "Protocol — see supportedProtocols attribute for valid values"],
            [name: "Address*",   type: "STRING", description: "Address in Flipper IRDB format e.g. 07 00 00 00"],
            [name: "Command*",   type: "STRING", description: "Command in Flipper IRDB format e.g. 02 00 00 00"]
        ]
        command "sendCode", [
            [name: "Code*", type: "STRING", description: "Name of stored code, Tuya learned Base64, Pronto hex (0000 006D...), or Pronto Base64 (JgBG...)"],
            [name: "Reps",  type: "NUMBER", description: "Number of times to send the code (default: 1)"]
        ]
        command "forgetCode", [
            [name: "Code Name*", type: "STRING", description: "Name of stored code to delete"]
        ]
        command "mapButton", [
            [name: "Button*",    type: "NUMBER", description: "Button number to map (1-50)"],
            [name: "Code Name*", type: "STRING", description: "Name of stored code to map to this button"]
        ]
        command "unmapButton", [
            [name: "Button*", type: "NUMBER", description: "Button number to unmap"]
        ]

        attribute "learnedCodeNames",  "STRING"
        attribute "lastLearnedCode",   "STRING"
        attribute "lastPushedButton",  "STRING"
        attribute "lastPushedCodeName","STRING"
        attribute "mappedButtons",     "STRING"
        attribute "supportedProtocols","STRING"

        // Fingerprints sourced from zigbee-herdsman-converters (Zigbee2MQTT)
        // https://github.com/Koenkk/zigbee-herdsman-converters/blob/master/src/devices/tuya.ts
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_7v1k4vufotpowp9z",  model: "TS1201", deviceJoinName: "Tuya ZS06 IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_ot6ewjvmejq5ekhl", model: "TS1201", deviceJoinName: "Moes UFO-R11 IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_rlkmy85q4pzoxobl", model: "TS1201", deviceJoinName: "Tuya UFO-R4Z IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_jxvzqatwgsaqzx1u", model: "TS1201", deviceJoinName: "QA QAIRZPRO IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_lypnqvlem5eq1ree", model: "TS1201", deviceJoinName: "QA QAIRZPRO IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_yac64inudpovoaba", model: "TS1201", deviceJoinName: "QA QAIRZM2 IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_uc8lwbi2",          model: "TS1201", deviceJoinName: "Zemismart ZM-18-USB IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_8xzb2ghn",          model: "TS1201", deviceJoinName: "Zemismart ZXMIR-02 IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_s6ezpa3j",          model: "TS1201", deviceJoinName: "Ekaza EKAT-T304Z IR Remote Control"
        fingerprint profileId: "0104", inClusters: "0000,0001,0004,0005,0003,ED00,E004,0006", outClusters: "0019,000A", manufacturer: "_TZ3290_acv1iuslxi3shaaj", model: "TS1201", deviceJoinName: "Aubess ZXZIR-02 IR Remote Control"
    }

    preferences {
        input name: "logLevel", type: "enum", title: "Log Level",
              description: "Override logging level. Default is INFO.<br>DEBUG level will reset to INFO after 30 minutes",
              options: ["DEBUG","INFO","WARN","ERROR"], required: true, defaultValue: "INFO"
    }
}
// END METADATA

/*
 * Semi-persistent data
 * We don't need this permanently in state, but we do need it between message executions so @Field alone doesn't work.
 */
/* deviceId -> seq -> { buffer: List<byte> } */
@Field static final Map<String, Map<Integer, Map>> SEND_BUFFERS = new ConcurrentHashMap()
def sendBuffers() { return SEND_BUFFERS.computeIfAbsent(device.id, { k -> new HashMap<>() }) }
/* deviceId -> seq -> { expectedBufferLength: int, buffer: List<byte> } */
@Field static final Map<String, Map<Integer, Map>> RECEIVE_BUFFERS = new ConcurrentHashMap()
def receiveBuffers() { return RECEIVE_BUFFERS.computeIfAbsent(device.id, { k -> new HashMap<>() }) }
/* deviceId -> Stack<String|null> */
@Field static final Map<String, List<String>> PENDING_LEARN_CODE_NAMES = new ConcurrentHashMap()
def pendingLearnCodeNames() { return PENDING_LEARN_CODE_NAMES.computeIfAbsent(device.id, { k -> new LinkedList<>() }) }
/* deviceId -> Stack<Integer> */
@Field static final Map<String, List<Integer>> PENDING_RECEIVE_SEQS = new ConcurrentHashMap()
def pendingReceiveSeqs() { return PENDING_RECEIVE_SEQS.computeIfAbsent(device.id, { k -> new LinkedList<>() }) }

@Field static final String SUPPORTED_PROTOCOLS = "Samsung32, NEC, NECext, RC5, RC6, SIRC, SIRC15, SIRC20"

static String version()   { "1.0b1" }
static String timeStamp() { "2026-06-01" }

/*********
 * LIFECYCLE
 */

def installed() {
    info "installed()"
    sendEvent(name: "numberOfButtons",   value: 50,                    descriptionText: "numberOfButtons set to 50")
    sendEvent(name: "supportedProtocols",value: SUPPORTED_PROTOCOLS,   descriptionText: "")
}

def updated() {
    info "updated()"
    switch (logLevel) {
    case "DEBUG":
        debug "log level is DEBUG. Will reset to INFO after 30 minutes"
        runIn(1800, "resetLogLevel")
        break
    case "INFO":  info  "log level is INFO";  break
    case "WARN":  warn  "log level is WARN";  break
    case "ERROR": error "log level is ERROR"; break
    default:      error "Unexpected logLevel: ${logLevel}"
    }
}

def configure() {
    info "configure()"
    sendEvent(name: "numberOfButtons",   value: 50,                  descriptionText: "numberOfButtons set to 50")
    sendEvent(name: "supportedProtocols",value: SUPPORTED_PROTOCOLS, descriptionText: "")
    // Restore mappedButtons from device data if state was cleared (e.g. after a driver swap)
    if (state.mappedButtons == null) {
        final String saved = getDataValue("mappedButtons")
        if (saved) {
            state.mappedButtons = new groovy.json.JsonSlurper().parseText(saved)
            info "restored mappedButtons from device data"
        }
    }
    def cmds = zigbee.configureReporting(0x0001, 0x0021, DataType.UINT8, 3600, 21600, 0x01) +
               zigbee.readAttribute(0x0001, 0x0021)
    sendHubCommand(new hubitat.device.HubMultiAction(cmds, hubitat.device.Protocol.ZIGBEE))
}

/*********
 * COMMANDS — IR CODE MANAGEMENT
 */

/**
 * Trigger the device's learn mode. Point the original remote at the device and press the button.
 * The learned code is stored in lastLearnedCode. If codeName is supplied, it is also stored
 * by name in learnedCodes and appears in learnedCodeNames.
 */
def learn(final String optionalCodeName) {
    info "learn(${optionalCodeName})"
    pendingLearnCodeNames().push(optionalCodeName)
    sendLearn(true)
}

/**
 * Import a Tuya learned Base64 code by name.
 * Use this to restore codes saved from lastLearnedCode after a driver swap.
 */
def importCode(final String codeName, final String base64Code) {
    info "importCode(${codeName})"
    final Map learnedCodes = state.computeIfAbsent("learnedCodes", {k -> new HashMap()})
    learnedCodes[codeName] = base64Code.replaceAll("\\s", "")
    sendEvent(name: "learnedCodeNames", value: learnedCodes.sort().toString(), descriptionText: "")
}

/**
 * Import a Pronto format IR code by name.
 * Accepts either raw Pronto hex (0000 006D ...) or Pronto Base64 (JgBG...).
 * The code is converted to Tuya compressed format before storage.
 */
def importProntoCode(final String codeName, final String prontoInput) {
    info "importProntoCode(${codeName})"
    final String tuya = prontoToTuya(prontoInput)
    if (tuya == null) {
        error "importProntoCode: failed to convert Pronto code for '${codeName}'"
        return
    }
    final Map learnedCodes = state.computeIfAbsent("learnedCodes", {k -> new HashMap()})
    learnedCodes[codeName] = tuya
    sendEvent(name: "learnedCodeNames", value: learnedCodes.sort().toString(), descriptionText: "")
    info "importProntoCode: stored '${codeName}'"
}

/**
 * Generate and store an IR code from Flipper Zero IRDB protocol/address/command values.
 *
 * Protocol must be one of the values in the supportedProtocols attribute.
 * Address and Command are entered in Flipper IRDB format: space-separated hex bytes, e.g. "07 00 00 00".
 *
 * Example — Samsung TV volume up from Flipper IRDB Samsung_AA59-00602A.ir:
 *   Code Name: VolumeUp
 *   Protocol:  Samsung32
 *   Address:   07 00 00 00
 *   Command:   07 00 00 00
 *
 * Samsung32 timing constants were derived from a live capture against a Samsung UN55EH6001F
 * with AA59-00666A remote. Other Samsung TVs using Samsung32 with address 07 should be
 * compatible; verify by testing Power before mapping all buttons.
 */
def generateIRCode(final String codeName, final String protocol, final String addressStr, final String commandStr) {
    info "generateIRCode(${codeName}, ${protocol}, ${addressStr}, ${commandStr})"
    try {
        final List<Integer> addrBytes = addressStr.trim().split("\\s+").collect { Integer.parseInt(it, 16) }
        final List<Integer> cmdBytes  = commandStr.trim().split("\\s+").collect { Integer.parseInt(it, 16) }
        final List<Integer> timings   = protocolTimings(protocol.trim(), addrBytes, cmdBytes)
        if (timings == null) {
            error "generateIRCode: unsupported protocol '${protocol}'. Supported: ${SUPPORTED_PROTOCOLS}"
            return
        }
        final String tuya = encodeTuya(timings)
        final Map learnedCodes = state.computeIfAbsent("learnedCodes", {k -> new HashMap()})
        learnedCodes[codeName] = tuya
        sendEvent(name: "learnedCodeNames", value: learnedCodes.sort().toString(), descriptionText: "")
        info "generateIRCode: stored '${codeName}' protocol=${protocol}"
    } catch (Exception e) {
        error "generateIRCode error: ${e.message}"
    }
}

/**
 * Send an IR code. Accepts:
 *   - Name of a stored code (learned, imported, or generated)
 *   - Tuya learned Base64 string (direct paste from lastLearnedCode)
 *   - Pronto hex string (0000 006D ...)
 *   - Pronto Base64 string (JgBG...)
 *
 * Pronto codes are converted to Tuya compressed format before sending.
 */
def sendCode(final String codeNameOrBase64CodeInput, final BigDecimal reps = 1) {
    info "sendCode(${codeNameOrBase64CodeInput}, reps=${reps})"

    // 1. Check stored codes by name first
    String learnedCode = null
    if (state.learnedCodes != null) {
        learnedCode = state.learnedCodes[codeNameOrBase64CodeInput]
    }

    final String actualCode

    if (learnedCode != null) {
        // Named stored code — already in Tuya format
        actualCode = learnedCode
    } else {
        final String cleaned = codeNameOrBase64CodeInput.replaceAll("\\s+", " ").trim()
        if (cleaned =~ /^[0-9A-Fa-f]{4}( [0-9A-Fa-f]{4})+$/) {
            // Raw Pronto hex string e.g. "0000 006D 0000 0022 ..." — convert to Tuya format
            final String converted = prontoToTuya(cleaned)
            if (converted == null) {
                error "sendCode: Pronto conversion failed"
                return
            }
            actualCode = converted
            info "sendCode: detected raw Pronto hex, converted to Tuya format"
        } else if (isProntoBase64(cleaned.replaceAll("\\s", ""))) {
            // Pronto Base64 e.g. "JgBG..." — convert to Tuya format
            final String prontoHex = cleaned.replaceAll("\\s", "").decodeBase64().collect { b ->
                String.format("%02X", b & 0xFF)
            }.collate(2).collect { pair -> pair.join("") }.join(" ")
            final String converted = prontoToTuya(prontoHex)
            if (converted == null) {
                error "sendCode: Pronto Base64 conversion failed"
                return
            }
            actualCode = converted
            info "sendCode: detected Pronto Base64, converted to Tuya format"
        } else {
            // Remove all whitespace since we added newlines to the lastLearnedCode attribute
            // + the Hubitat UI may add extra spaces. Assume Tuya learned Base64.
            actualCode = cleaned.replaceAll("\\s", "")
            info "sendCode: treating as Tuya learned Base64"
        }
    }

    // JSON format copied from zigbee-herdsman-converters
    // Unclear if any of this can be tweaked to get different behavior
    // type 1 = Tuya device learned/compressed format (the only supported type for this device)
    final String jsonToSend = "{\"key_num\":1,\"delay\":300,\"key1\":{\"num\":1,\"freq\":38000,\"type\":1,\"key_code\":\"${actualCode}\"}}"
    debug "JSON to send: ${jsonToSend}"

    final int count = (reps != null && reps > 1) ? reps.intValue() : 1
    for (int i = 0; i < count; i++) {
        if (i > 0) pauseExecution(500)
        def seq = nextSeq()
        sendBuffers()[seq] = [buffer: jsonToSend.bytes as List]
        sendStartTransmit(seq, jsonToSend.bytes.length)
    }
}

/**
 * Delete a stored code by name.
 */
def forgetCode(final String codeName) {
    info "forgetCode(${codeName})"
    if (state.learnedCodes == null) return
    state.learnedCodes.remove(codeName)
    sendEvent(name: "learnedCodeNames", value: state.learnedCodes.sort().toString(), descriptionText: "")
}

/*********
 * COMMANDS — BUTTON MAPPING
 */

/**
 * Map a button number to a stored code name.
 * Once mapped, push(N) will send the code assigned to button N.
 * Mappings persist across driver swaps via updateDataValue.
 */
def mapButton(final BigDecimal button, final String codeName) {
    info "mapButton(${button}, ${codeName})"
    final Map mappedButtons = state.computeIfAbsent("mappedButtons", {k -> new HashMap()})
    mappedButtons[button.toString()] = codeName
    sendEvent(name: "mappedButtons", value: state.mappedButtons.sort(), descriptionText: "mapped button")
    updateDataValue("mappedButtons", groovy.json.JsonOutput.toJson(state.mappedButtons))
}

/**
 * Remove the code mapping for a button number.
 */
def unmapButton(final BigDecimal button) {
    info "unmapButton(${button})"
    if (state.mappedButtons == null) return
    state.mappedButtons.remove(button.toString())
    sendEvent(name: "mappedButtons", value: state.mappedButtons.sort(), descriptionText: "unmapped button")
    updateDataValue("mappedButtons", groovy.json.JsonOutput.toJson(state.mappedButtons))
}

/**
 * Send the IR code mapped to a button number.
 * Called by Rule Machine or other automations via PushableButton capability.
 */
def push(final BigDecimal button) {
    info "push(${button})"
    if (state.mappedButtons == null) {
        warn "push: no button mappings in state — run Configure to restore"
        return
    }
    final String codeName = state.mappedButtons[button.toString()]
    if (codeName == null) {
        warn "push: button ${button} is not mapped"
    } else {
        sendCode(codeName)
        sendEvent(name: "lastPushedButton",   value: button,    descriptionText: "")
        sendEvent(name: "lastPushedCodeName", value: codeName,  descriptionText: "")
    }
}

/*********
 * ZIGBEE MESSAGE HANDLING
 */

def parse(final String description) {
    final def descMap = zigbee.parseDescriptionAsMap(description)

    switch (descMap.clusterInt) {
    case LEARN_CLUSTER:
        switch (Integer.parseInt(descMap.command, 16)) {
        case LEARN_CLUSTER_LEARN:
            debug "received ${LEARN_CLUSTER_LEARN} (learn): ${descMap.data}"
            break
        case LEARN_CLUSTER_ACK:
            debug "received ${LEARN_CLUSTER_ACK} (learn ack): ${descMap.data}"
            break
        default:
            debug "received unknown learn cluster message: ${descMap.command}"
        }
        break

    case TRANSMIT_CLUSTER:
        switch (Integer.parseInt(descMap.command, 16)) {
        case TRANSMIT_CLUSTER_START_TRANSMIT:
            debug "received ${TRANSMIT_CLUSTER_START_TRANSMIT} (start transmit): ${descMap.data}"
            handleStartTransmit(parseStartTransmit(descMap.data))
            break
        case TRANSMIT_CLUSTER_START_TRANSMIT_ACK:
            debug "received ${TRANSMIT_CLUSTER_START_TRANSMIT_ACK} (start transmit ack): ${descMap.data}"
            // I think this is just an ACK of the received initial msg 0 — nothing to do here
            break
        case TRANSMIT_CLUSTER_CODE_DATA_REQUEST:
            debug "received ${TRANSMIT_CLUSTER_CODE_DATA_REQUEST} (code data request): ${descMap.data}"
            handleCodeDataRequest(parseCodeDataRequest(descMap.data))
            break
        case TRANSMIT_CLUSTER_CODE_DATA_RESPONSE:
            debug "received ${TRANSMIT_CLUSTER_CODE_DATA_RESPONSE} (code data response): ${descMap.data}"
            handleCodeDataResponse(parseCodeDataResponse(descMap.data))
            break
        case TRANSMIT_CLUSTER_DONE_SENDING:
            debug "received ${TRANSMIT_CLUSTER_DONE_SENDING} (done sending): ${descMap.data}"
            handleDoneSending(parseDoneSending(descMap.data))
            break
        case TRANSMIT_CLUSTER_DONE_RECEIVING:
            debug "received ${TRANSMIT_CLUSTER_DONE_RECEIVING} (done receiving): ${descMap.data}"
            handleDoneReceiving(parseDoneReceiving(descMap.data))
            break
        case TRANSMIT_CLUSTER_ACK:
            debug "received ${TRANSMIT_CLUSTER_ACK} (ack): ${descMap.data}"
            handleAck(parseAck(descMap.data))
            break
        default:
            debug "received unknown transmit cluster message: ${descMap.command}"
        }
        break

    case 0x0001: // Power Configuration cluster
        if (descMap.command == "07") {
            debug "Configure Reporting Response, status: ${descMap.data[0] == "00" ? "success" : "failed"}"
        } else if (descMap.attrInt == 0x0021 && descMap.value) {
            // BatteryPercentageRemaining — Zigbee reports 0-200, divide by 2 for 0-100%
            int pct = Math.round(Integer.parseInt(descMap.value, 16) / 2)
            sendEvent(name: "battery", value: pct, unit: "%", descriptionText: "${device} battery is ${pct}%")
            info "battery: ${pct}%"
        } else if (descMap.attrInt == 0x0020 && descMap.value) {
            debug "BatteryVoltage raw: ${descMap.value} (${Integer.parseInt(descMap.value, 16) * 100}mV)"
        } else {
            debug "Power Configuration cluster, unhandled attr 0x${Integer.toHexString(descMap.attrInt ?: 0)}: ${descMap}"
        }
        break

    case 0x0000: // Basic cluster — Read Attributes Response, ignore silently
    case 0x0002: // Node Descriptor Response — Zigbee network housekeeping, ignore silently
    case 0x0013: // Device Announce — expected on rejoin, ignore silently
    case 0x8021: // Bind Response — Zigbee network housekeeping, ignore silently
        debug "Zigbee network message, cluster 0x${Integer.toHexString(descMap.clusterInt)}: ${descMap.data}"
        break

    default:
        warn "received unknown message from unknown cluster: 0x${descMap.command} (cluster 0x${Integer.toHexString(descMap.clusterInt)}). Ignoring"
        debug "descMap = ${descMap}"
        break
    }
}

/*
 * Learn command cluster (0xE004)
 */
@Field static final int LEARN_CLUSTER       = 0xe004
@Field static final int LEARN_CLUSTER_LEARN = 0x00
@Field static final int LEARN_CLUSTER_ACK   = 0x0B

String newLearnMessage(final boolean learn) {
    return command(LEARN_CLUSTER, LEARN_CLUSTER_LEARN, toPayload("{\"study\":${learn ? 0 : 1}}".bytes))
}

def sendLearn(final boolean learn) {
    final def cmd = newLearnMessage(learn)
    debug "sending (learn(${learn})): ${cmd}"
    doSendHubCommand(cmd)
}

/*
 * Transmit command cluster (0xED00)
 */
@Field static final int TRANSMIT_CLUSTER = 0xed00

/** 0x0B ACK */
@Field static final int TRANSMIT_CLUSTER_ACK = 0x0B
@Field static final def ACK_PAYLOAD_FORMAT = [
    [name: "cmd", type: "uint16"],
]

Map parseAck(final List<String> payload) { return toStruct(ACK_PAYLOAD_FORMAT, payload) }

def handleAck(final Map message) {
    switch (message.cmd) {
    case TRANSMIT_CLUSTER_START_TRANSMIT_ACK:
        // This is the only ack we care about.
        // zigbee-herdsman-converters handles this with a fixed delay after 0x00,
        // but responding to the ack directly is more reliable.
        sendCodeDataRequest(pendingReceiveSeqs().pop(), 0)
        break
    }
}

/** 0x00 Start Transmit */
@Field static final int TRANSMIT_CLUSTER_START_TRANSMIT = 0x00
@Field static final def START_TRANSMIT_PAYLOAD_FORMAT = [
    [name: "seq",    type: "uint16"],
    [name: "length", type: "uint32"],
    [name: "unk1",   type: "uint32"],
    [name: "unk2",   type: "uint16"], // Cluster Id?
    [name: "unk3",   type: "uint8"],
    [name: "cmd",    type: "uint8"],
    [name: "unk4",   type: "uint16"],
]

def newStartTransmitMessage(final int seq, final int length) {
    return command(TRANSMIT_CLUSTER, TRANSMIT_CLUSTER_START_TRANSMIT,
        toPayload(START_TRANSMIT_PAYLOAD_FORMAT, [
            seq: seq, length: length, unk1: 0,
            unk2: LEARN_CLUSTER, // This seems to be what this is set to for some reason
            unk3: 0x01, cmd: 0x02, unk4: 0
        ])
    )
}

def sendStartTransmit(final int seq, final int length) {
    final def cmd = newStartTransmitMessage(seq, length)
    debug "sending (start transmit): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseStartTransmit(final List<String> payload) { return toStruct(START_TRANSMIT_PAYLOAD_FORMAT, payload) }

def handleStartTransmit(final Map message) {
    pendingReceiveSeqs().push(message.seq)
    receiveBuffers()[message.seq] = [expectedBufferLength: message.length, buffer: []]
    sendStartTransmitAck(message)
}

/**
 * 0x01 Start Transmit ACK
 * Unclear what this is for, but it must be sent before 0x02.
 * The body is the same as 0x00 with an extra zero byte prepended.
 */
@Field static final int TRANSMIT_CLUSTER_START_TRANSMIT_ACK = 0x01
@Field static final def START_TRANSMIT_ACK_PAYLOAD_FORMAT = [
    [name: "zero",   type: "uint8"],
    [name: "seq",    type: "uint16"],
    [name: "length", type: "uint32"],
    [name: "unk1",   type: "uint32"],
    [name: "unk2",   type: "uint16"], // Cluster Id?
    [name: "unk3",   type: "uint8"],
    [name: "cmd",    type: "uint8"],
    [name: "unk4",   type: "uint16"],
]

String newStartTransmitAckMessage(final int seq, final int length) {
    return command(TRANSMIT_CLUSTER, TRANSMIT_CLUSTER_START_TRANSMIT_ACK,
        toPayload(START_TRANSMIT_ACK_PAYLOAD_FORMAT, [
            zero: 0, seq: seq, length: length, unk1: 0,
            unk2: LEARN_CLUSTER, // This seems to be what this is set to for some reason
            unk3: 0x01, cmd: 0x02, unk4: 0
        ])
    )
}

void sendStartTransmitAck(final Map message) {
    final def cmd = newStartTransmitAckMessage(message.seq, message.length)
    debug "sending (start transmit ack): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseStartTransmitAck(final List<String> payload) { return toStruct(START_TRANSMIT_ACK_PAYLOAD_FORMAT, payload) }

/** 0x02 Code Data Request */
@Field static final int TRANSMIT_CLUSTER_CODE_DATA_REQUEST = 0x02
@Field static final def CODE_DATA_REQUEST_PAYLOAD_FORMAT = [
    [name: "seq",      type: "uint16"],
    [name: "position", type: "uint32"],
    [name: "maxlen",   type: "uint8"],
]

String newCodeDataRequestMessage(final int seq, final int position) {
    return command(TRANSMIT_CLUSTER, TRANSMIT_CLUSTER_CODE_DATA_REQUEST,
        toPayload(CODE_DATA_REQUEST_PAYLOAD_FORMAT, [
            seq: seq, position: position,
            maxlen: 0x38 // Limits? Unknown, this default copied from zigbee-herdsman-converters
        ])
    )
}

void sendCodeDataRequest(final int seq, final int position) {
    final def cmd = newCodeDataRequestMessage(seq, position)
    debug "sending (code data request): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseCodeDataRequest(final List<String> payload) { return toStruct(CODE_DATA_REQUEST_PAYLOAD_FORMAT, payload) }

def handleCodeDataRequest(final Map message) {
    final int position = message.position
    final List<Byte> buffer = sendBuffers()[message.seq].buffer
    // Apparently 55 bytes at a time. TODO: experiment, should this be maxlen bytes?
    final byte[] part = buffer.subList(position, Math.min(position + 55, buffer.size())) as byte[]
    sendCodeDataResponse(message.seq, position, part, checksum(part))
}

/** 0x03 Code Data Response */
@Field static final int TRANSMIT_CLUSTER_CODE_DATA_RESPONSE = 0x03
@Field static final def CODE_DATA_RESPONSE_PAYLOAD_FORMAT = [
    [name: "zero",       type: "uint8"],
    [name: "seq",        type: "uint16"],
    [name: "position",   type: "uint32"],
    [name: "msgpart",    type: "octetStr"],
    [name: "msgpartcrc", type: "uint8"],
]

String newCodeDataResponseMessage(final int seq, final int position, final byte[] data, final int crc) {
    return command(TRANSMIT_CLUSTER, TRANSMIT_CLUSTER_CODE_DATA_RESPONSE,
        toPayload(CODE_DATA_RESPONSE_PAYLOAD_FORMAT, [
            zero: 0, seq: seq, position: position, msgpart: data, msgpartcrc: crc
        ])
    )
}

void sendCodeDataResponse(final int seq, final int position, final byte[] data, final int crc) {
    final def cmd = newCodeDataResponseMessage(seq, position, data, crc)
    debug "sending (code data response, position: ${position}) ${cmd}"
    doSendHubCommand(cmd)
}

Map parseCodeDataResponse(final List<String> payload) { return toStruct(CODE_DATA_RESPONSE_PAYLOAD_FORMAT, payload) }

def handleCodeDataResponse(final Map message) {
    final Map seqData = receiveBuffers()[message.seq]
    if (seqData == null) { log.error "handleCodeDataResponse: unexpected seq ${message.seq}"; return }

    final List<Byte> buffer = seqData.buffer
    final int position = message.position
    if (position != buffer.size) { log.error "handleCodeDataResponse: position mismatch, expected ${buffer.size} was ${position}"; return }

    final int actualCrc   = checksum(message.msgpart)
    final int expectedCrc = message.msgpartcrc
    if (actualCrc != expectedCrc) { log.error "handleCodeDataResponse: CRC mismatch, expected ${expectedCrc} was ${actualCrc}"; return }

    buffer.addAll(message.msgpart)
    if (buffer.size < seqData.expectedBufferLength) {
        sendCodeDataRequest(message.seq, buffer.size)
    } else {
        sendDoneSending(message.seq)
    }
}

/** 0x04 Done Sending */
@Field static final int TRANSMIT_CLUSTER_DONE_SENDING = 0x04
@Field static final def DONE_SENDING_PAYLOAD_FORMAT = [
    [name: "zero1", type: "uint8"],
    [name: "seq",   type: "uint16"],
    [name: "zero2", type: "uint16"],
]

String newDoneSendingMessage(final int seq) {
    return command(TRANSMIT_CLUSTER, TRANSMIT_CLUSTER_DONE_SENDING,
        toPayload(DONE_SENDING_PAYLOAD_FORMAT, [zero1: 0, seq: seq, zero2: 0])
    )
}

def sendDoneSending(final int seq) {
    final def cmd = newDoneSendingMessage(seq)
    debug "sending (done sending) ${cmd}"
    doSendHubCommand(cmd)
}

Map parseDoneSending(final List<String> payload) { return toStruct(DONE_SENDING_PAYLOAD_FORMAT, payload) }

def handleDoneSending(final Map message) {
    info "code fully sent"
    sendBuffers().remove(message.seq)
    sendDoneReceiving(message.seq)
}

/** 0x05 Done Receiving */
@Field static final int TRANSMIT_CLUSTER_DONE_RECEIVING = 0x05
@Field static final def DONE_RECEIVING_PAYLOAD_FORMAT = [
    [name: "seq",  type: "uint16"],
    [name: "zero", type: "uint16"],
]

String newDoneReceivingMessage(final int seq) {
    return command(TRANSMIT_CLUSTER, TRANSMIT_CLUSTER_DONE_RECEIVING,
        toPayload(DONE_RECEIVING_PAYLOAD_FORMAT, [seq: seq, zero: 0])
    )
}

def sendDoneReceiving(final int seq) {
    final def cmd = newDoneReceivingMessage(seq)
    debug "sending (done receiving): ${cmd}"
    doSendHubCommand(cmd)
}

Map parseDoneReceiving(final List<String> payload) { return toStruct(DONE_RECEIVING_PAYLOAD_FORMAT, payload) }

def handleDoneReceiving(final Map message) {
    final Map seqData = receiveBuffers().remove(message.seq)
    final String code = encodeBase64(seqData.buffer.toArray() as byte[])
    info "learned code: ${code}"

    // Add a newline every 25 characters so it wraps on the Hubitat UI.
    // Otherwise the code overflows the page, making it hard to copy.
    // Whitespace is stripped in sendCode to undo this.
    final String eventValue = code.split("(?<=\\G.{25})").join("\n")
    doSendEvent(name: "lastLearnedCode", value: eventValue, descriptionText: "${device} lastLearnedCode is ${code}".toString())

    final String optionalCodeName = pendingLearnCodeNames().pop()
    if (optionalCodeName != null) {
        final Map learnedCodes = state.computeIfAbsent("learnedCodes", {k -> new HashMap()})
        learnedCodes[optionalCodeName] = code
        sendEvent(name: "learnedCodeNames", value: learnedCodes.sort().toString(), descriptionText: "")
    }

    sendLearn(false)
}

/*************
 * IR CODE FORMAT CONVERSION
 */

/**
 * Convert a Pronto hex string to a Tuya IR Base64 code.
 * Pronto format: space-separated 4-hex-digit words.
 * Process: parse carrier frequency + timing words -> microsecond durations
 *          -> pack as LE uint16 pairs -> FastLZ compress -> Base64
 *
 * Based on mildsunrise's documentation:
 * https://gist.github.com/mildsunrise/1d576669b63a260d2cff35fda63ec0b5
 */
private String prontoToTuya(final String prontoHex) {
    try {
        final String cleaned = prontoHex.replaceAll("\\s+", " ").trim()
        // Handle Pronto Base64 input: detect and convert to hex first
        final String hexStr
        if (cleaned =~ /^[0-9A-Fa-f]{4}( [0-9A-Fa-f]{4})+$/) {
            hexStr = cleaned
        } else {
            // Assume Pronto Base64 — decode to hex words
            final byte[] decoded = cleaned.replaceAll("\\s", "").decodeBase64()
            hexStr = decoded.collect { b -> String.format("%02X", b & 0xFF) }
                            .collate(2).collect { pair -> pair.join("") }.join(" ")
        }

        final List<Integer> words = hexStr.split("\\s+").collect { Integer.parseInt(it, 16) }
        if (words.size() < 6) { error "prontoToTuya: too few Pronto words (${words.size()})"; return null }

        // Word 0: type (0000=learned), Word 1: freq code, Word 2: burst pairs, Word 3: repeat pairs
        // Words 4+: timing data in carrier cycles
        final int    freqWord = words[1]
        final double freqHz   = 1000000.0 / (freqWord * 0.241246)
        final double periodUs = 1000000.0 / freqHz

        // Convert carrier cycle counts to microseconds, clamped to uint16
        final List<Integer> timingsUs = words.subList(4, words.size()).collect { w ->
            Math.min(65535, (int) Math.round(w * periodUs))
        }
        return encodeTuya(timingsUs)
    } catch (Exception e) {
        error "prontoToTuya error: ${e.message}"
        return null
    }
}

/**
 * Detect Pronto Base64: Pronto data always starts with 0x0000 (two zero bytes).
 * Tuya compressed data never starts with two zero bytes.
 */
private boolean isProntoBase64(final String b64) {
    try {
        final byte[] decoded = b64.decodeBase64()
        return decoded.length >= 2 && decoded[0] == 0 && decoded[1] == 0
    } catch (Exception e) {
        return false
    }
}

/**
 * Pack a list of microsecond timing values as little-endian uint16 pairs,
 * FastLZ compress, and Base64 encode — producing a Tuya IR code string.
 */
private String encodeTuya(final List<Integer> timingsUs) {
    final ByteArrayOutputStream rawBaos = new ByteArrayOutputStream()
    timingsUs.each { t ->
        final int clamped = Math.min(65535, Math.max(0, t))
        rawBaos.write(clamped & 0xFF)
        rawBaos.write((clamped >> 8) & 0xFF)
    }
    return fastlzCompress(rawBaos.toByteArray()).encodeBase64().toString()
}

/*************
 * IR PROTOCOL ENCODERS
 */

/**
 * Dispatch to the correct protocol encoder based on protocol name.
 * Address and command bytes are in Flipper IRDB order (LSB first, zero-padded to 4 bytes).
 * Returns null if the protocol is not supported.
 */
private List<Integer> protocolTimings(final String protocol, final List<Integer> addrBytes, final List<Integer> cmdBytes) {
    switch (protocol.toUpperCase()) {
    case "SAMSUNG32": return timingsSamsung32(addrBytes[0], cmdBytes[0])
    case "NEC":       return timingsNEC(addrBytes[0], cmdBytes[0], false)
    case "NECEXT":    return timingsNEC((addrBytes[1] << 8) | addrBytes[0], cmdBytes[0], true)
    case "RC5":       return timingsRC5(addrBytes[0], cmdBytes[0])
    case "RC6":       return timingsRC6(addrBytes[0], cmdBytes[0])
    case "SIRC":      return timingsSIRC(addrBytes[0], cmdBytes[0], 12)
    case "SIRC15":    return timingsSIRC(addrBytes[0], cmdBytes[0], 15)
    case "SIRC20":    return timingsSIRC((addrBytes[1] << 5) | (addrBytes[0] >> 3), cmdBytes[0], 20)
    default:          return null
    }
}

/**
 * Samsung32: addr(8) addr(8) cmd(8) ~cmd(8), LSB first, 3 bursts.
 * Address is sent TWICE (not inverted) — key difference from NEC.
 *
 * Timing constants derived from live device capture against
 * Samsung UN55EH6001F with AA59-00666A remote.
 */
private List<Integer> timingsSamsung32(final int addr, final int cmd) {
    final int HEADER_PULSE   = 4523
    final int HEADER_SPACE   = 4523
    final int BIT_PULSE      = 585
    final int BIT_ONE_SPACE  = 1675
    final int BIT_ZERO_SPACE = 585
    final int STOP_PULSE     = 585
    final int BURST_GAP      = 45650

    final int cmdInv = (~cmd) & 0xFF

    Closure<List<Integer>> burst = {
        final List<Integer> t = [HEADER_PULSE, HEADER_SPACE]
        for (int b : [addr, addr, cmd, cmdInv]) {
            for (int i = 0; i < 8; i++) {
                t << BIT_PULSE
                t << (((b >> i) & 1) ? BIT_ONE_SPACE : BIT_ZERO_SPACE)
            }
        }
        t << STOP_PULSE
        return t
    }

    // Three bursts matching the structure of a live Samsung32 capture
    return burst() + [BURST_GAP] + burst() + [BURST_GAP] + burst()
}

/**
 * NEC / NECext: standard NEC protocol.
 * NEC:    addr(8) ~addr(8) cmd(8) ~cmd(8), LSB first.
 * NECext: addr(16) cmd(8) ~cmd(8), LSB first (16-bit address, no address inversion).
 * 1 main burst + 1 NEC repeat burst.
 */
private List<Integer> timingsNEC(final int addr, final int cmd, final boolean extended) {
    final int HEADER_PULSE   = 9000
    final int HEADER_SPACE   = 4500
    final int BIT_PULSE      = 560
    final int BIT_ONE_SPACE  = 1690
    final int BIT_ZERO_SPACE = 560
    final int STOP_PULSE     = 560
    final int REPEAT_SPACE   = 40000
    final int RPT_PULSE      = 9000
    final int RPT_SPACE      = 2250

    final int cmdInv = (~cmd) & 0xFF
    final List<Integer> dataBytes = extended ?
        [addr & 0xFF, (addr >> 8) & 0xFF, cmd, cmdInv] :
        [addr & 0xFF, (~addr) & 0xFF, cmd, cmdInv]

    final List<Integer> t = [HEADER_PULSE, HEADER_SPACE]
    for (int b : dataBytes) {
        for (int i = 0; i < 8; i++) {
            t << BIT_PULSE
            t << (((b >> i) & 1) ? BIT_ONE_SPACE : BIT_ZERO_SPACE)
        }
    }
    t << STOP_PULSE
    t << REPEAT_SPACE
    // NEC repeat burst
    t << RPT_PULSE
    t << RPT_SPACE
    t << STOP_PULSE
    return t
}

/**
 * RC5: Philips RC5 protocol, Manchester encoding.
 * 14-bit frame: start(1) field(1) toggle(0) addr(5) cmd(6), MSB first.
 * Toggle is fixed at 0; Manchester: 1 = space then pulse, 0 = pulse then space.
 */
private List<Integer> timingsRC5(final int addr, final int cmd) {
    final int HALF = 889

    // S2 is inverted cmd bit 6 (extended RC5 field bit)
    final int s2 = (cmd & 0x40) ? 0 : 1
    final List<Integer> bits = [1, s2, 0]  // start, field, toggle=0
    for (int i = 4; i >= 0; i--) bits << ((addr >> i) & 1)
    for (int i = 5; i >= 0; i--) bits << ((cmd >> i) & 1)

    return manchesterFlatten(bits, HALF)
}

/**
 * Flatten Manchester encoded bits into alternating pulse/space timings.
 * RC5 convention: 1 = space(low) then pulse(high), 0 = pulse(high) then space(low).
 * Adjacent same-polarity half-periods are merged into a single longer timing.
 */
private List<Integer> manchesterFlatten(final List<Integer> bits, final int half) {
    // Build level sequence: true=pulse(high), false=space(low)
    final List<Boolean> levels = []
    for (int bit : bits) {
        if (bit == 1) { levels << false; levels << true  }  // space then pulse
        else          { levels << true;  levels << false }  // pulse then space
    }

    // Compress consecutive same levels
    final List<Integer> timings = []
    int run = half
    for (int i = 1; i < levels.size(); i++) {
        if (levels[i] == levels[i-1]) { run += half }
        else { timings << run; run = half }
    }
    timings << run

    // RC5 starts with a space (level[0]=false); Tuya expects pulse first — drop leading space
    return levels[0] ? timings : timings.subList(1, timings.size())
}

/**
 * RC6: Philips RC6 mode 0, Manchester encoding.
 * Header: 2664us pulse + 888us space.
 * 21-bit frame: start(1) mode(3=000) trailer(1=toggle, fixed 0) addr(8) cmd(8), MSB first.
 * Trailer bit uses double-width half-periods (888us).
 */
private List<Integer> timingsRC6(final int addr, final int cmd) {
    final int HEADER_PULSE = 2664
    final int HEADER_SPACE = 888
    final int HALF         = 444
    final int TRAIL_HALF   = 888  // trailer bit uses double width

    final List<Integer> t = [HEADER_PULSE, HEADER_SPACE]

    // Build bit list: start(1) mode(000) trailer(0) addr(8 MSB) cmd(8 MSB)
    final List<Integer> allBits = [1, 0, 0, 0, 0] +
        (7..0).collect { i -> (addr >> i) & 1 } +
        (7..0).collect { i -> (cmd  >> i) & 1 }

    // Half-widths: normal for start+mode+addr+cmd, double for trailer bit (index 4)
    final List<Integer> halfWidths = (0..<21).collect { i -> i == 4 ? TRAIL_HALF : HALF }

    // RC6 Manchester: 1 = pulse then space, 0 = space then pulse
    final List<Boolean> levels = []
    allBits.eachWithIndex { bit, idx ->
        if (bit == 1) { levels << true; levels << false }
        else          { levels << false; levels << true }
    }

    // Compress into timings, merging consecutive same-polarity half-periods
    boolean prev = levels[0]
    int run = halfWidths[0]
    for (int i = 1; i < levels.size(); i++) {
        int hw = halfWidths[i.intdiv(2)]
        if (levels[i] == prev) { run += hw }
        else { t << run; run = hw; prev = levels[i] }
    }
    t << run
    return t
}

/**
 * SIRC: Sony IR protocol (pulse-width modulation, 40kHz carrier).
 * SIRC12: cmd(7) addr(5),  12 bits total.
 * SIRC15: cmd(7) addr(8),  15 bits total.
 * SIRC20: cmd(7) addr(13), 20 bits total.
 * LSB first. Space always 600us. Pulse: 1200us=1, 600us=0.
 * Header: 2400us pulse + 600us space.
 * Sent 3 times with ~45ms total frame period.
 */
private List<Integer> timingsSIRC(final int addr, final int cmd, final int totalBits) {
    final int HEADER_PULSE = 2400
    final int HEADER_SPACE = 600
    final int ONE_PULSE    = 1200
    final int ZERO_PULSE   = 600
    final int BIT_SPACE    = 600
    final int FRAME_GAP    = 45000

    final int cmdBits  = 7
    final int addrBits = totalBits - cmdBits

    Closure<List<Integer>> burst = {
        final List<Integer> t = [HEADER_PULSE, HEADER_SPACE]
        for (int i = 0; i < cmdBits; i++) {
            t << (((cmd  >> i) & 1) ? ONE_PULSE : ZERO_PULSE)
            t << BIT_SPACE
        }
        for (int i = 0; i < addrBits; i++) {
            t << (((addr >> i) & 1) ? ONE_PULSE : ZERO_PULSE)
            if (i < addrBits - 1) t << BIT_SPACE
        }
        return t
    }

    return burst() + [FRAME_GAP] + burst() + [FRAME_GAP] + burst()
}

/*************
 * FASTLZ COMPRESSION
 *
 * FastLZ level-1 (LZ77-based, 8kB window).
 * Documented at: https://gist.github.com/mildsunrise/1d576669b63a260d2cff35fda63ec0b5
 *
 * Stream format:
 *   Literal block:   [000LLLLL] [L+1 bytes of literal data]        L = length-1, max 32 bytes
 *   Distance block:  [LLLDDDDD] [DDDDDDDD]                         L = length-2 (3..8), D = distance-1
 *                    [111DDDDD] [LLLLLLLL] [DDDDDDDD]              L >= 7, extra byte carries L-7
 */

private byte[] fastlzCompress(final byte[] data) {
    final int W     = 8192  // window size
    final int L_MAX = 264   // maximum match length (255 + 9)
    final ByteArrayOutputStream out = new ByteArrayOutputStream()
    int pos        = 0
    int blockStart = 0

    while (pos < data.length) {
        // Search backwards in window for the first match of length >= 3 (level-1: first match wins)
        int bestLen  = 0
        int bestDist = 0
        final int searchStart = Math.max(0, pos - W)
        for (int start = pos - 1; start >= searchStart; start--) {
            final int d     = pos - start
            int length      = 0
            final int limit = Math.min(L_MAX, data.length - pos)
            while (length < limit && data[pos + length] == data[start + length]) { length++ }
            if (length >= 3) { bestLen = length; bestDist = d; break }
        }

        if (bestLen >= 3) {
            fastlzEmitLiterals(out, data, blockStart, pos)
            fastlzEmitDistance(out, bestLen, bestDist)
            pos        += bestLen
            blockStart  = pos
        } else {
            pos++
        }
    }
    fastlzEmitLiterals(out, data, blockStart, pos)
    return out.toByteArray()
}

private void fastlzEmitLiterals(final ByteArrayOutputStream out, final byte[] data, final int start, final int end) {
    if (start >= end) return
    for (int i = start; i < end; i += 32) {
        final int len = Math.min(32, end - i)
        out.write(len - 1)          // header: 000LLLLL where L = length-1
        out.write(data, i, len)
    }
}

private void fastlzEmitDistance(final ByteArrayOutputStream out, int length, int distance) {
    distance -= 1   // stored as distance-1
    length   -= 2   // stored as length-2
    if (length >= 7) {
        out.write((7 << 5) | ((distance >> 8) & 0x1F))
        out.write(Math.min(255, length - 7))
        out.write(distance & 0xFF)
    } else {
        out.write((length << 5) | ((distance >> 8) & 0x1F))
        out.write(distance & 0xFF)
    }
}

/*************
 * ZIGBEE PAYLOAD UTILITIES
 */

/** Format a byte[] as a string of space-separated hex bytes, used for Zigbee command payloads. */
String toPayload(final byte[] bytes) {
    return bytes.collect({ b -> String.format("%02X", b) }).join(' ')
}

/** Parse a string of space-separated hex bytes as a byte[]. */
byte[] toBytes(final List<String> payload) {
    return payload.collect({ x -> Integer.parseInt(x, 16) as byte }) as byte[]
}

/** Format a struct as a string of space-separated hex bytes. */
String toPayload(final List<Map> format, final Map<String, Object> payload) {
    final def output = new ByteArrayOutputStream()
    for (def entry in format) {
        def value = payload[entry.name]
        switch (entry.type) {
        case "uint8":    writeIntegerLe(output, value, 1); break
        case "uint16":   writeIntegerLe(output, value, 2); break
        case "uint24":   writeIntegerLe(output, value, 3); break
        case "uint32":   writeIntegerLe(output, value, 4); break
        case "octetStr":
            writeIntegerLe(output, value.length, 1)
            output.write(value, 0, value.length)
            break
        default: throw new RuntimeException("Unknown type: ${entry.type} (name: ${entry.name})")
        }
    }
    return toPayload(output.toByteArray())
}

/** Parse a struct from a string of space-separated hex bytes. */
Map toStruct(final List<Map> format, final List<String> payload) {
    final def input  = new ByteArrayInputStream(toBytes(payload))
    final def result = [:]
    for (def entry in format) {
        switch (entry.type) {
        case "uint8":    result[entry.name] = readIntegerLe(input, 1); break
        case "uint16":   result[entry.name] = readIntegerLe(input, 2); break
        case "uint24":   result[entry.name] = readIntegerLe(input, 3); break
        case "uint32":   result[entry.name] = readIntegerLe(input, 4); break
        case "octetStr":
            final int length = readIntegerLe(input, 1)
            result[entry.name] = new byte[length]
            input.read(result[entry.name], 0, length)
            break
        default: throw new RuntimeException("Unknown type: ${entry.type} (name: ${entry.name})")
        }
    }
    return result
}

/** Write an integer in two's complement little-endian byte order. */
def writeIntegerLe(final ByteArrayOutputStream out, int value, final int numBytes) {
    for (int p = 0; p < numBytes; p++) {
        final int digit1 = value % 16
        value = value.intdiv(16)
        final int digit2 = value % 16
        out.write(digit2 * 16 + digit1)
        value = value.intdiv(16)
    }
}

/** Read numBytes bytes from the input stream as a little-endian integer. */
def readIntegerLe(final ByteArrayInputStream input, final int numBytes) {
    int value = 0
    int pos   = 1
    for (int i = 0; i < numBytes; i++) {
        value += input.read() * pos
        pos   *= 0x100
    }
    return value
}

/** Return the next sequence number, wrapping at 0xFFFF. Persisted in driver state. */
def nextSeq() {
    return state.nextSeq = ((state.nextSeq ?: 0) + 1) % 0x10000
}

/**
 * Simple checksum: sum of all unsigned byte values mod 256.
 * Used to verify code data chunks during the transmit sequence.
 * Note: this is a weak checksum — byte ordering errors would not be detected —
 * but it matches what the device expects.
 */
def checksum(final byte[] byteArray) {
    // Java/Groovy bytes are signed; Byte.toUnsignedInt gives the correct integer value
    return byteArray.inject(0, { acc, val -> acc + Byte.toUnsignedInt(val) }) % 0x100
}

/*************
 * LOGGING
 */

def error(msg) { log.error(msg) }

def warn(msg) {
    if (logLevel in ["WARN", "INFO", "DEBUG"]) log.warn(msg)
}

def info(msg) {
    if (logLevel in ["INFO", "DEBUG"]) log.info(msg)
}

def debug(msg) {
    if (logLevel == "DEBUG") log.debug(msg)
}

def resetLogLevel() {
    info "logLevel auto reset to INFO"
    device.updateSetting("logLevel", [value: "INFO", type: "enum"])
}

/*************
 * MOCKING STUBS
 * These allow the driver to be used in unit tests outside the Hubitat sandbox.
 */

/** Returns true if hub commands should be intercepted for unit testing. */
def mockHubCommands() {
    try { return sentCommands != null } catch (ex) { return false }
}

/** Mocking facade for sendHubCommand. */
def doSendHubCommand(cmd) {
    if (mockHubCommands()) { sentCommands.add(cmd) }
    else { sendHubCommand(new hubitat.device.HubAction(cmd, hubitat.device.Protocol.ZIGBEE)) }
}

/** Mocking facade for sendEvent. */
def doSendEvent(final Map event) {
    if (mockHubCommands()) { sentEvents.add(event) }
    else { sendEvent(event) }
}

/**
 * Base64 encode helper — uses Apache Commons Codec when available (Hubitat sandbox),
 * falls back to Groovy's built-in for unit test environments.
 */
def encodeBase64(final byte[] bytes) {
    try { return org.apache.commons.codec.binary.Base64.encodeBase64String(bytes) }
    catch (ex) { return encodeToString(bytes) }  // fallback for tests
}

/**
 * Zigbee command builder — constructs a raw Zigbee command string.
 * Using string construction directly rather than zigbee.command() so the
 * method can be stubbed cleanly in unit tests.
 */
String command(final int clusterId, final int commandId, final String payload) {
    return "he cmd 0x${device.deviceNetworkId} 0x${device.endpointId} 0x${Integer.toHexString(clusterId)} 0x${Integer.toHexString(commandId)} {${payload}}"
}
