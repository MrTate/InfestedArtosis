package macro;

import bwapi.Game;
import bwapi.Player;
import bwapi.Text;
import bwapi.Unit;
import bwapi.UnitType;
import bwapi.UpgradeType;

import bwem.Base;
import info.BaseData;
import info.GameState;
import info.ResourceCount;
import info.TechProgression;
import info.UnitTypeCount;
import planner.Plan;
import planner.PlanState;
import planner.PlanType;
import planner.PlanComparator;
import strategy.strategies.UnitWeights;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.stream.Collectors;

// TODO: There is economy information here, build order and strategy. refactor
// Possible arch: GATHER GAME STATE -> PLAN -> EXECUTE
// STRATEGY -> BUILD ORDER (QUEUE) -> BUILD / ECONOMY MANAGEMENT (rebalance workers) (this file should eventually only be final step)
//
public class ProductionManager {

    private Game game;

    private GameState gameState;

    // isPlanning contingent on -> hitting min supply set by build order OR queue exhaust
    private boolean isPlanning = false;

    // TODO: Track in info manager / GameState with some sort of base planner class
    private int numSunkens = 0;

    private int numExtractors = 0;

    private int scheduledBuildings = 0;

    // TODO: Determine from desired unit composition / active strategy
    // Because bot is only on hatch tech, only take 1 for now
    private int targetExtractors = 1;

    private int currentFrame = 5;
    private int plannedHatcheries = 1; // Start with 1 because we decrement with initial hatch
    private int plannedWorkers = 0;

    private PriorityQueue<Plan> productionQueue = new PriorityQueue<>(new PlanComparator());

    public ProductionManager(Game game, GameState gameState, List<Plan> initialBuildOrder) {
        this.game = game;
        this.gameState = gameState;

        init(initialBuildOrder);
    }

    private void init(List<Plan> initialBuildOrder) {
        TechProgression techProgression = gameState.getTechProgression();
        for (Plan plan : initialBuildOrder) {
            if (plan.getPlannedUnit() != null && plan.getPlannedUnit() == UnitType.Zerg_Extractor) {
                this.numExtractors += 1;
            }

            // TODO: be able to decide between base hatch and macro hatch
            if (plan.getPlannedUnit() != null && plan.getPlannedUnit() == UnitType.Zerg_Hatchery) {
                Base base = gameState.reserveBase();
                plan.setBuildPosition(base.getLocation());
            }

            if (plan.getPlannedUnit() != null && plan.getPlannedUnit() == UnitType.Zerg_Drone) {
                plannedWorkers += 1;
            }

            if (plan.getPlannedUnit() != null && plan.getPlannedUnit() == UnitType.Zerg_Spawning_Pool) {
                techProgression.setPlannedSpawningPool(true);
            }

            if (plan.getType() == PlanType.UPGRADE) {
                if (plan.getPlannedUpgrade() == UpgradeType.Metabolic_Boost) {
                    techProgression.setPlannedMetabolicBoost(true);
                }
            }


            this.productionQueue.add(plan);
        }
    }

    private void debugProductionQueue() {
        int numDisplayed = 0;
        int x = 4;
        int y = 64;
        for (Plan plan : productionQueue) {
            game.drawTextScreen(x, y, plan.getName() + " " + plan.getPriority(), Text.Green);
            y += 8;
            numDisplayed += 1;
            if (numDisplayed == 10) {
                break;
            }
        }

        if (numDisplayed < productionQueue.size()) {
            game.drawTextScreen(x, y, String.format("... %s more planned items", productionQueue.size() - numDisplayed), Text.GreyGreen);
        }
    }

    private void debugBaseStats() {

    }

    // TODO: Ensure print out of production queue is displaying how much time is remaining
    private void debugInProgressQueue() {
        int numDisplayed = 0;
        int x = 100;
        int y = 64;
        // TODO: Debug production queue in GameState
        for (Plan plan : gameState.getAssignedPlannedItems().values()) {
            game.drawTextScreen(x, y, plan.getName() + " " + plan.getPriority(), Text.Green);
            y += 8;
            numDisplayed += 1;
            if (numDisplayed == 10) {
                break;
            }
        }
    }

    // TODO: Ensure print out of production queue is displaying how much time is remaining
    private void debugScheduledPlannedItems() {
        int numDisplayed = 0;
        int x = 196;
        int y = 64;
        // TODO: Debug production queue in GameState
        for (Plan plan : gameState.getPlansScheduled()) {
            game.drawTextScreen(x, y, plan.getName() + " " + plan.getPriority(), Text.Green);
            y += 8;
            numDisplayed += 1;
            if (numDisplayed == 10) {
                break;
            }
        }
    }

    private void planBase() {
        Base base = gameState.reserveBase();
        // all possible bases are taken!
        if (base == null) {
            return;
        }

        productionQueue.add(new Plan(UnitType.Zerg_Hatchery, currentFrame, true, true, base.getLocation()));
    }

    // debug console messaging goes here
    private void debug() {
        debugProductionQueue();
        debugInProgressQueue();
        debugScheduledPlannedItems();
    }

    // TODO: Determine why some workers go and stay idle
    public void onFrame() {

        debug();

        currentFrame = game.getFrameCount();

        plan();
        schedulePlannedItems();
        buildUpgrades();

        for (Unit u: game.getAllUnits()) {
            if (u.getType().isWorker() && u.isIdle()) {
                assignUnit(u);
            }
        }
    }

    private int expectedWorkers() {
        final int base = 5;
        final int expectedMineralWorkers = gameState.getBaseData().currentBaseCount() * 7;
        final int expectedGasWorkers = gameState.getGeyserAssignments().size() * 3;
        return base + expectedMineralWorkers + expectedGasWorkers;
    }

    private int numWorkers() {
        return gameState.getMineralWorkers() + gameState.getGeyserWorkers();
    }

    private void planBuildings(Player self, Boolean isAllIn) {
        // Allow buildings to arbitrary queue size
        // Always allow hatch to enter queue even if we're at max size (means we are throttled on bandwith)

        // 2 Types of Hatch:
        // BaseData Hatch - Setup resources to assign workers, add to base data
        // Macro Hatch - Take a macro hatch every other time
        // Limit to 3 plannedHatch to prevent queue deadlock
        if (!isAllIn && (canAffordHatch() || (isNearMaxExpectedWorkers() && canAffordHatchSaturation())) && plannedHatcheries < 3) {
            plannedHatcheries += 1;
            final int numHatcheries = gameState.getBaseData().numHatcheries();
            if ((numHatcheries % 2) != 0) {
                planBase();
            } else {
                productionQueue.add(new Plan(UnitType.Zerg_Hatchery, currentFrame / 3, true, true));
            }
        }

        // One extractor per base
        //  - Not always true, some bases are mineral only. Some maps have double gas.
        // TODO: account for bases with no gas or 2 gas
        if (!isAllIn && numExtractors < gameState.getBaseData().currentBaseCount() && numExtractors < targetExtractors) {
            numExtractors += 1;
            productionQueue.add(new Plan(UnitType.Zerg_Extractor, currentFrame, true, false));
        }

        TechProgression techProgression = this.gameState.getTechProgression();

        // Build at 10 workers if not part of initial build order
        if (techProgression.canPlanPool() && self.supplyUsed() > 20) {
            productionQueue.add(new Plan(UnitType.Zerg_Spawning_Pool, currentFrame / 4, true, true));
            techProgression.setPlannedSpawningPool(true);
        }

        if (isAllIn) {
            return;
        }

        UnitWeights unitWeights = this.gameState.getUnitWeights();

        if (techProgression.canPlanHydraliskDen() && unitWeights.hasUnit(UnitType.Zerg_Hydralisk)) {
            productionQueue.add(new Plan(UnitType.Zerg_Hydralisk_Den, currentFrame, true, true));
            techProgression.setPlannedDen(true);
        }

        final boolean needLairTech = unitWeights.hasUnit(UnitType.Zerg_Mutalisk) || unitWeights.hasUnit(UnitType.Zerg_Scourge);

        if (needLairTech && techProgression.canPlanLair()) {
            productionQueue.add(new Plan(UnitType.Zerg_Lair, currentFrame, true, true));
            techProgression.setPlannedLair(true);
        }

        if (techProgression.canPlanSpire() && unitWeights.hasUnit(UnitType.Zerg_Mutalisk)) {
            productionQueue.add(new Plan(UnitType.Zerg_Spire, currentFrame, true, true));
            techProgression.setPlannedSpire(true);
        }
    }

    /**
     * Plan to take an upgrade.
     *
     * Does not plan if there is no gas; all upgrades require gas.
     *
     * TODO: Track when an upgrade completes
     *
     * NOTE: Potential for reinforcement learning to search when to take an upgrade against an opponent.
     * @param isAllIn
     */
    private void planUpgrades(Boolean isAllIn) {

        if (numExtractors == 0 || isAllIn) {
            return;
        }

        TechProgression techProgression = this.gameState.getTechProgression();
        UnitTypeCount unitTypeCount = gameState.getUnitTypeCount();

        /** Ling Upgrades **/
        if (techProgression.canPlanMetabolicBoost() && unitTypeCount.get(UnitType.Zerg_Zergling) > 8) {
            productionQueue.add(new Plan(UpgradeType.Metabolic_Boost, currentFrame, false));
            techProgression.setPlannedMetabolicBoost(true);
        }

        /** Hydra Upgrades */
        final int numHydralisks = unitTypeCount.get(UnitType.Zerg_Hydralisk);
        if (techProgression.canPlanMuscularAugments() && numHydralisks > 4) {
            productionQueue.add(new Plan(UpgradeType.Muscular_Augments, currentFrame, false));
            techProgression.setPlannedMuscularAugments(true);
        }
        if (techProgression.canPlanGroovedSpines() && numHydralisks > 10) {
            productionQueue.add(new Plan(UpgradeType.Grooved_Spines, currentFrame, false));
            techProgression.setPlannedGroovedSpines(true);
        }
    }


    // planSupply checks if near supply cap or supply blocked
    private void planSupply(Player self) {
        final int supplyRemaining = self.supplyTotal() - self.supplyUsed();
        int plannedSupply = gameState.getPlannedSupply();
        if (supplyRemaining + plannedSupply < 5 && self.supplyUsed() < 400) {
            gameState.setPlannedSupply(plannedSupply+16);
            productionQueue.add(new Plan(UnitType.Zerg_Overlord, currentFrame / 3, false, false));
        } else if (supplyRemaining + plannedSupply < 0 && self.supplyUsed() < 400) {
            gameState.setPlannedSupply(plannedSupply+16);
            productionQueue.add(new Plan(UnitType.Zerg_Overlord, currentFrame / 2, false, true));
        }
    }

    // TODO: Droning vs Combat Units
    private void planUnits(Player self, Boolean isAllIn) {
        if (self.supplyUsed() >= 400) {
            return;
        }
        // Plan workers
        // This should be related to num bases + aval min patches and geysers, limited by army and potentially higher level strat info
        // For now, set them to be 1/3 of total supply
        // Limit the number of drones in queue, or they will crowd out production!
        if (!isAllIn && plannedWorkers < 3 && numWorkers() < 80 && numWorkers() < expectedWorkers()) {
            plannedWorkers += 1;
            addUnitToQueue(UnitType.Zerg_Drone);
        }

        UnitWeights unitWeights = this.gameState.getUnitWeights();

        // Plan army
        UnitType unitToBuild = unitWeights.getRandom();
        if (unitToBuild == UnitType.Unknown) {
            return;
        }
        addUnitToQueue(unitToBuild);
    }

    private void addUnitToQueue(UnitType unitType) {
        UnitTypeCount unitTypeCount = this.gameState.getUnitTypeCount();
        productionQueue.add(new Plan(unitType, currentFrame, false, false));
        unitTypeCount.planUnit(unitType);
    }

    // TODO: Make this smarter, following a strategy to define unit mix, when to take upgrades, etc.
    private void plan() {
        Player self = game.self();
        Boolean isAllIn = gameState.isAllIn();

        if (!isPlanning && productionQueue.size() > 0) {
            return;
        }

        // Once opener items are exhausted, plan items
        isPlanning = true;

        planBuildings(self, isAllIn);

        // NOTE: Always let upgrades to enter the queue, we take them greedily
        // Plan tech / upgrades
        // The former should at least be driven by a higher level (strategy) manager
        // For now, greedily plan upgrades
        planUpgrades(isAllIn);

        // Plan supply
        planSupply(self);

        /** For now, only subject unit production to queue size */
        // BaseData queue size is 3, increases per hatch
        if (productionQueue.size() >= 3) {
            return;
        }

        planUnits(self, isAllIn);
    }

    private boolean canAffordHatchSaturation() {
        final int numHatcheries = gameState.getBaseData().numHatcheries();
        return ((numHatcheries + plannedHatcheries) * 7) <= gameState.getMineralWorkers();
    }

    private boolean canAffordHatch() {
        ResourceCount resourceCount = gameState.getResourceCount();
        return resourceCount.canAffordHatch(plannedHatcheries);
    }

    private boolean isNearMaxExpectedWorkers() {
        return ((expectedWorkers() * (1 + plannedHatcheries)) - numWorkers() < 0);
    }

    /**
     * Plans that are impossible to schedule can block the queue.
     * @return boolean indicating if the plan can be scheduled
     */
    private boolean canSchedulePlan(Plan plan) {
        switch (plan.getType()) {
            case UNIT:
                return canScheduleUnit(plan.getPlannedUnit());
            case BUILDING:
                return canScheduleBuilding(plan.getPlannedUnit());
            case UPGRADE:
                return canScheduleUpgrade(plan.getPlannedUpgrade());
            default:
                return false;
        }
    }

    // TODO: Consider units trying to schedule before their required tech
    private boolean canScheduleUnit(UnitType unitType) {
        TechProgression techProgression = gameState.getTechProgression();

        final boolean hasFourOrMoreDrones = gameState.numGatherers() > 3;
        final int numHatcheries = gameState.getBaseData().numHatcheries();

        switch(unitType) {
            case Zerg_Overlord:
            case Zerg_Drone:
                return numHatcheries > 0;
            case Zerg_Zergling:
                return techProgression.isPlannedSpawningPool() || techProgression.isSpawningPool();
            case Zerg_Hydralisk:
                return hasFourOrMoreDrones && (techProgression.isPlannedDen() || techProgression.isHydraliskDen());
            case Zerg_Mutalisk:
            case Zerg_Scourge:
                return hasFourOrMoreDrones && (techProgression.isPlannedSpire() || techProgression.isSpire());
            default:
                return false;
        }
    }

    private boolean canScheduleBuilding(UnitType unitType) {
        TechProgression techProgression = gameState.getTechProgression();
        final int numHatcheries = gameState.getBaseData().numHatcheries();
        switch(unitType) {
            case Zerg_Hatchery:
            case Zerg_Extractor:
            case Zerg_Creep_Colony:
                return true;
            case Zerg_Spawning_Pool:
                return numHatcheries > 0;
            case Zerg_Hydralisk_Den:
                return techProgression.isSpawningPool();
            case Zerg_Lair:
                return numHatcheries > 0 && techProgression.isSpawningPool();
            case Zerg_Spire:
                return techProgression.isLair();
            default:
                return false;
        }
    }

    private boolean canScheduleUpgrade(UpgradeType upgradeType) {
        TechProgression techProgression = gameState.getTechProgression();
        switch(upgradeType) {
            case Metabolic_Boost:
                return techProgression.isSpawningPool();
            case Muscular_Augments:
            case Grooved_Spines:
                return techProgression.isHydraliskDen();
                default:
                return false;
        }
    }

    private void schedulePlannedItems() {
        if (productionQueue.size() == 0) {
            return;
        }

        Player self = game.self();

        // Loop through items until we exhaust queue or we break because we can't consume top item
        // Call method to attempt to build that type, if we can't build return false and break the loop

        HashSet<Plan> scheduledPlans = gameState.getPlansScheduled();

        List<Plan> requeuePlans = new ArrayList<>();
        boolean skipSchedule = false;
        for (int i = 0; i < productionQueue.size(); i++) {
            if (skipSchedule) {
                break;
            }

            boolean canSchedule = false;
            // If we can't plan, we'll put it back on the queue
            final Plan plan = productionQueue.poll();
            if (plan == null) {
                continue;
            }

            // Don't block the queue if the plan cannot be executed
            if (!canSchedulePlan(plan)) {
                continue;
            }

            PlanType planType = plan.getType();

            if (skipSchedule) {
                requeuePlans.add(plan);
                continue;
            }

            switch (planType) {
                case BUILDING:
                    canSchedule = scheduleBuildingItem(plan);
                    if (!canSchedule) {
                        skipSchedule = true;
                    }
                    break;
                case UNIT:
                    canSchedule = scheduleUnitItem(plan);
                    if (!canSchedule) {
                        skipSchedule = true;
                    }
                    break;
                case UPGRADE:
                    canSchedule = scheduleUpgradeItem(self, plan);
                    if (!canSchedule) {
                        skipSchedule = true;
                    }
                    break;
            }

            if (canSchedule) {
                scheduledPlans.add(plan);
            } else {
                requeuePlans.add(plan);
                if (plan.isBlockOtherPlans()) {
                    break;
                }
            }
        }

        // Requeue
        for (Plan plan : requeuePlans) {
            productionQueue.add(plan);
        }
    }

    // TODO: Refactor this into WorkerManager or a Buildingmanager (TechManager)?
    // These PlannedItems will not work through state machine in same way as Unit and Buildings
    // This is a bit of a HACK until properly maintained
    private void buildUpgrades() {
        HashSet<Plan> scheduledPlans = gameState.getPlansScheduled();
        if (scheduledPlans.size() == 0) {
            return;
        }

        HashSet<Unit> unitsExecutingPlan = new HashSet<>();
        List<Map.Entry<Unit, Plan>> scheduledUpgradeAssignments = gameState.getAssignedPlannedItems().entrySet()
                .stream()
                .filter(assignment -> assignment.getValue().getType() == PlanType.UPGRADE)
                .collect(Collectors.toList());

        for (Map.Entry<Unit, Plan> entry: scheduledUpgradeAssignments) {
            final Unit unit = entry.getKey();
            final Plan plan = entry.getValue();
            if (buildUpgrade(unit, plan)) {
                unitsExecutingPlan.add(unit);
                scheduledPlans.remove(plan);
                plan.setState(PlanState.BUILDING); // TODO: This is awkward
                gameState.getPlansBuilding().add(plan);
            }
        }

        // Remove executing plans from gameState.getAssignedPlannedItems()
        for (Iterator<Unit> it = unitsExecutingPlan.iterator(); it.hasNext(); ) {
            Unit u = it.next();
            gameState.getAssignedPlannedItems().remove(u);
        }
    }
    // Track planned items that are morphing
    // BUILD -> MORPH
    // Buildings and units
    // TODO: Move to info package
    private void plannedItemToMorphing(Plan plan) {
        final UnitType unitType = plan.getPlannedUnit();
        ResourceCount resourceCount = gameState.getResourceCount();
        resourceCount.unreserveUnit(unitType);

        if (unitType == UnitType.Zerg_Drone) {
            plannedWorkers -= 1;
        }

        if (unitType.isBuilding()) {
            scheduledBuildings -= 1;
        }

        TechProgression techProgression = this.gameState.getTechProgression();

        switch(unitType) {
            case Zerg_Hydralisk_Den:
                techProgression.setHydraliskDen(true);
                techProgression.setPlannedDen(false);
                break;
            case Zerg_Spawning_Pool:
                techProgression.setSpawningPool(true);
                techProgression.setPlannedSpawningPool(false);
                break;
            case Zerg_Lair:
                techProgression.setLair(true);
                techProgression.setPlannedLair(false);
                break;
            case Zerg_Spire:
                techProgression.setSpire(true);
                techProgression.setPlannedSpire(false);
                break;
        }

        gameState.getPlansBuilding().remove(plan);
        plan.setState(PlanState.MORPHING);
        gameState.getPlansMorphing().add(plan);
    }

    // TODO: Handle in BaseManager (ManagedUnits that are buildings. ManagedBuilding?)
    private boolean buildUpgrade(Unit unit, Plan plan) {
        final UpgradeType upgradeType = plan.getPlannedUpgrade();
        if (game.canUpgrade(upgradeType, unit)) {
            unit.upgrade(upgradeType);
        }

        ResourceCount resourceCount = gameState.getResourceCount();

        if (unit.isUpgrading()) {
            resourceCount.unreserveUpgrade(upgradeType);
            return true;
        }
        return false;
    }

    // PLANNED -> SCHEDULED
    // Allow one building to be scheduled if resources aren't available.
    private boolean scheduleBuildingItem(Plan plan) {
        // Can we afford this unit?
        UnitType building = plan.getPlannedUnit();
        ResourceCount resourceCount = gameState.getResourceCount();
        int predictedReadyFrame = gameState.frameCanAffordUnit(building, currentFrame);
        if (scheduledBuildings > 0 && resourceCount.canAffordUnit(building)) {
            return false;
        }

        // TODO: Assign building location from building location planner
        if (plan.getBuildPosition() == null) {
            plan.setBuildPosition(game.getBuildLocation(building, gameState.getBaseData().mainBasePosition(), 128, true));
        }

        scheduledBuildings += 1;
        resourceCount.reserveUnit(building);
        plan.setPredictedReadyFrame(predictedReadyFrame);
        plan.setState(PlanState.SCHEDULE);
        return true;
    }

    private boolean scheduleUnitItem(Plan plan) {
        UnitType unit = plan.getPlannedUnit();
        ResourceCount resourceCount = gameState.getResourceCount();
        if (resourceCount.canAffordUnit(unit)) {
            return false;
        }

        if (!resourceCount.canScheduleLarva(gameState.numLarva())) {
            return false;
        }

        resourceCount.reserveUnit(unit);
        plan.setState(PlanState.SCHEDULE);
        return true;
    }

    private boolean scheduleUpgradeItem(Player self, Plan plan) {
        final UpgradeType upgrade = plan.getPlannedUpgrade();
        ResourceCount resourceCount = gameState.getResourceCount();

        if (resourceCount.canAffordUpgrade(upgrade)) {
            return false;
        }

        for (Unit unit : self.getUnits()) {
            UnitType unitType = unit.getType();

            if (unitType == upgrade.whatUpgrades() && !gameState.getAssignedPlannedItems().containsKey(unit)) {
                gameState.getAssignedPlannedItems().put(unit, plan);
                plan.setState(PlanState.SCHEDULE);
                resourceCount.reserveUpgrade(upgrade);
                return true;
            }
        }

        return false;
    }

    public void onUnitComplete(Unit unit) {
        assignUnit(unit);
    }

    // TODO: Set curPriority PER type
    // Only allow items of the curPriority to attempt assignment
    private void assignUnit(Unit unit) {
        Player self = game.self();
        if (unit.getPlayer() != self) {
            return;
        }



        UnitType unitType = unit.getType();
        // TODO: Move to a building manager or base manager
        if (unitType == UnitType.Zerg_Extractor) {
            gameState.getGeyserAssignments().put(unit, new HashSet<>());
        }

        if (unitType == UnitType.Zerg_Overlord) {
            gameState.setPlannedSupply(Math.max(0, gameState.getPlannedSupply() - unitType.supplyProvided()));
        }

        if (unitType == UnitType.Zerg_Hatchery) {
            // Account for macro hatch
            BaseData baseData = gameState.getBaseData();
            if (baseData.isBaseTilePosition(unit.getTilePosition())) {
                gameState.claimBase(unit);
            } else {
                gameState.addMacroHatchery(unit);
            }

            plannedHatcheries -= 1;
            // TODO: How are we getting here?
            if (plannedHatcheries < 0) {
                plannedHatcheries = 0;
            }
        }
    }

    // TODO: Should there be special logic here for handling the drones?
    // Need to handle cancel case (building about to die, extractor trick, etc.)
    public void onUnitMorph(Unit unit) {
        HashMap<Unit, Plan> assignedPlannedItems = gameState.getAssignedPlannedItems();
        if (assignedPlannedItems.containsKey(unit)) {
            Plan plan = gameState.getAssignedPlannedItems().get(unit);
            plannedItemToMorphing(plan);
        }

        clearAssignments(unit);
    }

    public void onUnitRenegade(Unit unit) {
        Player self = game.self();
        if (unit.getPlayer() != self) {
            return;
        }

        final UnitType unitType = unit.getType();

        if (unitType == UnitType.Zerg_Extractor) {
            ResourceCount resourceCount = gameState.getResourceCount();
            resourceCount.unreserveUnit(unitType);
            clearAssignments(unit);
            // BUG: It seems that the unit passed here is the extractor, drone was destroyed
            //plannedItemToComplete(gameState.getAssignedPlannedItems().get(unit));
            //gameState.getAssignedPlannedItems().remove(unit);
        }
    }

    public void onUnitDestroy(Unit unit) {
        Player self = game.self();
        if (unit.getPlayer() != self) {
            return;
        }

        updateTechOnDestroy(unit);

        clearAssignments(unit);
    }

    private void updateTechOnDestroy(Unit unit) {
        TechProgression techProgression = this.gameState.getTechProgression();
        switch (unit.getType()) {
            case Zerg_Spawning_Pool:
                techProgression.setSpawningPool(false);
            case Zerg_Hydralisk_Den:
                techProgression.setHydraliskDen(false);
        }
    }

    /**
     * Remove a unit from all data stores
     *
     * @param unit unit to remove
     */
    // TODO: COMPLETE vs Requeue logic
    private void clearAssignments(Unit unit) {
        // Requeue PlannedItems
        // Put item back onto the queue with greater importance
        if (gameState.getAssignedPlannedItems().containsKey(unit)) {
            Plan plan = gameState.getAssignedPlannedItems().get(unit);
            switch(plan.getState()) {
                case SCHEDULE:
                    gameState.cancelPlan(unit, plan);
                    break;
                default:
                    gameState.completePlan(unit, plan);
                    break;
            }
        }
    }
}
