/**
 * Leakbot Connect
 * Hubitat Integration for Leakbot water leak detectors
 *
 * Authenticates with the Leakbot cloud API, creates/manages child
 * virtual devices (one per Leakbot unit), and polls on a configurable
 * schedule to keep attributes current.
 *
 * API base: https://app.leakbot.io/v1.0/
 * Auth:     POST /User/Account/MyLogin/  →  lctoken cookie
 * Devices:  POST /User/Device/MyDeviceList/
 * Status:   POST /Device/Device/MyView/  (per-device)
 * Messages: POST /Device/Device/MyListMessagesForDevice  (per-device)
 *
 * Child driver: Leakbot Device  (leakbot-device.groovy)
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

import groovy.transform.Field

definition(
    name:        "Leakbot Connect",
    namespace:   "neeravmodi",
    author:      "Neerav Modi",
    description: "Cloud polling integration for Leakbot water leak detectors",
    category:    "Safety & Security",
    iconUrl:     "",
    iconX2Url:   "",
    singleInstance: true,
    importUrl:   "https://raw.githubusercontent.com/neeravmodi/Hubitat/refs/heads/main/Integrations/Leakbot/leakbot-connect.groovy",
    documentationLink: "",
    menu:        "Integrations"
)

preferences {
    page(name: "mainPage")
    page(name: "loginPage")
}

// ---------------------------------------------------------------------------
// Pages
// ---------------------------------------------------------------------------

def mainPage() {
    dynamicPage(name: "mainPage", title: "Leakbot Connect", install: true, uninstall: true) {
        section("Account") {
            href "loginPage", title: "Leakbot Account Settings",
                 description: state.username ? "Logged in as: ${state.username}" : "Tap to configure"
        }

        if (state.token) {
            section("Polling") {
                input "pollInterval", "enum",
                    title: "Poll interval",
                    options: ["15": "15 minutes", "30": "30 minutes", "60": "1 hour", "120": "2 hours"],
                    defaultValue: "30",
                    required: true
            }
            section("Actions") {
                input "btnRefresh", "button", title: "Refresh devices now"
            }
            if (state.deviceList) {
                section("Discovered devices") {
                    paragraph state.deviceList
                }
            }
        }

        section("Debug") {
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
        }
    }
}

def loginPage() {
    dynamicPage(name: "loginPage", title: "Leakbot Account", nextPage: "mainPage") {
        section {
            input "leakbotUsername", "email",  title: "Email address", required: true
            input "leakbotPassword", "password", title: "Password",   required: true
            input "btnLogin", "button", title: "Log in"
        }
        if (state.loginError) {
            section {
                paragraph "<b style='color:red'>${state.loginError}</b>"
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Lifecycle
// ---------------------------------------------------------------------------

def installed() {
    logDebug "installed"
    initialize()
}

def updated() {
    logDebug "updated"
    unschedule()
    initialize()
}

def uninstalled() {
    logDebug "uninstalled - removing child devices"
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    if (!state.token) {
        log.warn "Leakbot Connect: no auth token - configure account credentials"
        return
    }
    schedulePolling()
    runIn(2, "refreshAllDevices")
}

// ---------------------------------------------------------------------------
// Button handler
// ---------------------------------------------------------------------------

def appButtonHandler(btn) {
    switch (btn) {
        case "btnLogin":
            doLogin()
            break
        case "btnRefresh":
            refreshAllDevices()
            break
    }
}

// ---------------------------------------------------------------------------
// Auth
// ---------------------------------------------------------------------------

@Field static final String API_BASE = "https://app.leakbot.io"
@Field static final String API_VER  = "v1.0"

def doLogin() {
    state.remove("loginError")
    if (!leakbotUsername || !leakbotPassword) {
        state.loginError = "Email and password are required"
        return
    }
    logDebug "Attempting login for ${leakbotUsername}"
    def body = [username: leakbotUsername, password: leakbotPassword]
    def result = apiPost("/User/Account/MyLogin/", body, null)
    if (result == null) return  // error already logged
    if (result.error) {
        state.loginError = "Login failed: ${result.description ?: result.error}"
        return
    }
    state.token    = result.token
    state.username = leakbotUsername
    logDebug "Login successful - token obtained"
    runIn(1, "refreshAllDevices")
}

// ---------------------------------------------------------------------------
// Polling
// ---------------------------------------------------------------------------

def schedulePolling() {
    def mins = (pollInterval ?: "30").toInteger()
    logDebug "Scheduling poll every ${mins} minutes"
    schedule("0 0/${mins} * * * ?", "refreshAllDevices")
}

def refreshAllDevices() {
    if (!state.token) {
        log.warn "Leakbot Connect: cannot refresh - no auth token"
        return
    }
    logDebug "Refreshing all devices"
    def devList = apiPost("/User/Device/MyDeviceList/", [:], state.token)
    if (devList == null) return
    if (devList.error) {
        handleApiError(devList)
        return
    }

    def ids = devList.IDs
    if (!ids) {
        log.warn "Leakbot Connect: no devices returned from API"
        return
    }

    def summary = []
    ids.each { dev ->
        def deviceId = dev.id?.toString()
        if (!deviceId) return
        ensureChildDevice(deviceId, dev)
        refreshDevice(deviceId)
        summary << "${dev.leakbotId ?: deviceId} (status: ${dev.device_status ?: 'unknown'})"
    }
    state.deviceList = summary.join("\n")
}

def refreshDevice(String deviceId) {
    def child = getChildDevice("leakbot:${deviceId}")
    if (!child) return

    // Status
    def status = apiPost("/Device/Device/MyView/", [LbDevice_ID: deviceId], state.token)
    if (status && !status.error) {
        child.updateStatus(status)
    } else if (status?.error) {
        handleApiError(status)
    }

    // Latest message (for lastMessage / lastMessageType attributes)
    def msgs = apiPost("/Device/Device/MyListMessagesForDevice",
                       [LbDevice_ID: deviceId, fetch_size: 1], state.token)
    if (msgs && !msgs.error) {
        child.updateMessages(msgs)
    }
}

// ---------------------------------------------------------------------------
// Child device management
// ---------------------------------------------------------------------------

def ensureChildDevice(String deviceId, Map dev) {
    def dni = "leakbot:${deviceId}"
    def child = getChildDevice(dni)
    if (!child) {
        def label = dev.leakbotId ? "Leakbot ${dev.leakbotId}" : "Leakbot ${deviceId}"
        logDebug "Creating child device: ${label} (${dni})"
        child = addChildDevice("neeravmodi", "Leakbot Device", dni,
                               [label: label, name: "Leakbot Device"])
        child.updateDataValue("deviceId",   deviceId)
        child.updateDataValue("leakbotId",  dev.leakbotId ?: "")
        child.updateDataValue("firmwareVersion", dev.fw_version ?: "")
    }
    return child
}

// ---------------------------------------------------------------------------
// HTTP helpers
// ---------------------------------------------------------------------------

def apiPost(String path, Map body, String token) {
    def uri  = "${API_BASE}/${API_VER}${path}"
    def headers = ["Content-Type": "application/json", "Accept": "application/json"]
    if (token) headers["Cookie"] = "lctoken=${token}"

    def result = null
    try {
        httpPostJson([uri: uri, headers: headers, body: body, timeout: 20]) { resp ->
            if (resp.status == 200) {
                result = resp.data
            } else {
                log.error "Leakbot Connect: HTTP ${resp.status} for ${path}"
            }
        }
    } catch (groovyx.net.http.HttpResponseException e) {
        if (e.statusCode == 401 || e.statusCode == 403) {
            log.warn "Leakbot Connect: auth error (${e.statusCode}) - clearing token"
            state.remove("token")
        } else {
            log.error "Leakbot Connect: HTTP error ${e.statusCode} on ${path}: ${e.message}"
        }
    } catch (Exception e) {
        log.error "Leakbot Connect: request failed for ${path}: ${e.message}"
    }
    return result
}

def handleApiError(Map result) {
    def err = result.error ?: "unknown"
    def desc = result.description ?: ""
    if (err == "INVALID_TOKEN" || err == "NOT_AUTHENTICATED") {
        log.warn "Leakbot Connect: token invalid - clearing, will re-auth on next login"
        state.remove("token")
    } else {
        log.error "Leakbot Connect: API error ${err}: ${desc}"
    }
}

// ---------------------------------------------------------------------------
// Logging
// ---------------------------------------------------------------------------

def logDebug(msg) {
    if (logEnable) log.debug "Leakbot Connect: ${msg}"
}
