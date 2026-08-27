package com.atlasdead.wanderbot.pit;

import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/** High-level Pit policy. Navigation remains the only subsystem responsible for locomotion. */
public class PitDecisionEngine {
    private final Minecraft mc;
    private final PitStateDetector detector = new PitStateDetector();
    private final TargetTracker targets = new TargetTracker();
    private final StreakManager streak = new StreakManager();
    private final PitZoneManager zones = new PitZoneManager();
    private final PitMapIntelligence map = new PitMapIntelligence();
    private final PitEventDetector events = new PitEventDetector();
    private final PitEventStrategy eventStrategy = new PitEventStrategy();
    private final PitEventStreakStrategy eventStreakStrategy = new PitEventStreakStrategy();
    private final StreakStrategyEngine strategy;
    private final PitProgressTracker progress;
    private final PitRulesEngine rules = new PitRulesEngine();
    private final PitCombatStrategyCoordinator combatStrategy;
    private final CombatDecisionEngine combat;
    private CombatExecutionController executor;
    private PitEventDetector.Result eventState = PitEventDetector.Result.none();
    private PitEventStrategy.Result eventDecision = PitEventStrategy.Result.normal();
    private PitEventStreakStrategy.Result eventStreakDecision = PitEventStreakStrategy.Result.normal();
    private PitMode mode = PitMode.OFF;
    private OopsAction oopsAction;
    private int modeTicks;
    private int disengageTicks;
    private long runtimeTick;
    private PitRuntimeSnapshot snapshot = PitRuntimeSnapshot.empty();
    private final PitMasterDecisionEngine masterDecision;

    public PitDecisionEngine(Minecraft mc) {
        this.mc = mc;
        this.combat = new CombatDecisionEngine(mc, zones, targets, streak);
        this.strategy = new StreakStrategyEngine(streak, targets, zones, map);
        this.progress = new PitProgressTracker(streak);
        this.combatStrategy = new PitCombatStrategyCoordinator(mc, zones, targets, streak, strategy);
        this.masterDecision = new PitMasterDecisionEngine(mc, this);
    }


    public void attachCombatExecutor(CombatExecutionController executor) {
        this.executor = executor;
        if (executor != null) executor.bindContext(zones, targets);
    }

    public CombatExecutionController.State executeCombat(EntityPlayerSP self, EntityPlayer target, CombatDecisionEngine.Action action) {
        if (executor == null) return null;
        return executor.tick(self, target, action, System.currentTimeMillis());
    }

    public void start() {
        mode = PitMode.WAITING;
        modeTicks = 0;
        streak.reset();
        configureDefaultStreakControl();
        zones.reset();
        eventState = PitEventDetector.Result.none();
        eventDecision = PitEventStrategy.Result.normal();
        eventStreakDecision = PitEventStreakStrategy.Result.normal();
        disengageTicks = 0;
        progress.reset();
        rules.reset();
        runtimeTick = 0L;
        snapshot = PitRuntimeSnapshot.empty();
    }

    public void stop() {
        map.reset();
        mode = PitMode.OFF;
        modeTicks = 0;
        targets.clear();
        zones.reset();
        eventState = PitEventDetector.Result.none();
        eventDecision = PitEventStrategy.Result.normal();
        eventStreakDecision = PitEventStreakStrategy.Result.normal();
        disengageTicks = 0;
        progress.reset();
        rules.reset();
        runtimeTick = 0L;
        snapshot = PitRuntimeSnapshot.empty();
    }

    public void tick() {
        if (mode == PitMode.OFF) return;
        EntityPlayerSP self = mc.thePlayer;
        if (self == null || mc.theWorld == null) {
            mode = PitMode.UNSAFE;
            return;
        }
        modeTicks++;

        PitStateDetector.Result state = detector.detect(mc);
        if (!state.available) {
            mode = PitMode.UNSAFE;
            targets.clear();
            return;
        }
        zones.learnFromSafeState(self, state.waiting, state.warmup);
        map.update(mc, zones);
        eventState = events.detect(state.lines);
        eventDecision = eventStrategy.evaluate(eventState, null, self, targets.getTarget());
        streak.observeScoreboard(state.lines, System.currentTimeMillis());
        PitRulesEngine.State rulesState = rules.evaluate(self, state, eventState, zones, streak);
        eventDecision = eventStrategy.evaluate(eventState, rulesState, self, targets.getTarget());
        eventStreakDecision = eventStreakStrategy.evaluate(eventState, streak.getControl().getActiveMegastreak(), streak.getEffectiveStreak());
        progress.observeScoreboard(true);
        progress.observePlayer(mc);
        progress.observeState(state.waiting, state.warmup, zones.isSelfProtected(self));
        runtimeTick++;
        EntityPlayer currentTarget = targets.getTarget();
        PitStreakCatalog.Definition mega = streak.getControl().getActiveMegastreak();
        snapshot = new PitRuntimeSnapshot(runtimeTick, mode, state, eventState, rulesState,
                streak.getEffectiveStreak(), mega, map.zone(), map.hazard(), map.openness(),
                currentTarget != null, currentTarget == null ? null : currentTarget.getName());

        // Spawn is a non-combat zone. Never acquire or retain a target while
        // the bot itself is protected, and never pursue a player who is inside
        // the protected spawn area.
        if (state.waiting) {
            mode = PitMode.WAITING;
            targets.clear();
            return;
        }
        if (state.warmup) {
            mode = PitMode.WARMUP;
            targets.clear();
            return;
        }
        if (zones.isSelfProtected(self)) {
            mode = PitMode.WARMUP;
            targets.clear();
            return;
        }

        // Major events change normal streak/combat flow. Stay passive while
        // an event is visible on the scoreboard; do not carry a stale target
        // into a different event state. Minor events do not hard-pause combat.
        if (!rules.mayAcquireCombatTarget(rulesState) || rulesState.phase == PitRulesEngine.MatchPhase.EVENT
                || eventDecision.action == PitEventStrategy.Action.PAUSE_COMBAT) {
            mode = PitMode.PAUSED;
            targets.clear();
            return;
        }

        if (self.getHealth() <= 0.0F) {
            streak.markDeath();
            targets.clear();
            mode = PitMode.RECOVERING;
            return;
        }

        if (mode == PitMode.RECOVERING && modeTicks > 20) mode = PitMode.STREAKING;
        if (disengageTicks > 0) {
            disengageTicks--;
            targets.clear();
            mode = PitMode.STREAKING;
            return;
        }

        if (mode == PitMode.OOPS) {
            if (oopsAction == null || oopsAction.tick(mc, modeTicks)) {
                mode = PitMode.STREAKING;
                modeTicks = 0;
            }
            return;
        }

        EntityPlayer target = targets.findBest(mc.theWorld, self, 18.0D, zones, strategy);
        if (target == null) {
            mode = PitMode.STREAKING;
            return;
        }
        MegastreakProfileEngine.Profile profile = strategy.getActiveMegastreakProfile();
        StreakStrategyEngine.Result strategic = strategy.evaluate(mc.theWorld, self, target);
        if (eventStreakDecision.active && !eventStreakDecision.combatAllowed) {
            targets.clear();
            mode = PitMode.PAUSED;
            return;
        }
        if (eventStreakDecision.active && eventStreakDecision.prioritizeEvent
                && eventStreakDecision.rewardMultiplier > eventStreakDecision.riskMultiplier) {
            strategic = new StreakStrategyEngine.Result(strategic.action, strategic.fightValue,
                    strategic.risk * eventStreakDecision.riskMultiplier,
                    strategic.reward * eventStreakDecision.rewardMultiplier,
                    strategic.reason + "|event=" + eventStreakDecision.reason);
        }
        if (strategic.action == StreakStrategyEngine.Action.DISENGAGE
                || strategic.action == StreakStrategyEngine.Action.RETARGET) {
            targets.clear();
            mode = PitMode.STREAKING;
            disengageTicks = streak.shouldProtectStreak() ? 12 : 6;
            return;
        }
        streak.observeTarget(target.getEntityId());
        mode = PitMode.STREAKING;
    }

    public long getRuntimeTick() { return runtimeTick; }

    public void handleRuntimeDeath() {
        if (mode != PitMode.OFF) {
            streak.markDeath();
            targets.clear();
            mode = PitMode.RECOVERING;
            modeTicks = 0;
            disengageTicks = 0;
            progress.reset();
            rules.reset();
            eventState = PitEventDetector.Result.none();
            eventDecision = PitEventStrategy.Result.normal();
            eventStreakDecision = PitEventStreakStrategy.Result.normal();
        }
    }

    public void resetForWorldChange() {
        if (mode != PitMode.OFF) {
            map.reset();
            targets.clear();
            zones.reset();
            streak.reset();
            progress.reset();
            rules.reset();
            eventState = PitEventDetector.Result.none();
            eventDecision = PitEventStrategy.Result.normal();
            eventStreakDecision = PitEventStreakStrategy.Result.normal();
            snapshot = PitRuntimeSnapshot.empty();
            runtimeTick = 0L;
            modeTicks = 0;
            mode = PitMode.WAITING;
        }
    }

    public MegastreakProfileEngine.Profile getMegastreakProfile() { return strategy.getActiveMegastreakProfile(); }

    public CombatDecisionEngine.Result evaluateCombat(EntityPlayerSP self, EntityPlayer target) {
        PitCombatStrategyCoordinator.Result strategic = combatStrategy.evaluate(self, target, eventState);
        if (strategic.verdict == PitCombatStrategyCoordinator.Verdict.DISENGAGE) {
            return new CombatDecisionEngine.Result(CombatDecisionEngine.Action.DISENGAGE,
                    strategic.threat, strategic.reward, strategic.reason);
        }
        if (strategic.verdict == PitCombatStrategyCoordinator.Verdict.REASSESS) {
            return new CombatDecisionEngine.Result(CombatDecisionEngine.Action.RETARGET,
                    strategic.threat, strategic.reward, strategic.reason);
        }
        CombatDecisionEngine.Result base = combat.evaluate(self, target);
        if (strategic.verdict == PitCombatStrategyCoordinator.Verdict.HOLD) {
            return new CombatDecisionEngine.Result(CombatDecisionEngine.Action.DISENGAGE,
                    Math.max(base.threatScore, strategic.threat), base.combatScore, strategic.reason);
        }
        return base;
    }

    public PitCombatStrategyCoordinator.Result evaluateCombatStrategy(EntityPlayerSP self, EntityPlayer target) {
        return combatStrategy.evaluate(self, target, eventState);
    }

    public void disengage(int ticks) {
        targets.clear();
        mode = PitMode.STREAKING;
        disengageTicks = Math.max(1, ticks);
    }

    public void enterOops() {
        mode = PitMode.OOPS;
        modeTicks = 0;
    }

    public PitMode getMode() { return mode; }
    public TargetTracker getTargets() { return targets; }
    public StreakManager getStreak() { return streak; }
    public StreakControlManager getStreakControl() { return streak.getControl(); }

    private void configureDefaultStreakControl() {
        // WanderBot only controls the selected megastreak.
        // Regular killstreak slots remain entirely player/server controlled.
        streak.getControl().reset();
        streak.getControl().setMegastreak(com.atlasdead.wanderbot.config.WanderBotSettings.megastreakId);
    }
    public PitStateDetector.Result getScoreboardState() { return detector.detect(mc); }
    public void setOopsAction(OopsAction action) { this.oopsAction = action; }
    public OopsAction getOopsAction() { return oopsAction; }
    public PitZoneManager getZones() { return zones; }
    public PitMapIntelligence getMap() { return map; }
    public PitEventDetector.Result getEventState() { return eventState; }
    public PitEventStrategy.Result getEventDecision() { return eventDecision; }
    public PitEventStreakStrategy.Result getEventStreakDecision() { return eventStreakDecision; }
    public StreakStrategyEngine getStrategy() { return strategy; }
    public PitProgressTracker getProgress() { return progress; }
    public PitRulesEngine getRules() { return rules; }
    public PitRulesEngine.State getRulesState() { return snapshot.rules; }
    public PitRuntimeSnapshot getRuntimeSnapshot() { return snapshot; }
    public PitMasterDecisionEngine getMasterDecision() { return masterDecision; }
    public CombatExecutionController getCombatExecutor() { return executor; }
    public CombatTelemetry getCombatTelemetry() { return executor == null ? CombatTelemetry.idle() : executor.getTelemetry(); }
    public TargetTracker getTargetTracker() { return targets; }

}
