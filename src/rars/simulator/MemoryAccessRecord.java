package rars.simulator;

/**
 * Records a single memory access made by a core during simulation.
 * Used for race condition detection and memory access visualization.
 */
public class MemoryAccessRecord {

    public enum AccessType {
        READ, WRITE
    }

    private final int coreId;
    private final int address;
    private final int value;
    private final int cycle;
    private final AccessType type;
    private final String instructionName;
    private final int pc;

    public MemoryAccessRecord(int coreId, int address, int value, int cycle,
                               AccessType type, String instructionName, int pc) {
        this.coreId = coreId;
        this.address = address;
        this.value = value;
        this.cycle = cycle;
        this.type = type;
        this.instructionName = instructionName;
        this.pc = pc;
    }

    public int getCoreId() { return coreId; }
    public int getAddress() { return address; }
    public int getValue() { return value; }
    public int getCycle() { return cycle; }
    public AccessType getType() { return type; }
    public String getInstructionName() { return instructionName; }
    public int getPc() { return pc; }

    /**
     * Two accesses conflict if they target the same address from different cores
     * and at least one is a write.
     */
    public boolean conflictsWith(MemoryAccessRecord other) {
        if (this.coreId == other.coreId) return false;
        if (this.address != other.address) return false;
        return this.type == AccessType.WRITE || other.type == AccessType.WRITE;
    }

    @Override
    public String toString() {
        return String.format("Core%d %s %s @0x%08x = 0x%08x (cycle %d, PC=0x%08x)",
                coreId, instructionName, type, address, value, cycle, pc);
    }
}
