# Pearl Catch

A Meteor Client addon. Throws an ender pearl, tracks the real pearl entity,
and fires a wind charge timed to arrive where the pearl will be.

## Building

You need **JDK 21 or newer** (get it from https://adoptium.net). Check with `java -version`.

Targets Minecraft 1.21.11 with Meteor 1.21.11-SNAPSHOT.

In this folder:

    ./gradlew build          (macOS / Linux)
    gradlew.bat build        (Windows)

First run downloads Minecraft, Fabric and Meteor. It takes a few minutes.

The finished mod appears at:

    build/libs/pearl-catch-0.1.0.jar

Ignore any file ending in `-sources.jar`.

## Installing

Put `pearl-catch-0.1.0.jar` in `.minecraft/mods` next to `meteor-client.jar`.
Launch with Fabric Loader. The module is under the **Pearl Catch** category
in the Meteor menu (Right Shift).

## Using it

1. Enable the module.
2. Put an ender pearl and a wind charge in your hotbar.
3. Press G.

## Tuning

If it never fires, raise `timing-tolerance`. If it fires too early, raise
`min-lead-ticks`. `aim-offset-y` controls how far below the pearl it aims.
Test in singleplayer with `render-target` on and watch where the green box lands.
