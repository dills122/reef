package com.reef.arena.controlplane.arena

class InMemoryArenaRunAdmissionStore : ArenaRunAdmissionStore {
    private val windows = linkedMapOf<String, ArenaAdmissionWindow>()
    private val decisions = linkedMapOf<String, ArenaRunEligibilityDecision>()
    private val rosters = linkedMapOf<String, ArenaRosterSnapshot>()
    private val removals = linkedMapOf<String, ArenaRosterRemoval>()

    override fun createWindow(window: ArenaAdmissionWindow): ArenaAdmissionWindow {
        val existing = windows[window.windowId]
        require(existing == null || existing == window) { "admission window is immutable: ${window.windowId}" }
        windows[window.windowId] = window
        return window
    }

    override fun window(windowId: String): ArenaAdmissionWindow? = windows[windowId]

    override fun recordDecision(decision: ArenaRunEligibilityDecision): ArenaRunEligibilityDecision {
        require(windows.containsKey(decision.windowId)) { "unknown admission window: ${decision.windowId}" }
        val existing = decisions[decision.evaluationId]
        require(existing == null || existing == decision) { "eligibility evaluation is immutable: ${decision.evaluationId}" }
        decisions[decision.evaluationId] = decision
        return decision
    }

    override fun decisions(windowId: String): List<ArenaRunEligibilityDecision> =
        decisions.values.filter { it.windowId == windowId }.sortedWith(
            compareBy<ArenaRunEligibilityDecision> { it.evaluatedAt }
                .thenBy { it.botId }
                .thenBy { it.versionId }
                .thenBy { it.evaluationId }
        )

    override fun lockRoster(snapshot: ArenaRosterSnapshot): ArenaRosterSnapshot {
        require(windows.containsKey(snapshot.windowId)) { "unknown admission window: ${snapshot.windowId}" }
        val existing = rosters[snapshot.windowId]
        require(existing == null || existing == snapshot) { "roster snapshot is immutable for window: ${snapshot.windowId}" }
        rosters[snapshot.windowId] = snapshot
        return snapshot
    }

    override fun roster(windowId: String): ArenaRosterSnapshot? = rosters[windowId]

    override fun recordRemoval(removal: ArenaRosterRemoval): ArenaRosterRemoval {
        val roster = requireNotNull(rosters[removal.windowId]) { "unknown roster for window: ${removal.windowId}" }
        require(roster.snapshotId == removal.snapshotId) { "roster snapshot does not match removal" }
        require(roster.entries.any { it.botId == removal.botId && it.versionId == removal.versionId }) {
            "roster removal target is not in the locked roster"
        }
        val sameTarget = removals.values.firstOrNull {
            it.snapshotId == removal.snapshotId && it.botId == removal.botId && it.versionId == removal.versionId
        }
        require(sameTarget == null || sameTarget == removal) { "roster entry is already removed" }
        val existing = removals[removal.removalId]
        require(existing == null || existing == removal) { "roster removal is immutable: ${removal.removalId}" }
        removals[removal.removalId] = removal
        return removal
    }

    override fun removals(windowId: String): List<ArenaRosterRemoval> = removals.values
        .filter { it.windowId == windowId }
        .sortedWith(compareBy<ArenaRosterRemoval> { it.removedAt }.thenBy { it.botId }.thenBy { it.versionId })
}
