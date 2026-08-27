package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.navigation.LocalAvoidanceController;
import com.atlasdead.wanderbot.navigation.TerrainAnalyzer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/** Compatibility facade for the telemetry/API layer. The actual movement logic lives in CombatNavigationController. */
public final class CombatNavigationCoordinator {
    public enum Outcome { NONE, DIRECT, DETOUR, BLOCKED, PROTECTED }

    private final CombatNavigationController controller;
    private Outcome lastOutcome = Outcome.NONE;

    public CombatNavigationCoordinator(Minecraft mc, TerrainAnalyzer terrain, LocalAvoidanceController avoidance) {
        this.controller = new CombatNavigationController();
    }

    public Outcome update(EntityPlayerSP self, EntityPlayer target, double desiredDistance, int crowdPressure,
                          boolean retreat, boolean reversal, long tick) {
        if (self == null || target == null) {
            lastOutcome = Outcome.NONE;
            return lastOutcome;
        }
        CombatTacticalModel.State tactical = null;
        CombatNavigationController.Result result = controller.compute(self.worldObj, self, target, tactical, self.getDistanceToEntity(target));
        if (result.blocked) lastOutcome = Outcome.BLOCKED;
        else if (result.detoured || result.cliffRisk) lastOutcome = Outcome.DETOUR;
        else lastOutcome = Outcome.DIRECT;
        return lastOutcome;
    }

    public void reset() { lastOutcome = Outcome.NONE; }
    public Outcome getLastOutcome() { return lastOutcome; }
}
