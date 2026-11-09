# SMS2Email

An Android application that forwards incoming SMS messages to email.

This app is useful for users who need to access their SMS messages remotely.

## Multiple endpoints

The app can forward each SMS through several email accounts. Each account is called an endpoint. An endpoint has its own SMTP settings and its own list of recipients. Every enabled endpoint sends a copy of each received SMS to each of its recipients.

- Tap **+ Gmail**, **+ iCloud**, or **+ Custom** to add an endpoint. The Gmail and iCloud buttons fill in the server, the port, and the encryption.
- Gmail needs a 16-character Google app password, not the account password.
- iCloud needs an Apple app-specific password. The username and the From address must be the full iCloud Mail address.
- One account can send to any address. For example, a Gmail endpoint can send to iCloud addresses. A second endpoint adds a backup path if the first account fails.

The success notification counts the messages that the SMTP servers accepted. A server can still reject a message later, so the count does not confirm delivery to the inbox.

<p align="center"><a href="https://f-droid.org/packages/io.github.sms2email.sms2email"><img src="https://fdroid.gitlab.io/artwork/badge/get-it-on.png" alt="Get it on F-Droid"  width="207" /></a>
<a href="https://apt.izzysoft.de/fdroid/index/apk/io.github.sms2email.sms2email"><img src="https://raw.githubusercontent.com/InfinityLoop1308/PipePipe/refs/tags/v5.0.0-beta4/assets/IzzyOnDroid.png" alt="Get it on IzzyOnDroid" width="207" /></a></p>

| 🌙                                                                        | ☀️                                                                         |
| ------------------------------------------------------------------------- | -------------------------------------------------------------------------- |
| ![Screenshot](metadata/en-US/images/phoneScreenshots/screenshot_dark.png) | ![Screenshot](metadata/en-US/images/phoneScreenshots/screenshot_light.png) |

![IzzyOnDroid Badge](https://img.shields.io/endpoint?url=https://apt.izzysoft.de/fdroid/api/v1/shield/io.github.sms2email.sms2email&label=IzzyOnDroid&style=flat-square)
[![IzzyOnDroid Yearly Downloads](https://img.shields.io/badge/dynamic/json?url=https://dlstats.izzyondroid.org/iod-stats-collector/stats/basic/yearly/rolling.json&query=$.['io.github.sms2email.sms2email']&label=IzzyOnDroid%20yearly%20downloads&style=flat-square)](https://apt.izzysoft.de/packages/io.github.sms2email.sms2email)
[![RB Status](https://shields.rbtlog.dev/simple/io.github.sms2email.sms2email?style=flat-square)](https://shields.rbtlog.dev/io.github.sms2email.sms2email)
