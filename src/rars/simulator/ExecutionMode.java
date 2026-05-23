package rars.simulator;

/**
 * Defines the CPU execution visualization modes.
 */
public enum ExecutionMode {
    SINGLE_CYCLE("Single-Cycle", "CPI = 1. All stages complete in one clock cycle."),
    MULTI_CYCLE("Multi-Cycle", "CPI = 3-5. Each stage takes one clock cycle."),
    PIPELINED("Pipelined", "5-stage pipeline with hazard detection and forwarding.");

    private final String displayName;
    private final String description;

    ExecutionMode(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }

    @Override
    public String toString() { return displayName; }
}
