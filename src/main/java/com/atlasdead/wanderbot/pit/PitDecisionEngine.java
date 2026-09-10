package com.atlasdead.wanderbot.pit;

import com.atlasdead.wanderbot.config.WanderBotSettings;
import net.minecraft.client.Minecraft;
import net.minecraft.client.entity.EntityPlayerSP;
import net.minecraft.entity.player.EntityPlayer;

/** High-level Pit policy. Navigation remains the only subsystem responsible for locomotion. */
public final class PitDecisionEngine {
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
    private final PitMasterDecisionEngine masterDecision;

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

    public CombatExecutionController.State executeCombat(
            EntityPlayerSP self, EntityPlayer target, CombatDecisionEngine.Action action) {
        return executor == null ? null : executor.tick(self, target, action, System.currentTimeMillis());
    }

    public void start() {
        mode = PitMode.WAITING;
        modeTicks = 0;
        disengageTicks = 0;
        runtimeTick = 0L;

        streak.reset();
        configureDefaultStreakControl();
        zones.reset();
        progress.reset();
        rules.reset();
        resetEventState();
        snapshot = PitRuntimeSnapshot.empty();
    }

    public void stop() {
        mode = PitMode.OFF;
        modeTicks = 0;
        disengageTicks = 0;
        runtimeTick = 0L;

        map.reset();
        targets.clear();
        zones.reset();
        progress.reset();
        rules.reset();
        resetEventState();
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
        if (WanderBotSettings.forcePitMode) {
            tickForcedMode(self);
            return;
        }

        PitStateDetector.Result state = detector.detect(mc);
        if (!state.available) {
            setModeAndClearTarget(PitMode.UNSAFE);
            return;
        }

        PitRulesEngine.State rulesState = updateRuntimeModels(self, state);
        updateSnapshot(state, rulesState);

        if (state.waiting) {
            setModeAndClearTarget(PitMode.WAITING);
            return;
        }
        if (state.warmup || zones.isSelfProtected(self)) {
            setModeAndClearTarget(PitMode.WARMUP);
            return;
        }
        if (!rules.mayAcquireCombatTarget(rulesState)
                || rulesState.phase == PitRulesEngine.MatchPhase.EVENT
                || eventDecision.action == PitEventStrategy.Action.PAUSE_COMBAT) {
            setModeAndClearTarget(PitMode.PAUSED);
            return;
        }
        if (self.getHealth() <= 0.0F) {
            streak.markDeath();
            setModeAndClearTarget(PitMode.RECOVERING);
            return;
        }

        if (mode == PitMode.RECOVERING && modeTicks > 20) {
            mode = PitMode.STREAKING;
        }
        if (consumeDisengageCooldown()) return;
        if (tickOopsMode()) return;

        EntityPlayer target = targets.findBest(
                mc.theWorld, self, WanderBotSettings.targetScanRange, zones, strategy);
        if (target == null) {
            mode = PitMode.STREAKING;
            return;
        }

        StreakStrategyEngine.Result strategic = strategy.evaluate(mc.theWorld, self, target);
        strategic = applyEventStreakPolicy(strategic);
        if (strategic == null) return;

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

    private void tickForcedMode(EntityPlayerSP self) {
        targets.findBest(mc.theWorld, self, WanderBotSettings.targetScanRange, zones, null);
        mode = PitMode.STREAKING;
    }

    private PitRulesEngine.State updateRuntimeModels(EntityPlayerSP self, PitStateDetector.Result state) {
        zones.learnFromSafeState(self, state.waiting, state.warmup);
        zones.updateAutoDetection(mc, self, state.waiting, state.warmup);
        map.update(mc, zones);

        eventState = events.detect(state.lines);
        streak.observeScoreboard(state.lines, System.currentTimeMillis());
        PitRulesEngine.State rulesState = rules.evaluate(self, state, eventState, zones, streak);
        eventDecision = eventStrategy.evaluate(eventState, rulesState, self, targets.getTarget());
        eventStreakDecision = eventStreakStrategy.evaluate(
                eventState,
                streak.getControl().getActiveMegastreak(),
                streak.getEffectiveStreak());

        progress.observeScoreboard(true);
        progress.observePlayer(mc);
        progress.observeState(state.waiting, state.warmup, zones.isSelfProtected(self));
        runtimeTick++;
        return rulesState;
    }

    private void updateSnapshot(PitStateDetector.Result state, PitRulesEngine.State rulesState) {
        EntityPlayer currentTarget = targets.getTarget();
        PitStreakCatalog.Definition mega = streak.getControl().getActiveMegastreak();
        snapshot = new PitRuntimeSnapshot(
                runtimeTick,
                mode,
                state,
                eventState,
                rulesState,
                streak.getEffectiveStreak(),
                mega,
                map.zone(),
                map.hazard(),
                map.openness(),
                currentTarget != null,
                currentTarget == null ? null : currentTarget.getName());
    }

    private boolean consumeDisengageCooldown() {
        if (disengageTicks <= 0) return false;
        disengageTicks--;
        targets.clear();
        mode = PitMode.STREAKING;
        return true;
    }

    private boolean tickOopsMode() {
        if (mode != PitMode.OOPS) return false;
        if (oopsAction == null || oopsAction.tick(mc, modeTicks)) {
            mode = PitMode.STREAKING;
            modeTicks = 0;
        }
        return true;
    }

    private StreakStrategyEngine.Result applyEventStreakPolicy(StreakStrategyEngine.Result strategic) {
        if (!eventStreakDecision.active) return strategic;

        if (!eventStreakDecision.combatAllowed) {
            targets.clear();
            mode = PitMode.PAUSED;
            return null;
        }

        if (eventStreakDecision.prioritizeEvent
                && eventStreakDecision.rewardMultiplier > eventStreakDecision.riskMultiplier) {
            return new StreakStrategyEngine.Result(
                    strategic.action,
                    strategic.fightValue,
                    strategic.risk * eventStreakDecision.riskMultiplier,
                    strategic.reward * eventStreakDecision.rewardMultiplier,
                    strategic.reason + "|event=" + eventStreakDecision.reason);
        }
        return strategic;
    }

    private void setModeAndClearTarget(PitMode newMode) {
        targets.clear();
        mode = newMode;
    }

    private void resetEventState() {
        eventState = PitEventDetector.Result.none();
        eventDecision = PitEventStrategy.Result.normal();
        eventStreakDecision = PitEventStreakStrategy.Result.normal();
    }

    public long getRuntimeTick() {
        return runtimeTick;
    }

    public void handleRuntimeDeath() {
        if (mode == PitMode.OFF) return;

        streak.markDeath();
        targets.clear();
        mode = PitMode.RECOVERING;
        modeTicks = 0;
        disengageTicks = 0;
        progress.reset();
        rules.reset();
        resetEventState();
    }

    public void resetForWorldChange() {
        if (mode == PitMode.OFF) return;

        map.reset();
        targets.clear();
        zones.reset();
        streak.reset();
        progress.reset();
        rules.reset();
        resetEventState();
        snapshot = PitRuntimeSnapshot.empty();
        runtimeTick = 0L;
        modeTicks = 0;
        disengageTicks = 0;
        mode = PitMode.WAITING;
    }

    public MegastreakProfileEngine.Profile getMegastreakProfile() {
        return strategy.getActiveMegastreakProfile();
    }

    public CombatDecisionEngine.Result evaluateCombat(EntityPlayerSP self, EntityPlayer target) {
        PitCombatStrategyCoordinator.Result strategic = combatStrategy.evaluate(self, target, eventState);
        if (strategic.verdict == PitCombatStrategyCoordinator.Verdict.DISENGAGE) {
            return new CombatDecisionEngine.Result(
                    CombatDecisionEngine.Action.DISENGAGE,
                    strategic.threat,
                    strategic.reward,
                    strategic.reason);
        }
        if (strategic.verdict == PitCombatStrategyCoordinator.Verdict.REASSESS) {
            return new CombatDecisionEngine.Result(
                    CombatDecisionEngine.Action.RETARGET,
                    strategic.threat,
                    strategic.reward,
                    strategic.reason);
        }

        CombatDecisionEngine.Result base = combat.evaluate(self, target);
        if (strategic.verdict == PitCombatStrategyCoordinator.Verdict.HOLD) {
            return new CombatDecisionEngine.Result(
                    CombatDecisionEngine.Action.DISENGAGE,
                    Math.max(base.threatScore, strategic.threat),
                    base.combatScore,
                    strategic.reason);
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

    private void configureDefaultStreakControl() {
        streak.getControl().reset();
        streak.getControl().setMegastreak(WanderBotSettings.megastreakId);
    }

    public PitMode getMode() { return mode; }
    public TargetTracker getTargets() { return targets; }
    public StreakManager getStreak() { return streak; }
    public StreakControlManager getStreakControl() { return streak.getControl(); }
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
    public CombatTelemetry getCombatTelemetry() {
        return executor == null ? CombatTelemetry.idle() : executor.getTelemetry();
    }
    public TargetTracker getTargetTracker() { return targets; }
}
