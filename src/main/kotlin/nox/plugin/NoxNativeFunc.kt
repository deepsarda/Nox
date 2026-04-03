package nox.plugin

import nox.runtime.RuntimeContext

/**
 * The runtime adapter interface for all linked plugin functions.
 *
 * Every plugin function (Tier 0 annotated or Tier 1 external) is compiled
 * into a concrete implementation of this interface at link time. The VM
 * invokes these through the `SCALL` instruction.
 *
 * See docs/extensibility/ffi-internals.md.
 *
 * @see Linker for how adapters are generated from annotated methods.
 */
fun interface NoxNativeFunc {
    /**
     * Execute the native function.
     *
     * @param context  the runtime context
     * @param pMem     the primitive register bank
     * @param rMem     the reference register bank
     * @param bp       base pointer for primitives (current frame start)
     * @param bpRef    base pointer for references
     * @param argStart offset where arguments begin (relative to bp/bpRef)
     * @param destReg  register where the result should be stored
     */
    @Throws(Throwable::class)
    suspend fun invoke(
        context: RuntimeContext,
        pMem: LongArray,
        rMem: Array<Any?>,
        bp: Int,
        bpRef: Int,
        primArgStart: Int,
        refArgStart: Int,
        destReg: Int,
    )
}
