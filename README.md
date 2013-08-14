# RS Text Forwarder

This is the client-side code for Android.

The idea behind this project is not new, and originates from [DeskSMS](https://play.google.com/store/apps/details?id=com.koushikdutta.desktopsms&hl=en) and [GTalkSMS](https://code.google.com/p/gtalksms/). The former is paid yearly and has no option for custom commands whatsoever, only SMS forwarding, and the latter requires you to connect and maintain a constant connection to an XMPP server, which means always-on Wi-Fi with constant traffic and full wakelock, which is the best combination if your goal is to drain your battery as fast as possible. It also seems to go nuts when the network connectivity changes.

This application aims to be as customizable (soon) as GTalkSMS, but use Google's Cloud Messaging solution to push messages from/to the device, thus eliminating connection and battery drain issues.

The project is in its infancy, only supporting a handful of commands and an UI that consists of only two `TextView`s. However, all the implemented commands work as intended.

## Efficiency

The impact of the app on your battery is next to nothing, because your phone will be able to go to deep sleep, and this app will only be invoked when needed, and even then, it will only run for an average of 25 milliseconds until the `Intent` is processed and the HTTP request is sent to AppEngine asynchronously, if needed.

## Latency

With the phone and the XMPP client being in Europe, and the AppEngine servers being in the US, the commands have an average total latency of 0.35 seconds between them being issued in your XMPP client and the response being received in the same XMPP client.

However, if your phone is in deep sleep, it may take up to 2 minutes to answer a push request. This delay doesn't apply to stuff that the phone forwards towards your XMPP client, because your phone will exit deep sleep when receiving an SMS, for example, and after that the app will maintain a partial wakelock until the `Intent` is processed and the HTTP request finishes.

Ping requests while the phone is in deep sleep:

    [16:20] RoliSoft: /ping
    [16:20] rstxtfwd@appspot.com: Pushing ping notification to your device...
    [16:21] rstxtfwd@appspot.com: Pingback received from device after 69.899 seconds.
    [16:24] RoliSoft: /ping
    [16:24] rstxtfwd@appspot.com: Pushing ping notification to your device...
    [16:24] rstxtfwd@appspot.com: Pingback received from device after 6.427 seconds.
    [16:46] RoliSoft: /ping
    [16:46] rstxtfwd@appspot.com: Pushing ping notification to your device...
    [16:46] rstxtfwd@appspot.com: Pingback received from device after 19.068 seconds.
    [17:09] RoliSoft: /ping
    [17:09] rstxtfwd@appspot.com: Pushing ping notification to your device...
    [17:10] rstxtfwd@appspot.com: Pingback received from device after 93.239 seconds.

Ping requests while the phone is under partial wakelock or screen is on:

    [16:16] RoliSoft: /ping
    [16:16] rstxtfwd@appspot.com: Pushing ping notification to your device...
    [16:16] rstxtfwd@appspot.com: Pingback received from device after 0.268 seconds.
    [16:25] RoliSoft: /ping
    [16:25] rstxtfwd@appspot.com: Pushing ping notification to your device...
    [16:25] rstxtfwd@appspot.com: Pingback received from device after 0.325 seconds.
    [17:01] RoliSoft: /ping
    [17:01] rstxtfwd@appspot.com: Pushing ping notification to your device...
    [17:01] rstxtfwd@appspot.com: Pingback received from device after 0.287 seconds.
    [17:59] RoliSoft: /ping
    [17:59] rstxtfwd@appspot.com: Pushing ping notification to your device...
    [17:59] rstxtfwd@appspot.com: Pingback received from device after 0.441 seconds.

## Commands

This section contains a list of supported commands.

### Server-side commands

These commands are handled by the AppEngine application before they may result in a GCM push to the device. A list of these commands is also available in the server-side application's repository as well, this is only kept as reference. For the latest list and behaviour of the commands, please refer to that repository.

### /help [server*|device]

The server or device replies with the list of commands it supports including some minimal explanation of what they do. The device reply may take up to 2 minutes to complete if your device is in deep sleep. The default parameter is the server's response.

### /ping

Pushes a ping notification through GCM to the device and when the device receives it, it invokes the `PingbackHandler` on the AppEngine, returning the original timestamp.

### /send *name*: *message*

Sends a text message to *name*. The name parameter can be of any length and contain any characters, except `:`, which is the name/text separator. Spaces around the separator will be trimmed. The message can contain further `:` characters without any issues.

To find out how the name parameter works, refer to `/chat`.

### /chat *name*

Opens a new chat window dedicated to *name*. Anything sent to that window will be forwarded as an SMS, with the exception of commands. (Anything that starts with `/`.)

The way this works, is that instead of talking to `rstxtfwd.appspot.com` the application will clean the *name* parameter and send you a message from `name@rstxtfwd.appspotchat.com`.

The *name* can be a phone number or either full or partial name. The action will be carried out on the first match. If a contact has multiple phone numbers, you can append `/N` to the name where `N` is the index of the phone number as seen in your address book, starting from 1. If you do not append an index, but have a number marked as default, then that will be used. If not, the first mobile number will be used.

To make sure your first match is the actual number you're looking for, you can play around with `/contact`.

### Client-side commands

Every command that is not handled on the server side is pushed through GCM to the device. The client currently supports these commands:

### /contact *name*

Lists the contacts that match the name or phone number fully or partially. Accepts `/N`.

The purpose of this command is to make sure you're addressing the right contact when you're sending a message.

### /whois

This command only works in the dedicated chat window. Its purpose is to return the full name and phone number of the receiving end of the forwarded messages.

### /locate

The device will reply with the last known network and GPS locations. These locations may not be accurate at all, because if the device was turned off and moved, then it will return the old location. In these situations look for `/track` for solutions.

### /track [start|stop|status*|provider|exploit]

This command allows you to track your phone's location.

 - **start**: Starts tracking the phone with the best available provider.
 - **stop**: Stops tracking the phone.
 - **status**: Gets the status of the `LocationListener`. Returns whether it's running, what provider is in use, and when was the last location update.
 - **provider**: Gets the name of the currently available best location provider. (Mostly either `gps` or `network`.)
 - **exploit**: Tries to enable the GPS provider, if disabled, with a method that doesn't require root, but may not work on all devices. Tested on Nexus 4 running Android 4.2.2.

Soon the `exploit` argument will try using root to turn on GPS as a fallback method. See `TODO` in the source.

## Important

**You have to add at least one address from your AppEngine's address to your friends list, otherwise the server won't receive your full JID when you go online, and messages may not be delivered properly, depending on the server and client configurations.** Google Talk, for example, will bounce all messages that are not sent to a specific login location.

## Installation

1. Get Android Studio, however you should be able to compile to app with any Android dev toolkit.
2. Get Android SDK, unless you have previously installed Android Studio, which already came with a copy.
3. Import the project to your IDE.
4. Open the source for `MainActivity` and replace the `AppID` constant to point to your own server.
5. Generate a key which will be used to sign the `apk`.
6. Compile the project.

You will have a hard time if you'll try to use the app in the Android emulator, since it needs Google Play Services which in turn needs a Google account which is signed in and connected to Google Cloud Messaging. However, it is possible, by creating a new emulator by using "Google APIs" as the platform and then going through the process of setting up an Android with Google.

The server currently filters out GMail and Simonics Google Voice Gateway presences.

## Usage

Check out the server-side Python code that was designed to run on an AppEngine instance. After successfully setting that server up and compiling this client-side code, push the `apk` onto your device and install it.

Upon opening the software, it will register for GCM, so you'll need internet access. However, you may re-register at any time by clicking the "Register GCM" menu option, should any problems arise. After the registration was successful, you don't have to do anything else. Close the app and everything will be forwarded bidirectionally. You may suspend the bidirectional forwarding globally by going back into the app and selecting "Suspend" from the menu.

The app will automatically suspend itself when there is no internet connection, so don't worry about the app constantly trying to contact your server then timing out if you receive a message.

## Security

Stronger authentication is planned, however encryption is not. Get an SSL certificate if you want to encrypt the traffic between the phone and the AppEngine server.

Beware, the `AppEngine server` -> `Google's XMPP server` -> `your provider's XMPP server` -> `your client` route and vice versa is not encrypted, and there might be nothing you can do about it. But then again, you shouldn't be sharing sensitive information through SMS to begin with...

## License

Both the server-side and client-side applications are licensed under [AGPLv3](http://en.wikipedia.org/wiki/Affero_General_Public_License). Consult the `LICENSE` file for more information.

tl;dr: The Affero GPL v3:

- closes the 'ASP loophole' by mandating the delivery of source code by service providers
- ensures that modified versions of the code it covers remain free and open source

If you'd like to use the code without attribution and under a different license that isn't reciprocal and doesn't address the application service provider loophole, contact me via email for further information.