package rars.simulator;

import rars.ProgramStatement;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Represents a pipeline latch (register) between two pipeline stages.
 * Holds instruction data as it flows through the pipeline.
 */
public class PipelineLatch {

    private ProgramStatement instruction;
    private int pc;
    private boolean isBubble;   // NOP inserted due to stall
    private boolean isValid;    // contains a valid instruction (not flushed)

    // Decoded fields (populated after ID stage)
    private int rs1, rs2, rd;
    private long rs1Val, rs2Val;
    private long immediate;
    private String aluOperation;
    private String instructionText;

    // Control signals
    private Map<String, Integer> controlSignals;

    // ALU results (populated after EX stage)
    private long aluResult;
    private int branchTarget;
    private boolean branchTaken;

    // Memory data (populated after MEM stage)
    private long memReadData;

    // Instruction opcode for quick type checking
    private int opcode;

    public PipelineLatch() {
        this.controlSignals = new LinkedHashMap<>();
        this.isBubble = true;
        this.isValid = false;
        this.instructionText = "---";
        this.aluOperation = "";
    }

    /**
     * Create a latch loaded with an instruction from the FETCH stage.
     */
    public static PipelineLatch fromFetch(ProgramStatement stmt, int pc) {
        PipelineLatch latch = new PipelineLatch();
        latch.instruction = stmt;
        latch.pc = pc;
        latch.isBubble = false;
        latch.isValid = true;

        if (stmt != null && stmt.getInstruction() != null) {
            int binary = stmt.getBinaryStatement();
            latch.opcode = binary & 0x7F;
            latch.rd  = (binary >> 7)  & 0x1F;
            latch.rs1 = (binary >> 15) & 0x1F;
            latch.rs2 = (binary >> 20) & 0x1F;
            latch.instructionText = stmt.getPrintableBasicAssemblyStatement();
        }

        return latch;
    }

    /**
     * Create a bubble (NOP) latch.
     */
    public static PipelineLatch bubble() {
        PipelineLatch latch = new PipelineLatch();
        latch.isBubble = true;
        latch.isValid = false;
        latch.instructionText = "BUBBLE";
        return latch;
    }

    /**
     * Mark this latch as flushed (invalid).
     */
    public void flush() {
        this.isValid = false;
        this.isBubble = false;
        this.instructionText = "FLUSH";
    }

    /**
     * Check if this instruction writes to a register.
     */
    public boolean writesRegister() {
        return isValid && !isBubble
                && controlSignals.getOrDefault("RegWrite", 0) == 1
                && rd != 0;
    }

    /**
     * Check if this is a load instruction.
     */
    public boolean isLoad() {
        return isValid && !isBubble && opcode == 0x03;
    }

    /**
     * Check if this is a branch instruction.
     */
    public boolean isBranch() {
        return isValid && !isBubble && (opcode == 0x63);
    }

    /**
     * Check if this is a jump instruction.
     */
    public boolean isJump() {
        return isValid && !isBubble && (opcode == 0x6F || opcode == 0x67);
    }

    /**
     * Convert to a CycleState for the given cycle and stage.
     */
    public CycleState toCycleState(int cycleNumber, CycleState.Stage stage) {
        CycleState state = new CycleState();
        state.setCycleNumber(cycleNumber);
        state.setPc(pc);
        state.setInstruction(instruction);
        state.setCurrentStage(stage);
        state.setInstructionText(instructionText);

        if (isBubble) {
            state.setBubble(true);
            state.setCurrentStage(CycleState.Stage.BUBBLE);
        } else if (!isValid) {
            state.setFlush(true);
            state.setCurrentStage(CycleState.Stage.FLUSH);
        }

        state.setRs1(rs1);
        state.setRs2(rs2);
        state.setRd(rd);
        state.setRs1Val(rs1Val);
        state.setRs2Val(rs2Val);
        state.setImmediate(immediate);
        state.setAluOperation(aluOperation);
        state.setAluResult(aluResult);
        state.setMemReadData(memReadData);

        for (var entry : controlSignals.entrySet()) {
            state.setControlSignal(entry.getKey(), entry.getValue());
        }

        if (branchTaken) {
            state.setIsBranch(true);
            state.setBranchTaken(true);
            state.setBranchTarget(branchTarget);
        }

        return state;
    }

    // ==================== Getters & Setters ====================

    public ProgramStatement getInstruction() { return instruction; }
    public int getPc() { return pc; }
    public boolean isBubble() { return isBubble; }
    public boolean isValid() { return isValid; }

    public int getRs1() { return rs1; }
    public int getRs2() { return rs2; }
    public int getRd() { return rd; }

    public long getRs1Val() { return rs1Val; }
    public void setRs1Val(long v) { this.rs1Val = v; }
    public long getRs2Val() { return rs2Val; }
    public void setRs2Val(long v) { this.rs2Val = v; }

    public long getImmediate() { return immediate; }
    public void setImmediate(long imm) { this.immediate = imm; }

    public String getAluOperation() { return aluOperation; }
    public void setAluOperation(String op) { this.aluOperation = op; }

    public String getInstructionText() { return instructionText; }

    public Map<String, Integer> getControlSignals() { return controlSignals; }
    public void setControlSignal(String name, int value) { controlSignals.put(name, value); }

    public long getAluResult() { return aluResult; }
    public void setAluResult(long r) { this.aluResult = r; }

    public int getBranchTarget() { return branchTarget; }
    public void setBranchTarget(int t) { this.branchTarget = t; }

    public boolean isBranchTaken() { return branchTaken; }
    public void setBranchTaken(boolean b) { this.branchTaken = b; }

    public long getMemReadData() { return memReadData; }
    public void setMemReadData(long d) { this.memReadData = d; }

    public int getOpcode() { return opcode; }

    @Override
    public String toString() {
        if (isBubble) return "BUBBLE";
        if (!isValid) return "FLUSH";
        return String.format("0x%08x: %s", pc, instructionText);
    }
}
