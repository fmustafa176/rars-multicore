package rars.simulator;

import rars.Globals;
import rars.ProgramStatement;
import rars.riscv.BasicInstruction;
import rars.riscv.hardware.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Core Simulator: orchestrates N CPU cores executing on shared memory.
 * 
 * Uses round-robin scheduling: each core gets one instruction (or one cycle
 * in pipelined mode) per turn. This makes interleaving visible and deterministic.
 * 
 * All cores share the same Memory instance (text and data segments).
 * Each core has its own register file state (managed via CoreState context-switching).
 * Core ID is passed to each core in register a0 (x10).
 */
public class MultiCoreSimulator {

    private int numCores;
    private CoreState[] cores;
    private SharedMemory sharedMemory;
    private ExecutionMode mode;
    private int currentCycle;
    private boolean allHalted;

    // Per-core pipeline engines (used in PIPELINED mode)
    private PipelineEngine[] pipelines;

    // Per-core single-cycle engines
    private SingleCycleEngine[] singleCycleEngines;

    // Per-core multi-cycle engines
    private MultiCycleEngine[] multiCycleEngines;

    // Snapshot history
    private List<MultiCoreCycleSnapshot> history;

    /**
     * Snapshot of all cores at one cycle.
     */
    public static class MultiCoreCycleSnapshot {
        public final int cycleNumber;
        public final CycleState[] coreStates;
        public final PipelineEngine.PipelineSnapshot[] pipelineSnapshots; // null if not pipelined
        public final List<MemoryAccessRecord> memoryAccesses;
        public final List<RaceCondition> newRaces;
        public final String[] coreStatuses;

        public MultiCoreCycleSnapshot(int cycle, CycleState[] states,
                                       PipelineEngine.PipelineSnapshot[] pipeSnaps,
                                       List<MemoryAccessRecord> memAccesses,
                                       List<RaceCondition> races, String[] statuses) {
            this.cycleNumber = cycle;
            this.coreStates = states;
            this.pipelineSnapshots = pipeSnaps;
            this.memoryAccesses = memAccesses;
            this.newRaces = races;
            this.coreStatuses = statuses;
        }
    }

    public MultiCoreSimulator() {
        this.history = new ArrayList<>();
    }

    /**
     * Initialize the multi-core simulator.
     * 
     * @param numCores Number of cores (1, 2, or 4)
     * @param mode     Execution visualization mode
     * @param startPC  Starting program counter for all cores
     */
    public void initialize(int numCores, ExecutionMode mode, int startPC) {
        this.numCores = numCores;
        this.mode = mode;
        this.currentCycle = 0;
        this.allHalted = false;
        this.history.clear();

        // Create shared memory wrapper
        this.sharedMemory = new SharedMemory(Globals.memory);

        // Create core states
        this.cores = new CoreState[numCores];
        for (int i = 0; i < numCores; i++) {
            cores[i] = new CoreState(i);
            cores[i].reset(startPC);
        }

        // Create per-core engines based on mode
        switch (mode) {
            case SINGLE_CYCLE:
                singleCycleEngines = new SingleCycleEngine[numCores];
                for (int i = 0; i < numCores; i++) {
                    singleCycleEngines[i] = new SingleCycleEngine();
                }
                pipelines = null;
                multiCycleEngines = null;
                break;
            case MULTI_CYCLE:
                multiCycleEngines = new MultiCycleEngine[numCores];
                for (int i = 0; i < numCores; i++) {
                    multiCycleEngines[i] = new MultiCycleEngine();
                }
                pipelines = null;
                singleCycleEngines = null;
                break;
            case PIPELINED:
                pipelines = new PipelineEngine[numCores];
                for (int i = 0; i < numCores; i++) {
                    pipelines[i] = new PipelineEngine();
                }
                singleCycleEngines = null;
                multiCycleEngines = null;
                break;
        }
    }

    /**
     * Advance all cores by one cycle (round-robin).
     * Returns a snapshot of all cores, or null if all are halted.
     */
    public MultiCoreCycleSnapshot advanceOneCycle() {
        if (allHalted) return null;

        currentCycle++;
        sharedMemory.setCurrentCycle(currentCycle);

        CycleState[] coreStates = new CycleState[numCores];
        PipelineEngine.PipelineSnapshot[] pipeSnapshots = null;
        if (mode == ExecutionMode.PIPELINED) {
            pipeSnapshots = new PipelineEngine.PipelineSnapshot[numCores];
        }
        String[] statuses = new String[numCores];

        int raceCountBefore = sharedMemory.getDetectedRaces().size();

        // Execute one cycle for each core
        for (int i = 0; i < numCores; i++) {
            if (cores[i].isHalted()) {
                coreStates[i] = makeHaltedState(i);
                statuses[i] = "HALTED";
                continue;
            }

            // Context switch: swap this core's state into the static registers
            cores[i].swapIn();
            cores[i].setStatusMessage("RUNNING");

            switch (mode) {
                case SINGLE_CYCLE:
                    coreStates[i] = executeSingleCycle(i);
                    break;
                case MULTI_CYCLE:
                    coreStates[i] = executeMultiCycle(i);
                    break;
                case PIPELINED:
                    pipeSnapshots[i] = executePipelined(i);
                    if (pipeSnapshots[i] != null) {
                        coreStates[i] = pipeSnapshots[i].exState; // Use EX stage as representative
                    } else {
                        coreStates[i] = makeHaltedState(i);
                    }
                    break;
            }

            // Context switch: swap state back out
            cores[i].swapOut();
            cores[i].incrementCycles();
            statuses[i] = cores[i].getStatusMessage();

            if (coreStates[i] != null) {
                coreStates[i].setCoreId(i);
                
                // Log standard memory accesses to SharedMemory for race detection
                try {
                    if (coreStates[i].isMemRead() && !coreStates[i].getInstructionText().contains("lr.w")) {
                        sharedMemory.recordedRead(i, (int) coreStates[i].getMemAddress(), 4, 
                            coreStates[i].getInstructionText(), coreStates[i].getPc());
                    } else if (coreStates[i].isMemWrite() && !coreStates[i].getInstructionText().contains("sc.w")) {
                        sharedMemory.recordedWrite(i, (int) coreStates[i].getMemAddress(), (int) coreStates[i].getMemWriteData(), 4, 
                            coreStates[i].getInstructionText(), coreStates[i].getPc());
                    }
                } catch (Exception e) {
                    // Ignore memory errors during logging
                }
            }
        }

        // Check for new race conditions
        List<RaceCondition> newRaces = sharedMemory.getNewRacesSince(raceCountBefore);

        // Get memory accesses for this cycle
        List<MemoryAccessRecord> cycleAccesses = sharedMemory.getAccessesForCycle(currentCycle);

        // Check if all cores are halted
        allHalted = true;
        for (CoreState core : cores) {
            if (!core.isHalted()) {
                allHalted = false;
                break;
            }
        }

        MultiCoreCycleSnapshot snapshot = new MultiCoreCycleSnapshot(
                currentCycle, coreStates, pipeSnapshots, cycleAccesses, newRaces, statuses);
        history.add(snapshot);

        return snapshot;
    }

    private CycleState executeSingleCycle(int coreIndex) {
        SingleCycleEngine engine = singleCycleEngines[coreIndex];
        CycleState state = engine.executeOneInstruction();

        if (state == null || engine.isDone()) {
            cores[coreIndex].setHalted(true);
            return state != null ? state : makeHaltedState(coreIndex);
        }

        cores[coreIndex].incrementInstructionsRetired();
        return state;
    }

    private CycleState executeMultiCycle(int coreIndex) {
        MultiCycleEngine engine = multiCycleEngines[coreIndex];
        CycleState state = engine.advanceOneCycle();

        if (state == null || engine.isDone()) {
            cores[coreIndex].setHalted(true);
            return state != null ? state : makeHaltedState(coreIndex);
        }

        if (state.getCurrentStage() == CycleState.Stage.WRITEBACK
                || state.getCurrentStage() == CycleState.Stage.EXECUTE) {
            // Instruction completed
            cores[coreIndex].incrementInstructionsRetired();
        }

        return state;
    }

    private PipelineEngine.PipelineSnapshot executePipelined(int coreIndex) {
        PipelineEngine engine = pipelines[coreIndex];
        PipelineEngine.PipelineSnapshot snapshot = engine.advanceOneCycle();

        if (snapshot == null || engine.isDone()) {
            cores[coreIndex].setHalted(true);
        }

        return snapshot;
    }

    private CycleState makeHaltedState(int coreIndex) {
        CycleState state = new CycleState();
        state.setCycleNumber(currentCycle);
        state.setCoreId(coreIndex);
        state.setInstructionText("(halted)");
        return state;
    }

    // ==================== Public API ====================

    public int getNumCores() { return numCores; }
    public CoreState[] getCores() { return cores; }
    public CoreState getCore(int i) { return cores[i]; }
    public SharedMemory getSharedMemory() { return sharedMemory; }
    public int getCurrentCycle() { return currentCycle; }
    public boolean isAllHalted() { return allHalted; }
    public ExecutionMode getMode() { return mode; }
    public List<MultiCoreCycleSnapshot> getHistory() { return history; }

    public PipelineEngine getPipelineEngine(int coreIndex) {
        return pipelines != null ? pipelines[coreIndex] : null;
    }

    /**
     * Get aggregate CPI across all cores.
     */
    public double getAverageCPI() {
        int totalCycles = 0;
        int totalInstructions = 0;
        for (CoreState core : cores) {
            totalCycles += core.getCyclesExecuted();
            totalInstructions += core.getInstructionsRetired();
        }
        return totalInstructions > 0 ? (double) totalCycles / totalInstructions : 0;
    }

    /**
     * Get total instructions per cycle (IPC) across all cores.
     */
    public double getTotalIPC() {
        double cpi = getAverageCPI();
        return cpi > 0 ? numCores / cpi : 0;
    }

    public void reset() {
        currentCycle = 0;
        allHalted = false;
        history.clear();
        if (sharedMemory != null) sharedMemory.resetTracking();
    }
}
