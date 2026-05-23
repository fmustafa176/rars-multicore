package rars.simulator;

import rars.Globals;
import rars.riscv.hardware.*;

import java.util.ArrayList;
import java.util.List;

/**
 * Holds the complete architectural state of one CPU core.
 * 
 * Since RARS uses static singletons for RegisterFile, FloatingPointRegisterFile,
 * and ControlAndStatusRegisterFile, we use a context-switch pattern: before a core
 * executes, its state is swapped INTO the static fields; after execution, the
 * state is swapped back OUT. This avoids modifying 150+ instruction implementations.
 */
public class CoreState {

    private final int coreId;

    // Integer register values (x0-x31)
    private long[] intRegisters;
    // Program counter
    private int pc;
    // Floating point register values (f0-f31)
    private long[] fpRegisters;
    // CSR register values (indexed by position in the CSR array, not by CSR number)
    private long[] csrRegisters;

    // Execution status
    private boolean halted;
    private boolean stalled;
    private String statusMessage;

    // Memory access log for this core (for race condition detection)
    private List<MemoryAccessRecord> memoryLog;

    // Per-core cycle counter
    private int cyclesExecuted;
    private int instructionsRetired;

    /**
     * Create a new core state with the given ID.
     * Registers are initialized from the current static RegisterFile state.
     */
    public CoreState(int coreId) {
        this.coreId = coreId;
        this.halted = false;
        this.stalled = false;
        this.statusMessage = "IDLE";
        this.memoryLog = new ArrayList<>();
        this.cyclesExecuted = 0;
        this.instructionsRetired = 0;

        // Snapshot integer registers
        Register[] regs = RegisterFile.getRegisters();
        this.intRegisters = new long[regs.length];
        for (int i = 0; i < regs.length; i++) {
            this.intRegisters[i] = regs[i].getValueNoNotify();
        }
        this.pc = RegisterFile.getProgramCounter();

        // Snapshot FP registers
        Register[] fpRegs = FloatingPointRegisterFile.getRegisters();
        this.fpRegisters = new long[fpRegs.length];
        for (int i = 0; i < fpRegs.length; i++) {
            this.fpRegisters[i] = fpRegs[i].getValueNoNotify();
        }

        // Snapshot CSR registers
        Register[] csrRegs = ControlAndStatusRegisterFile.getRegisters();
        this.csrRegisters = new long[csrRegs.length];
        for (int i = 0; i < csrRegs.length; i++) {
            this.csrRegisters[i] = csrRegs[i].getValueNoNotify();
        }
    }

    /**
     * Swap this core's saved state INTO the static register files.
     * Call this before executing instructions for this core.
     */
    public void swapIn() {
        // Integer registers
        Register[] regs = RegisterFile.getRegisters();
        for (int i = 0; i < regs.length; i++) {
            regs[i].setValueBackdoor(intRegisters[i]);
        }
        RegisterFile.initializeProgramCounter(pc);

        // FP registers
        Register[] fpRegs = FloatingPointRegisterFile.getRegisters();
        for (int i = 0; i < fpRegs.length; i++) {
            fpRegs[i].setValueBackdoor(fpRegisters[i]);
        }

        // CSR registers
        Register[] csrRegs = ControlAndStatusRegisterFile.getRegisters();
        for (int i = 0; i < csrRegs.length; i++) {
            csrRegs[i].setValueBackdoor(csrRegisters[i]);
        }
    }

    /**
     * Swap the current static register file state back OUT into this core's saved state.
     * Call this after executing instructions for this core.
     */
    public void swapOut() {
        // Integer registers
        Register[] regs = RegisterFile.getRegisters();
        for (int i = 0; i < regs.length; i++) {
            intRegisters[i] = regs[i].getValueNoNotify();
        }
        pc = RegisterFile.getProgramCounter();

        // FP registers
        Register[] fpRegs = FloatingPointRegisterFile.getRegisters();
        for (int i = 0; i < fpRegs.length; i++) {
            fpRegisters[i] = fpRegs[i].getValueNoNotify();
        }

        // CSR registers
        Register[] csrRegs = ControlAndStatusRegisterFile.getRegisters();
        for (int i = 0; i < csrRegs.length; i++) {
            csrRegisters[i] = csrRegs[i].getValueNoNotify();
        }
    }

    /**
     * Reset this core to a fresh state for the given start PC.
     * The core ID is placed in register a0 (x10) so the program can
     * identify which core it is running on.
     */
    public void reset(int startPC) {
        // Reset integer registers to default
        Register[] regs = RegisterFile.getRegisters();
        for (int i = 0; i < intRegisters.length; i++) {
            intRegisters[i] = regs[i].getResetValue();
        }
        // Set core ID in a0 (register x10)
        intRegisters[10] = coreId;
        this.pc = startPC;

        // Reset FP registers
        Register[] fpRegs = FloatingPointRegisterFile.getRegisters();
        for (int i = 0; i < fpRegisters.length; i++) {
            fpRegisters[i] = fpRegs[i].getResetValue();
        }

        // Reset CSR registers
        Register[] csrRegs = ControlAndStatusRegisterFile.getRegisters();
        for (int i = 0; i < csrRegisters.length; i++) {
            csrRegisters[i] = csrRegs[i].getResetValue();
        }

        this.halted = false;
        this.stalled = false;
        this.statusMessage = "READY";
        this.memoryLog.clear();
        this.cyclesExecuted = 0;
        this.instructionsRetired = 0;
    }

    /**
     * Record a memory access made by this core.
     */
    public void recordMemoryAccess(int address, int value, int cycle,
                                    MemoryAccessRecord.AccessType type,
                                    String instructionName) {
        memoryLog.add(new MemoryAccessRecord(coreId, address, value, cycle,
                type, instructionName, pc));
    }

    // ==================== Getters & Setters ====================

    public int getCoreId() { return coreId; }
    public int getPc() { return pc; }
    public void setPc(int pc) { this.pc = pc; }

    public boolean isHalted() { return halted; }
    public void setHalted(boolean halted) {
        this.halted = halted;
        if (halted) this.statusMessage = "HALTED";
    }

    public boolean isStalled() { return stalled; }
    public void setStalled(boolean stalled) {
        this.stalled = stalled;
        if (stalled) this.statusMessage = "STALLED";
        else if (!halted) this.statusMessage = "RUNNING";
    }

    public String getStatusMessage() { return statusMessage; }
    public void setStatusMessage(String msg) { this.statusMessage = msg; }

    public List<MemoryAccessRecord> getMemoryLog() { return memoryLog; }

    public int getCyclesExecuted() { return cyclesExecuted; }
    public void incrementCycles() { this.cyclesExecuted++; }

    public int getInstructionsRetired() { return instructionsRetired; }
    public void incrementInstructionsRetired() { this.instructionsRetired++; }

    /**
     * Get the value of integer register n from the saved state (without swapping in).
     */
    public long getIntRegisterValue(int n) {
        if (n < 0 || n >= intRegisters.length) return 0;
        return intRegisters[n];
    }

    /**
     * Get all integer register values (snapshot copy).
     */
    public long[] getIntRegisters() {
        return intRegisters.clone();
    }

    /**
     * Get all FP register values (snapshot copy).
     */
    public long[] getFpRegisters() {
        return fpRegisters.clone();
    }

    /**
     * Get the register names from the static RegisterFile.
     */
    public static String[] getIntRegisterNames() {
        Register[] regs = RegisterFile.getRegisters();
        String[] names = new String[regs.length];
        for (int i = 0; i < regs.length; i++) {
            names[i] = regs[i].getName();
        }
        return names;
    }

    @Override
    public String toString() {
        return String.format("Core%d [%s] PC=0x%08x cycles=%d instret=%d",
                coreId, statusMessage, pc, cyclesExecuted, instructionsRetired);
    }
}
