package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;

/** Server-specific action hook. Kept configurable because the exact "Oops" behavior belongs to the private test server. */
public interface OopsAction {
    boolean tick(Minecraft mc, int ticksInState);
    String getName();
}
