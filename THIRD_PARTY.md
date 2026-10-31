# Third Party Software

The code that is part of Exhish is released under the MIT license. Exhish also makes use of the following software and includes them in the application:

This list is provided as a summary and not legal advice. You should review the licenses from the upstream software before distributing the application.

## Termux Terminal Libraries

terminal-view, terminal-emulator from https://github.com/termux/termux-app

The termux repository is licensed under GPLv3 only. The code has been taken from Terminal Emulator for Android (Jack Palevich) so all of that code is under Apache 2.0. Please check the LICENSE.md file within the termux repository for up-to-date licensing information. These libraries are included in Exhish so you should ensure you adhere to the license. That means you must provide source code and pass the license information through.

## PRoot

https://proot-me.github.io/ (Build appears to be a modified version of termux)

Copyright (C) 2015 STMicroelectronics. Licensed under GPL v2+. A separate program (libproot.so, libproot_loader.so). Source can be found at PRoot and termux repositories.

## BusyBox

https://busybox.net/ (Android version based on osm0sis https://github.com/osm0sis/android-busybox-ndk)

Licensed under GPL v2. A separate program (libbusybox.so). Source can be found at the links above.

## Exherbo

https://exherbo.org

The Linux system that Exhish downloads and runs. This includes Paludis and the cave command. The packages will have their own licenses.
