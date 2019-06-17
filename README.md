### Remap
To support multiple Minecraft versions with the ReplayMod, a preprocessor is used which transforms source code to compile against different Minecraft versions.

To keep preprocessor statements to a minimum and support changes in mapping (of originally obfuscated Minecraft names), the preprocessor additionally supports
source remapping of class, method and field names implemented in the application through use of an embedded IntelliJ IDEA (from kotlin-compiler to be precise, though Kotlin support has yet to to implemented).

This is not integrated into the preprocessor itself for essentially two (now historical) reasons:
- License incompatibility between the GPL used in the ReplayMod (and the preprocessor) and the EPL used by the JDT
- Lombok requires a javaagent to work for the JDT, so we need to fork off into a separate JVM anyway

## License
The Remap is provided under the terms of the GNU General Public License Version 3 or (at your option) any later version.
See `LICENSE.md` for the full license text.
