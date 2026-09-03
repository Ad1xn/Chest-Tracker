plugins {
    id("dev.kikugie.stonecutter")
}

stonecutter active "1.21.11" /* [SC] DO NOT EDIT */

stonecutter parameters {
    // Available to source files as `//? if unobfuscated { ... }`
    constants["unobfuscated"] = eval(current.version, ">=26.1")
    constants["obfuscated"] = eval(current.version, "<26.1")
}
