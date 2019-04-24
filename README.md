### Remap
To support multiple Minecraft versions with the ReplayMod, a preprocessor is used which transforms source code to compile against different Minecraft versions.

To keep preprocessor statements to a minimum and support changes in mapping (of originally obfuscated Minecraft names), the preprocessor additionally supports
source remapping of class, method and field names implemented in the application through use of the Eclipse JDT.

This is not integrated into the preprocessor itself for essentially two reasons:
- License incompatibility between the GPL used in the ReplayMod (and the preprocessor) and the EPL used by the JDT
- Lombok requires a javaagent to work for the JDT, so we need to fork off into a separate JVM anyway

## License
The Remap application is provided under the terms of the Eclipse Public License Version 2.
See `EPL-2.0.txt` for the full license text.