### Remap
To support multiple Minecraft versions with the ReplayMod, a preprocessor is used which transforms source code to compile against different Minecraft versions.

To keep preprocessor statements to a minimum and support changes in mapping (of originally obfuscated Minecraft names), the preprocessor additionally supports
source remapping of class, method and field names implemented in the application through use of an embedded IntelliJ IDEA (Java and Kotlin sources are supported).

Additionally, it supports defining simple "search and replace"-like patterns (but smarter in that they are type-aware) annotated by a `@Pattern` (configurable) annotation in one or more central places which then are applied all over the code base.
This allows code which would previously have to be written with preprocessor statements or as `MCVer.getWindow(mc)` all over the code base to instead now use the much more intuitive `mc.getWindow()` and be automatically converted to `mc.window` (or even a Window stub object) on remap if a pattern for that exists anywhere in the same source tree:
```java
    @Pattern
    private static Window getWindow(MinecraftClient mc) {
        //#if MC>=11500
        return mc.getWindow();
        //#elseif MC>=11400
        //$$ return mc.window;
        //#else
        //$$ return new com.replaymod.core.versions.Window(mc);
        //#endif
    }
```
All pattern cases should be a single line as to not mess with indentation and/or line count.
Any arguments passed to the pattern must be used in the pattern in the same order in every case (introducing in-line locals to work around that is fine).
Defining and/or applying patterns in/on Kotlin code is not yet supported.

This is not integrated into the preprocessor itself for essentially two (now historical) reasons:
- License incompatibility between the GPL used in the ReplayMod (and the preprocessor) and the EPL used by the JDT
- Lombok requires a javaagent to work for the JDT, so we need to fork off into a separate JVM anyway

## License
The Remap is provided under the terms of the GNU General Public License Version 3 or (at your option) any later version.
See `LICENSE.md` for the full license text.
