package app.morphe.patches.pixelcamera.location

import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.toInstructions
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction10t
import com.android.tools.smali.dexlib2.builder.instruction.BuilderInstruction21t
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

val nativeLocationPatch = bytecodePatch(
    name = "Use Android location instead of Google Play Services",
    description = "Forces photo/video geotagging to read location from the system " +
        "LocationManager (GPS + network providers) instead of the Google Play Services " +
        "Fused Location Provider, even when Play Services is installed and the " +
        "device's \"Use Google's location service\" setting is turned on. Also makes " +
        "the native provider fall back to the last known cached fix (approximate) " +
        "when no fresh live fix has arrived yet, instead of returning nothing.",
    default = false
) {
    compatibleWith(
        "com.google.android.GoogleCamera" to setOf("10.4.117.936816638.14")
    )
    execute {
        // ── 1. Force the native (android.location.LocationManager) provider ─────────
        //
        // Lkdx;->a()Luyx; is the location-provider selector (obfuscated names, pinned
        // to this build). After the permission + feature-flag gates it calls
        // GoogleApiAvailability#isGooglePlayServicesAvailable (Lppm;->h) and, when that
        // reports SUCCESS, goes on to check the "use_location_for_services" setting and
        // ultimately instantiate the Play-Services-backed provider (Lkdr;). When Play
        // Services is unavailable it instead falls straight through to the
        // android.location.LocationManager-backed provider (Lkdt;).
        //
        // Flipping the conditional branch that follows the availability check into an
        // unconditional jump (same target) makes the method always take the
        // native-provider path. Lkdr; and the GMS LocationRequest plumbing it depends
        // on are left untouched -- they simply become unreachable from here.
        //
        // Verified: this exact edit was decompiled, rebuilt, signed and installed on a
        // real device, where `dumpsys location` confirmed the app registers directly
        // with the system's "gps"/"network" providers and never reaches the GMS path.
        mutableClassDefByOrNull("Lkdx;")?.let { clazz ->
            clazz.methods.firstOrNull { it.name == "a" && it.parameterTypes.isEmpty() }
                ?.let { method ->
                    val impl = method.implementation ?: return@let
                    val instructions = impl.instructions

                    val availabilityCheckIdx = instructions.indexOfFirst { instruction ->
                        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                            ((instruction as Instruction35c).reference as? MethodReference)
                                ?.let { it.definingClass == "Lppm;" && it.name == "h" } == true
                    }
                    if (availabilityCheckIdx == -1) return@let

                    val branchIdx = (availabilityCheckIdx + 1 until instructions.size)
                        .firstOrNull { instructions[it].opcode == Opcode.IF_NEZ }
                        ?: return@let

                    // In a MutableMethodImplementation, branch instructions carry a
                    // symbolic Label (resolved to a byte offset at build time), not a
                    // raw numeric offset -- reuse the if-nez's own existing target label.
                    val target = (instructions[branchIdx] as BuilderInstruction21t).target
                    impl.replaceInstruction(branchIdx, BuilderInstruction10t(Opcode.GOTO, target))
                }
        }

        // ── 1b. Remove the remote feature-flag gate earlier in the same selector ────
        //
        // Before even reaching the GMS-availability check above, Lkdx;->a() first
        // reads a boolean from a remote/experiment config (Lnbq;->b(Lnbj;) with key
        // Lnbl;->a) and bails out to the same "no location" path as a denied
        // permission if it's false. On a re-signed, non-Google-signed build this
        // config may never sync (or default differently) and silently short-circuit
        // location before patch 1 above even gets a chance to run. Removing the
        // bailout branch entirely (not replacing it with anything) makes execution
        // always fall through as if the flag were true -- the same effect as just
        // deleting that one instruction in the raw smali patch.
        mutableClassDefByOrNull("Lkdx;")?.let { clazz ->
            clazz.methods.firstOrNull { it.name == "a" && it.parameterTypes.isEmpty() }
                ?.let { method ->
                    val impl = method.implementation ?: return@let
                    val instructions = impl.instructions

                    val flagFetchIdx = instructions.indexOfFirst { instruction ->
                        instruction.opcode == Opcode.INVOKE_VIRTUAL &&
                            ((instruction as Instruction35c).reference as? MethodReference)
                                ?.let { it.definingClass == "Lnbq;" && it.name == "b" } == true
                    }
                    if (flagFetchIdx == -1) return@let

                    val gateIdx = (flagFetchIdx + 1 until instructions.size)
                        .firstOrNull { instructions[it].opcode == Opcode.IF_EQZ }
                        ?: return@let

                    impl.removeInstruction(gateIdx)
                }
        }

        // ── 2. Fall back to the last known (approximate) fix when nothing is live ────
        //
        // Lkdt;->a()Luyx; (the native provider) only ever returns a location once its
        // LocationListener (Lkds;) has actually received a fresh onLocationChanged
        // callback. If neither "gps" nor "network" has delivered one yet it gives up
        // and returns empty -- there's no fallback to a cached fix the way GMS's fused
        // provider implicitly had. This appends a fallback, right before the method's
        // existing empty-result tail, that tries
        // LocationManager#getLastKnownLocation() for each provider (gps first/precise,
        // then network/approximate) and uses whichever is available.
        //
        // NOTE on verification: the multi-instruction smali block below, with its own
        // self-contained ":loop"/":next"/":skip" labels inserted via
        // toInstructions()/addInstruction(), matches a pattern directly confirmed in
        // real, shipped Morphe patches for this app (e.g. the "early return" insertion
        // used elsewhere, which also defines and jumps to a label entirely local to
        // its own snippet). What it does NOT replicate is the try/catch
        // (SecurityException/IllegalArgumentException) that the proven raw-smali
        // version has around getLastKnownLocation() -- see
        // smali_patches/approximate-location-fallback.patch for that exact bytecode.
        // No reference patch in that same codebase shows exception-handler ranges
        // being attached to an inserted instruction block via this simple
        // instruction-list API, so it's dropped here rather than guessed. In practice
        // this should be safe regardless: by the time Lkdt;->a() runs, Lkdx; has
        // already confirmed location permission is granted, and "gps"/"network" are
        // always-valid provider names on Android, so neither exception should
        // actually occur here -- but if you want the same defensive try/catch
        // Morphe-side, use the .patch file as the reference and adjust this block (or
        // just apply it as a raw smali patch instead).
        mutableClassDefByOrNull("Lkdt;")?.let { clazz ->
            clazz.methods.firstOrNull { it.name == "a" && it.parameterTypes.isEmpty() }
                ?.let { method ->
                    val impl = method.implementation ?: return@let

                    // Insert right before the method's final two instructions
                    // (invoke-virtual Luzk;->d(...)Z ; return-object v0), which already
                    // build and return the empty Optional -- this fallback block either
                    // returns early with a location, or falls through into that
                    // existing tail unchanged.
                    val insertAt = impl.instructions.size - 2

                    // Only v1-v4 are used below (v0 holds the Optional builder and must
                    // stay untouched for the rest of the method). This method's header
                    // declares ".locals 5" (v0-v4) with the implicit "this" parameter
                    // sitting right at register index 5 -- i.e. what looks like a free
                    // "v5" is actually "this" aliased under a different name, since
                    // toInstructions() compiles against the method's *existing*
                    // register window rather than growing it. v3 and v4 are each reused
                    // for two unrelated values, but never while both are still needed.
                    val fallbackSmali = """
                        iget-object v1, p0, Lkdt;->b:Landroid/location/LocationManager;
                        if-eqz v1, :skip
                        const/4 v2, 0x0
                        :loop
                        const/4 v3, 0x2
                        if-ge v2, v3, :skip
                        iget-object v3, p0, Lkdt;->c:[Lkds;
                        aget-object v3, v3, v2
                        iget-object v4, v3, Lkds;->c:Ljava/lang/String;
                        invoke-virtual {v1, v4}, Landroid/location/LocationManager;->getLastKnownLocation(Ljava/lang/String;)Landroid/location/Location;
                        move-result-object v4
                        if-eqz v4, :next
                        invoke-virtual {v0, v4}, Luzk;->d(Ljava/lang/Object;)Z
                        return-object v0
                        :next
                        add-int/lit8 v2, v2, 0x1
                        goto :loop
                        :skip
                    """.trimIndent()

                    var pos = insertAt
                    fallbackSmali.toInstructions(method).forEach { instruction ->
                        impl.addInstruction(pos++, instruction)
                    }
                }
        }
    }
}
