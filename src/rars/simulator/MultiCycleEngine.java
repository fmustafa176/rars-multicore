package rars.simulator;

import rars.Globals;
import rars.ProgramStatement;
import rars.riscv.BasicInstruction;
import rars.riscv.hardware.*;
import rars.util.Binary;

import java.util.ArrayList;
import java.util.List;

/**
 * Multi-Cycle execution engine.
 * 
 * Breaks each instruction into 3-5 clock cycles depending on type:
 *   - R-type:  IF → ID → EX/WB  (3 cycles)
 *   - I-type:  IF → ID → EX/WB  (3 cycles)
 *   - Load:    IF → ID → EX → MEM → WB  (5 cycles)
 *   - Store:   IF → ID → EX → MEM  (4 cycles)
 *   - Branch:  IF → ID → EX  (3 cycles)
 *   - Jump:    IF → ID → EX/WB  (3 cycles)
 * 
 * The actual RARS instruction.simulate() is called during the EX phase
 * for most instructions, or during MEM phase for loads/stores.
 */
public class MultiCycleEngine {

    private int cycleCount;
    private boolean done;

    // Current instruction being processed across multiple cycles
    private ProgramStatement currentStatement;
    private int currentPc;
    private List<CycleState.Stage> stagesForCurrentInstruction;
    private int currentStageIndex;
    private boolean instructionExecuted; // whether simulate() has been called

    // Pre-captured state
    private CycleState baseState; // common state for the current instruction

    public MultiCycleEngine() {
        this.cycleCount = 0;
        this.done = false;
        this.currentStatement = null;
        this.stagesForCurrentInstruction = null;
        this.currentStageIndex = 0;
        this.instructionExecuted = false;
    }

    /**
     * Advance by one clock cycle. Returns a CycleState for the current stage
     * of the current instruction. Returns null when done.
     */
    public CycleState advanceOneCycle() {
        if (done) return null;

        cycleCount++;

        // If no current instruction, fetch a new one
        if (currentStatement == null) {
            if (!fetchNewInstruction()) {
                return makeEndState();
            }
        }

        // Get the current stage
        CycleState.Stage stage = stagesForCurrentInstruction.get(currentStageIndex);

        // Create the cycle state
        CycleState state = new CycleState();
        state.setCycleNumber(cycleCount);
        state.setPc(currentPc);
        state.setInstruction(currentStatement);
        state.setCurrentStage(stage);

        if (baseState != null) {
            // Copy base instruction info
            state.setRs1(baseState.getRs1());
            state.setRs2(baseState.getRs2());
            state.setRd(baseState.getRd());
            state.setInstructionText(baseState.getInstructionText());
            state.setImmediate(baseState.getImmediate());
            state.setAluOperation(baseState.getAluOperation());
            // Copy control signals
            for (var entry : baseState.getControlSignals().entrySet()) {
                state.setControlSignal(entry.getKey(), entry.getValue());
            }
        }

        // Execute stage-specific logic
        switch (stage) {
            case FETCH:
                // In FETCH, we read the instruction from memory
                state.setRs1Val(0);
                state.setRs2Val(0);
                break;

            case DECODE:
                // In DECODE, we read register values
                state.setRs1Val(RegisterFile.getValueLong(state.getRs1()));
                state.setRs2Val(RegisterFile.getValueLong(state.getRs2()));
                break;

            case EXECUTE:
                // In EX, perform ALU operation
                state.setRs1Val(RegisterFile.getValueLong(state.getRs1()));
                state.setRs2Val(RegisterFile.getValueLong(state.getRs2()));
                state.setAluInput1(state.getRs1Val());
                if (state.getControlSignals().getOrDefault("ALUSrc", 0) == 1) {
                    state.setAluInput2(state.getImmediate());
                } else {
                    state.setAluInput2(state.getRs2Val());
                }

                // For R-type, I-type (non-load), branch, and jump:
                // execute the instruction now if no MEM stage follows
                if (!instructionExecuted && !hasMemoryStage()) {
                    executeInstruction();
                    state.setAluResult(RegisterFile.getValueLong(state.getRd()));
                    state.setRdVal(RegisterFile.getValueLong(state.getRd()));
                    
                    // Check branch
                    if (state.getControlSignals().getOrDefault("Branch", 0) == 1
                            || state.getControlSignals().getOrDefault("Jump", 0) == 1) {
                        int newPc = RegisterFile.getProgramCounter();
                        state.setIsBranch(true);
                        state.setBranchTaken(newPc != currentPc + 4);
                        state.setBranchTarget(newPc);
                    }
                }
                break;

            case MEMORY:
                // In MEM, perform memory access
                if (!instructionExecuted) {
                    long rdBefore = RegisterFile.getValueLong(state.getRd());
                    executeInstruction();
                    long rdAfter = RegisterFile.getValueLong(state.getRd());

                    if (state.getControlSignals().getOrDefault("MemRead", 0) == 1) {
                        state.setMemRead(true);
                        state.setMemAddress(state.getRs1Val() + state.getImmediate());
                        state.setMemReadData(rdAfter);
                    }
                    if (state.getControlSignals().getOrDefault("MemWrite", 0) == 1) {
                        state.setMemWrite(true);
                        state.setMemAddress(state.getRs1Val() + state.getImmediate());
                        state.setMemWriteData(state.getRs2Val());
                    }
                    state.setAluResult(state.getRs1Val() + state.getImmediate());
                }
                break;

            case WRITEBACK:
                // In WB, the value is written to the register file
                state.setRdVal(RegisterFile.getValueLong(state.getRd()));
                break;
        }

        // Advance to next stage or next instruction
        currentStageIndex++;
        if (currentStageIndex >= stagesForCurrentInstruction.size()) {
            // Current instruction is complete
            currentStatement = null;
            currentStageIndex = 0;
            instructionExecuted = false;
            baseState = null;
        }

        return state;
    }

    /**
     * Fetch a new instruction and determine its stage sequence.
     */
    private boolean fetchNewInstruction() {
        currentPc = RegisterFile.getProgramCounter();
        RegisterFile.incrementPC();

        try {
            currentStatement = Globals.memory.getStatement(currentPc);
        } catch (AddressErrorException e) {
            done = true;
            return false;
        }

        if (currentStatement == null) {
            done = true;
            return false;
        }

        // Determine stages based on instruction type
        stagesForCurrentInstruction = determineStages(currentStatement);
        currentStageIndex = 0;
        instructionExecuted = false;

        // Pre-compute base state info
        baseState = new CycleState();
        baseState.setInstruction(currentStatement);
        baseState.deriveRegisterFields(currentStatement);
        baseState.deriveControlSignals(currentStatement);
        
        // Extract immediate
        extractImmediate(baseState, currentStatement);
        deriveAluOperation(baseState, currentStatement);

        return true;
    }

    /**
     * Determine the pipeline stages for the given instruction.
     */
    private List<CycleState.Stage> determineStages(ProgramStatement stmt) {
        List<CycleState.Stage> stages = new ArrayList<>();
        int opcode = stmt.getBinaryStatement() & 0x7F;

        stages.add(CycleState.Stage.FETCH);
        stages.add(CycleState.Stage.DECODE);

        switch (opcode) {
            case 0x03: // Load
                stages.add(CycleState.Stage.EXECUTE);
                stages.add(CycleState.Stage.MEMORY);
                stages.add(CycleState.Stage.WRITEBACK);
                break;
            case 0x23: // Store
                stages.add(CycleState.Stage.EXECUTE);
                stages.add(CycleState.Stage.MEMORY);
                break;
            case 0x33: case 0x3B: // R-type
            case 0x13: case 0x1B: // I-type arithmetic
            case 0x37: case 0x17: // LUI, AUIPC
            case 0x6F: case 0x67: // JAL, JALR
                stages.add(CycleState.Stage.EXECUTE);
                // EX includes WB in multi-cycle for these
                break;
            case 0x63: // Branch
                stages.add(CycleState.Stage.EXECUTE);
                break;
            case 0x73: // System
                stages.add(CycleState.Stage.EXECUTE);
                break;
            default:
                stages.add(CycleState.Stage.EXECUTE);
                break;
        }

        return stages;
    }

    private boolean hasMemoryStage() {
        return stagesForCurrentInstruction.contains(CycleState.Stage.MEMORY);
    }

    /**
     * Actually execute the instruction using RARS's existing simulate().
     */
    private void executeInstruction() {
        if (instructionExecuted) return;
        instructionExecuted = true;

        try {
            BasicInstruction instruction = (BasicInstruction) currentStatement.getInstruction();
            if (instruction != null) {
                instruction.simulate(currentStatement);
            }
        } catch (Exception e) {
            if (e instanceof rars.ExitingException) {
                done = true;
            }
        }
    }

    // Reuse the same immediate extraction and ALU operation logic
    private void extractImmediate(CycleState state, ProgramStatement stmt) {
        int binary = stmt.getBinaryStatement();
        int opcode = binary & 0x7F;
        long imm = 0;
        switch (opcode) {
            case 0x13: case 0x03: case 0x67: case 0x1B:
                imm = binary >> 20;
                break;
            case 0x23:
                imm = ((binary >> 25) << 5) | ((binary >> 7) & 0x1F);
                if ((imm & 0x800) != 0) imm |= 0xFFFFF000;
                break;
            case 0x63:
                imm = ((binary >> 31) << 12) | (((binary >> 7) & 1) << 11)
                        | (((binary >> 25) & 0x3F) << 5) | (((binary >> 8) & 0xF) << 1);
                if ((imm & 0x1000) != 0) imm |= 0xFFFFE000;
                break;
            case 0x37: case 0x17:
                imm = binary & 0xFFFFF000;
                break;
            case 0x6F:
                imm = ((binary >> 31) << 20) | (((binary >> 12) & 0xFF) << 12)
                        | (((binary >> 20) & 1) << 11) | (((binary >> 21) & 0x3FF) << 1);
                if ((imm & 0x100000) != 0) imm |= 0xFFE00000;
                break;
        }
        state.setImmediate(imm);
    }

    private void deriveAluOperation(CycleState state, ProgramStatement stmt) {
        if (stmt.getInstruction() == null) return;
        String name = stmt.getInstruction().getName().toUpperCase();
        if (name.startsWith("ADD") || name.startsWith("LW") || name.startsWith("LH")
                || name.startsWith("LB") || name.startsWith("SW") || name.startsWith("SH")
                || name.startsWith("SB") || name.startsWith("JAL") || name.startsWith("AUIPC")) {
            state.setAluOperation("ADD");
        } else if (name.startsWith("SUB")) {
            state.setAluOperation("SUB");
        } else if (name.startsWith("AND")) {
            state.setAluOperation("AND");
        } else if (name.startsWith("OR")) {
            state.setAluOperation("OR");
        } else if (name.startsWith("XOR")) {
            state.setAluOperation("XOR");
        } else if (name.startsWith("SLT")) {
            state.setAluOperation("SLT");
        } else if (name.startsWith("SLL")) {
            state.setAluOperation("SLL");
        } else if (name.startsWith("SRL")) {
            state.setAluOperation("SRL");
        } else if (name.startsWith("SRA")) {
            state.setAluOperation("SRA");
        } else if (name.startsWith("BEQ") || name.startsWith("BNE") || name.startsWith("BLT")
                || name.startsWith("BGE")) {
            state.setAluOperation("SUB");
        } else if (name.startsWith("LUI")) {
            state.setAluOperation("PASS");
        } else {
            state.setAluOperation(name);
        }
    }

    private CycleState makeEndState() {
        CycleState state = new CycleState();
        state.setCycleNumber(cycleCount);
        state.setInstructionText("(end of program)");
        state.setCurrentStage(CycleState.Stage.FETCH);
        return state;
    }

    public boolean isDone() { return done; }
    public int getCycleCount() { return cycleCount; }

    public void reset() {
        cycleCount = 0;
        done = false;
        currentStatement = null;
        stagesForCurrentInstruction = null;
        currentStageIndex = 0;
        instructionExecuted = false;
        baseState = null;
    }
}
