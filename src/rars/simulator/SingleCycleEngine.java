package rars.simulator;

import rars.Globals;
import rars.ProgramStatement;
import rars.riscv.BasicInstruction;
import rars.riscv.hardware.*;
import rars.util.Binary;

/**
 * Single-Cycle execution engine.
 * 
 * Wraps a single instruction execution and produces a CycleState capturing
 * all datapath signals. In single-cycle mode, every instruction completes
 * in exactly one clock cycle (CPI = 1).
 */
public class SingleCycleEngine {

    private int cycleCount;
    private boolean done;

    public SingleCycleEngine() {
        this.cycleCount = 0;
        this.done = false;
    }

    /**
     * Execute one instruction and return the complete CycleState.
     * Returns null if there are no more instructions to execute.
     */
    public CycleState executeOneInstruction() {
        if (done) return null;

        CycleState state = new CycleState();
        state.setCycleNumber(++cycleCount);

        int pc = RegisterFile.getProgramCounter();
        state.setPc(pc);

        // ===== FETCH =====
        ProgramStatement statement;
        try {
            statement = Globals.memory.getStatement(pc);
        } catch (AddressErrorException e) {
            done = true;
            return makeErrorState("Instruction fetch error at PC=" + Binary.intToHexString(pc));
        }

        if (statement == null) {
            done = true;
            state.setCurrentStage(CycleState.Stage.FETCH);
            state.setInstructionText("(end of program)");
            return state;
        }

        state.setInstruction(statement);
        state.deriveRegisterFields(statement);
        state.deriveControlSignals(statement);
        state.setCurrentStage(CycleState.Stage.FETCH);

        // ===== DECODE =====
        // Read register values before execution
        int rs1 = state.getRs1();
        int rs2 = state.getRs2();
        state.setRs1Val(RegisterFile.getValueLong(rs1));
        state.setRs2Val(RegisterFile.getValueLong(rs2));

        // Extract immediate based on opcode
        extractImmediate(state, statement);

        // Determine ALU operation
        deriveAluOperation(state, statement);

        // ===== EXECUTE (simulate the instruction) =====
        // Capture pre-execution register state for rd
        int rd = state.getRd();
        long rdBefore = RegisterFile.getValueLong(rd);
        int pcBefore = RegisterFile.getProgramCounter();

        // Advance PC
        RegisterFile.incrementPC();

        // Actually execute the instruction
        try {
            BasicInstruction instruction = (BasicInstruction) statement.getInstruction();
            if (instruction != null) {
                instruction.simulate(statement);
            }
        } catch (Exception e) {
            // Instruction may throw for ecall (exit), ebreak, etc.
            if (e instanceof rars.ExitingException) {
                done = true;
            }
        }

        // ===== Post-execution: capture results =====
        long rdAfter = RegisterFile.getValueLong(rd);
        int pcAfter = RegisterFile.getProgramCounter();

        // ALU result — approximate: if rd was written, the ALU result is the new rd value
        // (not perfectly accurate for all instructions but good for visualization)
        state.setAluInput1(state.getRs1Val());
        if (state.getControlSignals().getOrDefault("ALUSrc", 0) == 1) {
            state.setAluInput2(state.getImmediate());
        } else {
            state.setAluInput2(state.getRs2Val());
        }
        state.setAluResult(rdAfter);
        state.setRdVal(rdAfter);

        // Memory signals
        if (state.getControlSignals().getOrDefault("MemRead", 0) == 1) {
            state.setMemRead(true);
            state.setMemAddress(state.getRs1Val() + state.getImmediate());
            state.setMemReadData(rdAfter); // load result goes to rd
        }
        if (state.getControlSignals().getOrDefault("MemWrite", 0) == 1) {
            state.setMemWrite(true);
            state.setMemAddress(state.getRs1Val() + state.getImmediate());
            state.setMemWriteData(state.getRs2Val());
        }

        // Branch detection
        if (state.getControlSignals().getOrDefault("Branch", 0) == 1
                || state.getControlSignals().getOrDefault("Jump", 0) == 1) {
            state.setIsBranch(true);
            state.setBranchTaken(pcAfter != pc + 4);
            state.setBranchTarget(pcAfter);
        }

        // In single-cycle mode, the stage shown is always the full cycle
        state.setCurrentStage(null); // all stages in one cycle

        return state;
    }

    /**
     * Extract the immediate value from the instruction binary encoding.
     */
    private void extractImmediate(CycleState state, ProgramStatement stmt) {
        int binary = stmt.getBinaryStatement();
        int opcode = binary & 0x7F;

        long imm = 0;
        switch (opcode) {
            case 0x13: case 0x03: case 0x67: // I-type
            case 0x1B:
                imm = binary >> 20; // sign-extended
                break;
            case 0x23: // S-type
                imm = ((binary >> 25) << 5) | ((binary >> 7) & 0x1F);
                if ((imm & 0x800) != 0) imm |= 0xFFFFF000; // sign extend
                break;
            case 0x63: // B-type
                imm = ((binary >> 31) << 12) | (((binary >> 7) & 1) << 11)
                        | (((binary >> 25) & 0x3F) << 5) | (((binary >> 8) & 0xF) << 1);
                if ((imm & 0x1000) != 0) imm |= 0xFFFFE000;
                break;
            case 0x37: case 0x17: // U-type
                imm = binary & 0xFFFFF000;
                break;
            case 0x6F: // J-type
                imm = ((binary >> 31) << 20) | (((binary >> 12) & 0xFF) << 12)
                        | (((binary >> 20) & 1) << 11) | (((binary >> 21) & 0x3FF) << 1);
                if ((imm & 0x100000) != 0) imm |= 0xFFE00000;
                break;
        }
        state.setImmediate(imm);
    }

    /**
     * Determine the ALU operation name from the instruction.
     */
    private void deriveAluOperation(CycleState state, ProgramStatement stmt) {
        if (stmt.getInstruction() == null) return;
        String name = stmt.getInstruction().getName().toUpperCase();

        // Map instruction name to ALU operation
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
        } else if (name.startsWith("MUL")) {
            state.setAluOperation("MUL");
        } else if (name.startsWith("DIV")) {
            state.setAluOperation("DIV");
        } else if (name.startsWith("REM")) {
            state.setAluOperation("REM");
        } else if (name.startsWith("BEQ") || name.startsWith("BNE") || name.startsWith("BLT")
                || name.startsWith("BGE")) {
            state.setAluOperation("SUB"); // branches use subtraction for comparison
        } else if (name.startsWith("LUI")) {
            state.setAluOperation("PASS"); // pass-through upper immediate
        } else {
            state.setAluOperation(name);
        }
    }

    private CycleState makeErrorState(String message) {
        CycleState state = new CycleState();
        state.setCycleNumber(cycleCount);
        state.setInstructionText(message);
        state.setCurrentStage(CycleState.Stage.FETCH);
        return state;
    }

    public boolean isDone() { return done; }
    public int getCycleCount() { return cycleCount; }

    public void reset() {
        cycleCount = 0;
        done = false;
    }
}
