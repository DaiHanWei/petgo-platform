## 9.0.7652

* Supported notification reach statistics for the FCM channel.
* Optimized log printing and push registration logic.
* Fixed occasional crashes on iOS.
* Adapted to Android Gradle Plugin (AGP) 9.0.

## 8.9.7538

* VoIP Logic Optimization.

## 8.9.7537

* Added push template feature.
* Optimized the logic of the push registration flow in the troubleshooting tool.
* Optimized the status update logic for the notification bar.
* Added support for custom app icon badges on Honor devices.
* Optimized the usage logic for push registration.

## 8.8.7375

* Optimized the registration frequency limit for the VIVO push channel.
* Optimized the event dispatching logic for incoming call messages when the app is in the foreground.
* Optimized the channel selection logic for push service registration.

## 8.8.7357

* Optimize the reporting logic for reach.
* Optimize the update logic for badges.
* Upgrade the honor and vivo push packages.
* Support Push online message click report.
* Optimize the registration push logic.

## 8.7.7201

* Support OPPO private message channel push templates.
* Support setting FCM message priority.
* Support vivo notification type settings.
* Optimize OPPO push registration success rate.
* Support Apple LiveCommunicationKit features.
* Optimize iOS multi-product integration didReceiveRemoteNotification conflict issues.
* Remove glide dependency and optimize large image notification display functionality.
* Optimize error code parsing logic.

## 8.6.7019+1

* Adapt to Flutter SDK 3.29.3.

## 8.6.7019

* Support push message adaptation to device system language.
* Support Meizu message categorization.
* Solve the login issue in push registration notifications.
* Resolve the double-click callback issue after iOS process termination.

## 8.5.6864

* Support push capability for specified RegistrationID
* Support automatic adaptation feature for overseas FCM channel
* Optimize the unregistration logic in hybrid mode
* Optimization of iOS foreground or background status concurrency issues
* Optimization of the issue of closing notification bar callback on iOS
* Optimization of Push error codes

## 8.4.6667+3

* Update native android aar

## 8.4.6667+2

* Solving the multi-process initialization issue.

## 8.4.6667+1

* Optimize message receiving logic.

## 8.4.6667

* Optimize the logic for unregistering push notifications.
* Support categorization of honor offline messages.
* Add offline storage functionality for Push messages.
* Optimize the logic for handling offline roaming messages.

## 8.3.6498+2

* Optimize FCM VOIP logic.
* Optimize the push notification registration logic to not strongly depend on stack verification.

## 8.3.6498+1

* To resolve the issue of the main function being initialized twice

## 8.3.6498

* Add a notification bar click event listener method onNotificationClicked.
* Add a method createNotificationChannel to support creating notification channels.

## 8.2.6325

* Add support for the feature of non-persistent push messages.
* FCM supports custom redirection upon clicking the notification bar.
* Optimize the log printing functionality before registering for push notifications.

## 8.1.6907

* Resolve database concurrency issues.

## 8.1.6906

* Resolve the issue of Push user login type error.
* Fix the issue where APNs fails to receive push notifications due to proxy failure.
* Optimize the issue where APNs offline pass-through messages with an empty Ext do not trigger the click event callback.
* Resolve the issue of abnormal callback when parsing notifications in the foreground state for APNs.
* Optimize the issue of FCM data empty message pop-up.

## 8.1.6107

* Upgrade under layer Push SDK to 8.1.

## 8.0.6897

* Added `setXiaoMiPushStorageRegion` method for XiaoMi devices.
* Enhanced `registerPush` on Android devices by adding result return.
* Replaced `androidPushOEMConfig` setting for `registerPush` method with `setAndroidCustomConfigFile` method.
* Renamed `setAndroidCustomTIMPushConfigs` and `configFCMPrivateRing` to `setAndroidCustomConfigFile` and `setCustomFCMRing`, respectively.
* Introduced smart detection for available channel strategies.
* Implemented push registration timeout protection mechanism.
* Fixed issues with small icon settings.
* Resolved app launch failure when jump option configuration was set to the home page.
* Refined device model recognition logic.
* Boosted code stability and optimization.

## 7.9.5668+1

* Fixed an issue that may throw an exception during the `registerPush` process.
* Downgraded the minimum supported Flutter version to `flutter: '>=2.10.0'`.
* Fixed an issue that may cause an exception for FCM launching processes.

## 7.9.5668
* Implemented additional enhancements to our Native Push Plugin in version 7.9.5668.
* Fixed several bugs.

## 7.8.5484

* Introduced `registerOnAppWakeUpEvent` function to enable registering a listener when the app is activated due to a Google FCM high-priority background message, ensuring efficient handling of critical notifications.
* Lowered the minimum supported iOS version to iOS 11.
* Enhanced the `registerPush` and `unRegisterPush` processes for better performance.
* Implemented additional enhancements to our Native Push Plugin in version 7.8.5484.
* Fixed various bugs.

## 7.7.5283

* Introduced `setAndroidPushToken` and `getAndroidPushToken` methods for Android devices.
* Implemented `setAndroidCustomTIMPushConfigs` for Android devices, allowing customization of the
  push configuration file.
* Upgraded Push SDK version for Honor devices.
* Incorporated additional improvements in our Native Push Plugin for version 7.7.5283.

## 1.0.0

* First release of Tencent Cloud Chat Push Plug-in for Flutter.