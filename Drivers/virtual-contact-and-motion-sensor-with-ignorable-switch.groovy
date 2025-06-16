/*
 *  Virtual Contact and Motion Sensor With Ignorable Switch
 *  Project URL: https://github.com/neeravmodi/Hubitat/Drivers
 *  Copyright 2025 Neerav Modi
 *
 *  ImportURL: https://raw.githubusercontent.com/neeravmodi/Hubitat/refs/heads/main/Drivers/virtual-contact-and-motion-sensor-with-ignorable-switch.groovy
 *
 *  Description:
 *  
 *  A driver to mirror a contact or motion sensor as a switch.  However, turning the switch on or off 
 *  is ignored by default. Useful hack in HomeKit and the Home app to display the state of contact or 
 *  motion sensors on the main Home page, instead of opening the Security tab and then choosing Contact 
 *  Sensors or Motion Sensors. Configurable to reverse the switch behavior and to not ignore switch
 *  toggling.
 *
 *  -----------------------------------------------------------------------------------------------------
 *
 *  Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 *  in compliance with the License. You may obtain a copy of the License at:
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software distributed under the License is distributed
 *  on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License
 *  for the specific language governing permissions and limitations under the License.
 *
 *  -----------------------------------------------------------------------------------------------------
 *
 *  Last modified: 2025-06-16
 *
 *  Change History:
 *
 *  Version  Date        Description
 *  -------  ----        -----------
 *  v0.1     2025-06-01  Initial pre-release
 *  v0.2     2025-06-16  Initial release
 */

metadata {
	definition (
			name: "Virtual Contact and Motion Sensor With Ignorable Switch", 
			namespace: "neeravmodi", 
			author: "Neerav Modi",
			importUrl: "https://raw.githubusercontent.com/neeravmodi/Hubitat/refs/heads/main/Drivers/virtual-contact-and-motion-sensor-with-ignorable-switch.groovy",
			description: "A driver to mirror a contact or motion sensor as a switch.  However, turning the switch on or off is ignored by default. Useful hack in HomeKit and the Home app to display the state of contact or motion sensors on the main Home page, instead of opening the Security tab and then choosing Contact Sensors or Motion Sensors. Configurable to reverse the switch behavior and to not ignore switch toggling."
		) {

		capability "Actuator"
		capability "Contact Sensor"
		capability "Motion Sensor"
		capability "Sensor"
		capability "Switch"

		attribute "switchIsReversed", "bool"
		attribute "switchIsIgnored", "bool"

		command "contactOpen"
		command "contactClose"
		command "motionActive"
		command "motionInactive"
	}

	preferences {
		input name: "isReversed", type: "bool", default: false, title: "Reverse Switch Behavior", description: "By default, an open contact turns the switch ON.  Turn this preference ON for an open contact to turn the switch OFF."
        input name: "switchIgnored", type: "bool", default: true, title: "Ignore Switch On/Off", description: "When this preference is ON, turning the switch on or off is ignored.  This prevents toggling the switch in the Home app from affecting the state of the device.  Turn this preference OFF to allow changes to the switch to also change the state of the contact and motion sensors."
        input name: "debugLogging", type: "bool", default: true, title: "Enable debug logging?", description: "Turn off to disable debug logs"
	}
}

String version() { return "0.2" }

def contactOpen(){
	
    logDebug("contactOpen() called")
	sendEvent(name: "contact", value: "open", descriptionText: "${device.displayName} contact is open.")
	sendEvent(name: "motion", value: "active", descriptionText: "${device.displayName} motion is active.")	

	if(isReversed) {
		switchState = "off"
	} else {
		switchState = "on"
	}
	sendEvent(name: "switch", value: switchState, descriptionText: "${device.displayName} switch is ${switchState}.")

	switchDebugLog()

}

def contactClose(){
	
    logDebug("contactClose() called")
	sendEvent(name: "contact", value: "closed", descriptionText: "${device.displayName} contact is closed.")
	sendEvent(name: "motion", value: "inactive", descriptionText: "${device.displayName} motion is inactive.")	

	if(isReversed) {
		switchState = "on"
	} else {
		switchState = "off"
	}
	sendEvent(name: "switch", value: switchState, descriptionText: "${device.displayName} switch is ${switchState}.")

	switchDebugLog()

}

def motionActive() {
	
    logDebug("motionActive() called")
	contactOpen()
}

def motionInactive() {
	
    logDebug("motionInactive() called")
	contactClose()
}

def on(){
	
    logDebug("on() called")
	if (switchIgnored) {
		// Turning a switch ON is ignored and is set back to match contact sensor state
		// In order to get Home to refresh the status immediately, Hubitat needs to toggle
		// the switch to match Home and then toggle it back.
		
		logDebug("Switch is being ignored.")
		
		// Initial toggle
		if (device.currentValue("contact") == "open") {
			if(isReversed) {
				sendEvent(name: "switch", value: "on")
				switchState = "off"
			} else {
				sendEvent(name: "switch", value: "off")
				switchState = "on"
			}
		} else {
			if(isReversed) {
				sendEvent(name: "switch", value: "off")
				switchState = "on"
			} else {
				sendEvent(name: "switch", value: "on")
				switchState = "off"
			}
		}

		// Reverted state
		sendEvent(name: "switch", value: switchState, descriptionText: "${device.displayName} switch reverted to ${switchState}.")

	} else {
		// Normal switch operation
		switchState = "on"

		if(isReversed) {
			contactState = "closed"
			motionState = "inactive"
		} else {
			contactState = "open"
			motionState = "active"
		}

		sendEvent(name: "switch", value: switchState, descriptionText: "${device.displayName} switch is ${switchState}.")
		sendEvent(name: "contact", value: contactState, descriptionText: "${device.displayName} contact is ${contactState}.")
		sendEvent(name: "motion", value: motionState, descriptionText: "${device.displayName} motion is ${motionState}.")	

	}

	switchDebugLog()

}

def off(){
	
    logDebug("off() called")
	if (switchIgnored) {
		// Turning a switch OFF is ignored and is set back to match contact sensor state
		// In order to get Home to refresh the status immediately, Hubitat needs to toggle
		// the switch to match Home and then toggle it back.

		logDebug("Switch is being ignored.")

		// Initial toggle
		if (device.currentValue("contact") == "open") {
			if(isReversed) {
				sendEvent(name: "switch", value: "on")
				switchState = "off"
			} else {
				sendEvent(name: "switch", value: "off")
				switchState = "on"
			}
		} else {
			if(isReversed) {
				sendEvent(name: "switch", value: "off")
				switchState = "on"
			} else {
				sendEvent(name: "switch", value: "on")
				switchState = "off"
			}
		}

		// Reverted state
		sendEvent(name: "switch", value: switchState,  descriptionText: "${device.displayName} switch reverted to ${switchState}.")
 
	} else {
		// Normal switch operation
		switchState = "off"

		if(isReversed) {
			contactState = "open"
			motionState = "active"
		 } else {
			contactState = "closed"
			motionState = "inactive"
		 }

		sendEvent(name: "switch", value: switchState, descriptionText: "${device.displayName} switch is ${switchState}.")
		sendEvent(name: "contact", value: contactState, descriptionText: "${device.displayName} contact is ${contactState}.")
		sendEvent(name: "motion", value: motionState, descriptionText: "${device.displayName} motion is ${motionState}.")	

	}

	switchDebugLog()

}

def installed(){
    logDebug("installed() called")

	sendEvent(name: "contact", value: "closed") 
	sendEvent(name: "motion", value: "inactive")	
	sendEvent(name: "isReversed", value: false) 
	device.updateSetting("switchIgnored",[value: true,type:"bool"])
	sendEvent(name: "switchIgnored", value: true)	

	if(isReversed) {
		sendEvent(name: "switch", value: "on")
	} else {
		sendEvent(name: "switch", value: "off")
	}

	switchDebugLog()

}

// Correctly set the switch state in case the isReversed preference has been changed
def updated(){
    logDebug("updated() called")

    unschedule(disableDebugLogging)
    if (debugLogging) {
        // Schedule it to flip debugLogging=false in 30 minutes
        runIn(30 * 60, disableDebugLogging)
        log.info "${device.displayName}: Debug logging turned ON for 30 minutes."
    }

    // mirror preferences into attributes, etc.
	if (device.currentValue("switchReversed") != isReversed) {
		sendEvent(name: "switchIsReversed", value: isReversed)	
	}
	if (device.currentValue("switchIgnoredAttr") != switchIgnored) {
		sendEvent(name: "switchIsIgnored", value: switchIgnored)
	}
	
	if (device.currentValue("contact") == "closed") {
		if(isReversed) {
			sendEvent(name: "switch", value: "on")
		} else {
			sendEvent(name: "switch", value: "off")
		}
	} else {
		if(isReversed) {
			sendEvent(name: "switch", value: "off")
		} else {
			sendEvent(name: "switch", value: "on")
		}
	}

	switchDebugLog()

}

def uninstalled() {
    unschedule()
    logDebug("Driver uninstalled; all schedules cleared.")
}

private logDebug(String msg) {
    if (debugLogging) {
        log.debug "${device.displayName}: ${msg}"
    }
}

def disableDebugLogging() {
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
    log.info "${device.displayName}: Debug logging disabled automatically."
}

def switchDebugLog() {
    logDebug("Contact is ${device.currentValue("contact")}.")
	if(isReversed) {
		logDebug("Switch is reversed.")
	} else {
		logDebug("Switch is not reversed.")		
	}
	logDebug("Switch is currently ${device.currentValue("switch")}.")
}