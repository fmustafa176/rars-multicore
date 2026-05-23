package rars.simulator;

/**
 * Represents a detected race condition between two memory accesses
 * from different cores to the same address.
 */
public class RaceCondition {

    public enum RaceType {
        WRITE_WRITE("W→W"),
        READ_WRITE("R→W"),
        WRITE_READ("W→R");

        private final String label;

        RaceType(String label) {
            this.label = label;
        }

        public String getLabel() { return label; }
    }

    private final MemoryAccessRecord access1;
    private final MemoryAccessRecord access2;
    private final RaceType type;
    private final int address;
    private final boolean resolved;

    public RaceCondition(MemoryAccessRecord access1, MemoryAccessRecord access2, boolean resolved) {
        this.access1 = access1;
        this.access2 = access2;
        this.address = access1.getAddress();
        this.resolved = resolved;

        // Determine race type based on access types
        if (access1.getType() == MemoryAccessRecord.AccessType.WRITE
                && access2.getType() == MemoryAccessRecord.AccessType.WRITE) {
            this.type = RaceType.WRITE_WRITE;
        } else if (access1.getType() == MemoryAccessRecord.AccessType.WRITE) {
            this.type = RaceType.WRITE_READ;
        } else {
            this.type = RaceType.READ_WRITE;
        }
    }

    public MemoryAccessRecord getAccess1() { return access1; }
    public MemoryAccessRecord getAccess2() { return access2; }
    public RaceType getType() { return type; }
    public int getAddress() { return address; }
    public boolean isResolved() { return resolved; }

    /**
     * The cycle at which the race was detected (the later of the two accesses).
     */
    public int getDetectionCycle() {
        return Math.max(access1.getCycle(), access2.getCycle());
    }

    @Override
    public String toString() {
        return String.format("RACE [%s] @0x%08x cycle %d: %s vs %s %s",
                type.getLabel(), address, getDetectionCycle(),
                access1, access2,
                resolved ? "✅ resolved" : "❌ unresolved");
    }
}
