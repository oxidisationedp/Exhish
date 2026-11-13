# Exhish

Exherbo shell for Android (Termux, proot + busybox).

## Native helper libraries

The folder `app/src/main/jniLibs/<abi>/` must contain the following files: `libproot.so`,
`libproot_loader.so` and `libbusybox.so`. These are uncompiled files. The app will function,
but display an error saying "libproot.so missing". These files should be committed or present before building.

## Building

The build configuration for this repository is set up for GitHub builds. If you are building locally or in Termux,
you'll need to add machine specific information such as `sdk.dir`, `ndk.dir`, `cmake.dir`, `android.aapt2FromMavenOverride`,
`compileSdk` and `build-tools` to either the local properties file (not tracked by git) or `~/.gradle/gradle.properties`.
This information should not go into the `gradle.properties` file (tracked by git) in this repository.

There is no gradle wrapper in this repository. Run the command `gradle wrapper --gradle-version 8.5` to generate the `gradlew`.
compile the needed libraries:

```sh
sh scripts/build.sh
```

Then build the APK:

```sh
gradle assembleDebug
```

## Credits

We would like to thank:

Termux for providing us with the terminal-view and terminal-emulator libraries to allow
us to have a terminal interface and keyboard input.

PRoot which we used to allow us to have Exhish. This means that we can run Exherbo on your phone
without needing root.

BusyBox which we used to extract and set up the Exherbo system.

Exherbo: This is the Linux system you will be using. This includes Paludis and cave.

## License

Exhish is free and open source. It is licensed under the MIT license provided by Oxidisation EDP.
This means that you can use, edit, publish and use parts of this in your own projects.
You must keep the copyright notice in the LICENSE file.

Exhish includes other software with their own licenses (Termux, PRoot, BusyBox). See: THIRD_PARTY.md.

