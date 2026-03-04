/**
 * Three-tier plugin / extensibility system:
 *  Tier 0: @NoxModule + @NoxFunction + @NoxTypeMethod (Kotlin annotations, compiled in)
 *  Tier 1: Native plugins loaded via C ABI (dlopen / LoadLibrary)
 *  Tier 2: Script imports (import "path.nox" as ns)
 *
 *  LibraryRegistry: single source of truth consulted by both compiler and VM
 */
package nox.plugin
