/*
 *  Virtual Message and Notification Device
 *  Project URL: https://github.com/neeravmodi/Hubitat/Drivers
 *  Copyright 2026 Neerav Modi
 *
 *  ImportURL: https://raw.githubusercontent.com/neeravmodi/Hubitat/refs/heads/main/Drivers/virtual-message-notification-device.groovy
 *
 *  Description:
 *  
 *  A virtual device driver used to accept, re-format (HTML/BBcode/Pushover/etc), and store a notification 
 *  message.  Using the *changed* trigger, message attribute can further be used by Rule Machine or Webcore
 *  to fetch and send the processed message.
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
 *  Last modified: 2026-01-18
 *
 *  Change History:
 *
 *  Version  Date        Description
 *  -------  ----        -----------
 *  v0.1                 Initial driver
 *  v0.2     2026-01-18  Initial release
 *                       Added preferences for processing HTML, color, and BBcode
 */

import java.util.regex.*

static String version()	{ return '0.2' }

metadata {
	definition (
        name: "Notification Device", 
        namespace: "neeravmodi", 
		importUrl: "https://raw.githubusercontent.com/neeravmodi/Hubitat/refs/heads/main/Drivers/virtual-message-notification-device.groovy",
        description: "Barebone device driver acting as a notification destination, with the functionality to (re)format dates/times and do HTML markup.",
        author: "Neerav Modi"
	) 
    
    {
		//capability "Actuator"
        capability "Configuration"
        capability "Momentary"
        capability "Notification"
        attribute "message", "STRING"
        attribute "messageHTML", "STRING"
        attribute "messageRaw", "STRING"
        attribute "regex pattern", "STRING"
        attribute "regex replace", "STRING"
		//command "commandName"
	}   
	
	preferences {
    	//input("debugEnable", "bool", title: "Enable debug logging?")
		input name: "useHTML", type: "bool", default: true, title: "Use HTML Encoding", description: "Encode message using HTML tags.  If this is ON, then there are further options to configure: Color, BBCode, etc."
		input name: "usePushover", type: "bool", default: false, title: "Use Pushover encoding", description: "If you are using the community Pushover driver and using the advanced encoding options, turning this ON will use consistent markup for use with Pushover devices. USE HTML needs to be ON. Reminder to start the message with [HTML]"
		input name: "wordColor", type: "bool", default: true, title: "Colorize 'color' words", description: "Process color words (red, orange, yellow, green, blue, purple) to display in their corresponding color. USE HTML needs to be ON."
		input name: "useBBcode", type: "bool", default: false, title: "Use BBCode Encoding", description: "Process messages marked up with BBCode tags. USE HTML needs to be ON."
		input name: "debugEnable", type: "bool", default: false, title: "Enable debug logging?"
	}
}

void installed() {
    if (debugEnable) log.trace "installed()"
    configure()
}

void updated(){
    if (debugEnable) log.trace "updated()"
    if (debugEnable) runIn(1800,logsOff)

    if (debugEnable) log.debug "Initialize empty attribute" 
    configure()
}

void configure() {
    log.trace "configure()"
    sendEvent(name:"message", value:' ')
    sendEvent(name:"messageRaw", value:' ')
    //state.remove("messageHTML")
}

void deviceNotification(message){
	sendEvent(name:"messageRaw", value: message)
	if (debugEnable) log.debug "deviceNotification: incoming message: ${message}" 

    // Remove the time in all day events -- Date1 12:00 AM - Date2 11:59 PM -> Date1 - Date2
    message = message.replaceAll(/\((([A-Z][a-z]{2} [0-9]{2}) 12:00 AM) - ([A-Z][a-z]{2} [0-9]{2}) 11:59 PM\)/, '$2 - $3')
    // Replace Date1 Time1 AM - Date1 Time2 PM -> Date 1, Time 1 AM to Time 2 PM
    message = message.replaceAll(/\((([A-Z][a-z]{2} [0-9]{2}) ([0-9]{2}:[0-9]{2} (AM))) - \2 ([0-9]{2}:[0-9]{2} (PM))\)/, '$2, $3 to $5')
    // Replace Date1 Time1 - Date1 Time2 -> Date 1, Time 1 - Time 2 (when both times are the same AM or PM)
    message = message.replaceAll(/\((([A-Z][a-z]{2} [0-9]{2}) (([0-9]{2}:[0-9]{2}) (AM|PM))) - \2 (([0-9]{2}:[0-9]{2}) \5)\)/, '$2, $4 - $7 $5')
    // Remove leading 0
    message = message.replaceAll(/0([1-9])/, '$1')
    // Simplify the time by removing the :00
    message = message.replaceAll(/:00/, '')
    // Replace Date1 - Date1 -> Date1
    message = message.replaceAll(/([A-Z][a-z]{2} [0-9]{1,2}) - \1/, '$1')

    if (debugEnable) log.debug "deviceNotification: date/time regexed: ${message}" 
   
    //	Update attribute
	sendEvent(name:"message", value: message)

	if (useHTML) {
		// Convert color words into color 
		if (wordColor) {
			if (usePushover) {
				message = message.replaceAll(/([Rr][Ee][Dd])/, '[OPEN]font color="#FF0000"[CLOSE]$1[OPEN]/font[CLOSE]')
				message = message.replaceAll(/([Oo][Rr][Aa][Nn][Gg][Ee])/, '[OPEN]font color=\"\\#FFAD01\"[CLOSE]$1[OPEN]/font[CLOSE]')
				message = message.replaceAll(/([Yy][Ee][Ll][Ll][Oo][Ww])/, '[OPEN]font color=\"\\#FFEF00\"[CLOSE]$1[OPEN]/font[CLOSE]')
				message = message.replaceAll(/([Gg][Rr][Ee][Ee][Nn])/, '[OPEN]font color=\"\\#00FF00\"[CLOSE]$1[OPEN]/font[CLOSE]')
				message = message.replaceAll(/([Bb][Ll][Uu][Ee])/, '[OPEN]font color=\"\\#00a6ff\"[CLOSE]$1[OPEN]/font[CLOSE]')
				message = message.replaceAll(/([Pp][Uu][Rr][Pp][Ll][Ee])/, '[OPEN]font color=\"\\#A63A79\"[CLOSE]$1[OPEN]/font[CLOSE]')
			} else {
				message = message.replaceAll(/([Rr][Ee][Dd])/, '<font color="#FF0000">$1</font>')
				message = message.replaceAll(/([Oo][Rr][Aa][Nn][Gg][Ee])/, '<font color=\"\\#FFAD01\">$1</font>')
				message = message.replaceAll(/([Yy][Ee][Ll][Ll][Oo][Ww])/, '<font color=\"\\#FFEF00\">$1</font>')
				message = message.replaceAll(/([Gg][Rr][Ee][Ee][Nn])/, '<font color=\"\\#00FF00\">$1</font>')
				message = message.replaceAll(/([Bb][Ll][Uu][Ee])/, '<font color=\"\\#00a6ff\">$1</font>')
				message = message.replaceAll(/([Pp][Uu][Rr][Pp][Ll][Ee])/, '<font color=\"\\#A63A79\">$1</font>')
			}
						
			if (debugEnable) log.debug "deviceNotification: processed color: ${message}"
			
		}

		// Convert bbCode to HTML tags
		if (useBBcode) {
			message = bb2Html(message)
			if (debugEnable) log.debug "deviceNotification: processed BBcode: ${message}"
		}
	}
    

	//	Update attribute
	sendEvent(name:"message", value: message)

}    

void logsOff(){
    device.updateSetting("debugEnable",[value:"false",type:"bool"])
}

String bb2Html(String htmlStr) {
    htmlStr=htmlStr.replace("[b]","<b>")
    htmlStr=htmlStr.replace("[/b]","</b>")
    htmlStr=htmlStr.replace("[i]","<i>")
    htmlStr=htmlStr.replace("[/i]","</i>")
    htmlStr=htmlStr.replace("[u]","<u>")
    htmlStr=htmlStr.replace("[/u]","</u>")
    htmlStr=htmlStr.replace("[s]","<s>")
    htmlStr=htmlStr.replace("[/s]","</s>")
    htmlStr=htmlStr.replace("[sup]","<sup>")
    htmlStr=htmlStr.replace("[/sup]","</sup>")  
    htmlStr=htmlStr.replace("[sub]","<sub>")
    htmlStr=htmlStr.replace("[/sub]","</sub>")  
    htmlStr=htmlStr.replace("[br]","<br>")    
    while(htmlStr.indexOf("[color=")>=0) {
        htmlStr=htmlStr.replace("[/color]","</font>")
        int startPos = htmlStr.indexOf("[color=")
        String colorCode = htmlStr.substring(startPos+7,startPos+13)
        htmlStr=htmlStr.replace("[color=$colorCode]","<font color=\"$colorCode\">")
    }
    while(htmlStr.indexOf("[size=")>=0) {
        htmlStr=htmlStr.replace("[/size]","</font>")
        int startPos2 = htmlStr.indexOf("[size=")
        int endPos2 = htmlStr.indexOf("]",startPos2+6)
        String fSize = htmlStr.substring(startPos2+6,endPos2)
        htmlStr=htmlStr.replace("[size=$fSize]","<font size=\"$fSize\">")
    }
    
    return htmlStr
}