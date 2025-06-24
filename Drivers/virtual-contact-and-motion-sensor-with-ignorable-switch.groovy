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
 *  Last modified: 2025-06-24
 *
 *  Change History:
 *
 *  Version  Date        Description
 *  -------  ----        -----------
 *  v0.1     2025-06-01  Initial pre-release
 *  v0.2     2025-06-16  Initial release
 *  v0.3     2025-06-24  Added delay when ignoring switch toggling to minimize Event race condition
 *                       Added info logging, default is on
 *                       Changed debug logging to default to off
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
        input name: "debugLogging", type: "bool", default: false, title: "Enable debug logging?", description: "Turn on to enable debug logs"
        input name: "infoLogging", type: "bool", default: true, title: "Enable info logging?", description: "Turn on to enable info logs"
	}
}

String version() { return "0.2" }

def contactOpen(){
	
    logInfo("Contact opened.")
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
	
    logInfo("Contact closed.")
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
	
    logInfo("Motion active.")
    logDebug("motionActive() called")
	contactOpen()
}

def motionInactive() {
	
    logInfo("Motion inactive.")
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

		// Prevent event race condition
		pauseExecution(250)

		// Reverted state
		sendEvent(name: "switch", value: switchState, descriptionText: "${device.displayName} switch reverted to ${switchState}.")
   		logInfo("Switch turned on. Ignored and reverted.")
		logDebug("Switch reverted to ${switchState}.")

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

   		logInfo("Switch turned on.")
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
		
		// Prevent event race condition
		pauseExecution(250)

		// Reverted state
		sendEvent(name: "switch", value: switchState,  descriptionText: "${device.displayName} switch reverted to ${switchState}.")
   		logInfo("Switch turned off. Ignored and reverted.")
		logDebug("Switch reverted to ${switchState}.")
 
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

   		logInfo("Switch turned off.")
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

    if (infoLogging) {
        logInfo ("Info logging turned ON.")
    } else {
        logInfo ("Info logging turned OFF.")
    }

    unschedule(disableDebugLogging)
    if (debugLogging) {
        // Schedule it to flip debugLogging=false in 30 minutes
        runIn(30 * 60, disableDebugLogging)
        logInfo ("Debug logging turned ON for 30 minutes.")
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

private logInfo(String msg) {
    if (infoLogging) {
        log.info "${device.displayName}: ${msg}"
    }
}

private logDebug(String msg) {
    if (debugLogging) {
        log.debug "${device.displayName}: ${msg}"
    }
}

def disableDebugLogging() {
    device.updateSetting("debugLogging", [value: "false", type: "bool"])
    logInfo ("Debug logging disabled automatically.")
}

def switchDebugLog() {
	// Prevent event race condition
	pauseExecution(250)
	
	String msgDebug = "Contact is ${device.currentValue("contact")}. "
	if(isReversed) {
		msgDebug = msgDebug + "Switch is reversed. "
	} else {
		msgDebug = msgDebug + "Switch is not reversed. "
	}
    
	msgDebug = msgDebug + "Switch is currently ${device.currentValue("switch")}."
	logDebug(msgDebug)
}