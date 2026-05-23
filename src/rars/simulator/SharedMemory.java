package rars.simulator;

import rars.Globals;
import rars.riscv.hardware.AddressErrorException;
import rars.riscv.hardware.Memory;

import java.util.*;

/**
 * Wraps RARS Memory with multi-core-aware access tracking.
 * Provides load-reserved/store-conditional support and race condition detection.
 * 
 * In multi-core mode, all cores share the same underlying Memory instance.
 * This class intercepts accesses to log them and manage atomic reservations.
 */
public class SharedMemory {

    // The underlying RARS memory instance
    private final Memory memory;

    // Chronological log of all memory accesses across all cores
    private final List<MemoryAccessRecord> accessLog;

    // Load-reserved reservations: address -> coreId that holds the reservation
    private final Map<Integer, Integer> reservations;

    // Detected race conditions
    private final List<RaceCondition> detectedRaces;

    // Addresses currently protected by lr/sc (for race resolution detection)
    private final Set<Integer> atomicAddresses;

    // Current simulation cycle (set by the multi-core simulator)
    private int currentCycle;

    // Window size for race detection (accesses within this many cycles are checked)
    private static final int RACE_DETECTION_WINDOW = 10;

    public SharedMemory(Memory memory) {
        this.memory = memory;
        this.accessLog = new ArrayList<>();
        this.reservations = new HashMap<>();
        this.detectedRaces = new ArrayList<>();
        this.atomicAddresses = new HashSet<>();
        this.currentCycle = 0;
    }

    /**
     * Set the current cycle number (called by MultiCoreSimulator each cycle).
     */
    public void setCurrentCycle(int cycle) {
        this.currentCycle = cycle;
    }

    /**
     * Record a regular (non-atomic) memory read.
     */
    public int recordedRead(int coreId, int address, int length, String instrName, int pc)
            throws AddressErrorException {
        int value = memory.get(address, length);
        MemoryAccessRecord record = new MemoryAccessRecord(
                coreId, address, value, currentCycle,
                MemoryAccessRecord.AccessType.READ, instrName, pc);
        accessLog.add(record);
        checkForRaces(record);
        return value;
    }

    /**
     * Record a regular (non-atomic) memory write.
     */
    public int recordedWrite(int coreId, int address, int value, int length, String instrName, int pc)
            throws AddressErrorException {
        int oldValue = memory.set(address, value, length);
        MemoryAccessRecord record = new MemoryAccessRecord(
                coreId, address, value, currentCycle,
                MemoryAccessRecord.AccessType.WRITE, instrName, pc);
        accessLog.add(record);

        // A write by any core invalidates other cores' reservations on this address
        invalidateReservations(address, coreId);

        checkForRaces(record);
        return oldValue;
    }

    /**
     * Load-Reserved (LR.W): Read a word and set a reservation for the given core.
     */
    public int loadReserved(int coreId, int address, int pc) throws AddressErrorException {
        int value = memory.get(address, Memory.WORD_LENGTH_BYTES);
        
        // Set reservation
        reservations.put(address, coreId);
        atomicAddresses.add(address);

        MemoryAccessRecord record = new MemoryAccessRecord(
                coreId, address, value, currentCycle,
                MemoryAccessRecord.AccessType.READ, "lr.w", pc);
        accessLog.add(record);

        return value;
    }

    /**
     * Store-Conditional (SC.W): Write a word only if the reservation is still valid.
     * Returns 0 on success (reservation held), non-zero on failure (reservation lost).
     */
    public int storeConditional(int coreId, int address, int value, int pc) throws AddressErrorException {
        Integer reservationHolder = reservations.get(address);

        if (reservationHolder != null && reservationHolder == coreId) {
            // Reservation valid — perform the store
            memory.set(address, value, Memory.WORD_LENGTH_BYTES);
            reservations.remove(address);

            MemoryAccessRecord record = new MemoryAccessRecord(
                    coreId, address, value, currentCycle,
                    MemoryAccessRecord.AccessType.WRITE, "sc.w (success)", pc);
            accessLog.add(record);

            return 0; // success
        } else {
            // Reservation lost — store fails
            MemoryAccessRecord record = new MemoryAccessRecord(
                    coreId, address, value, currentCycle,
                    MemoryAccessRecord.AccessType.WRITE, "sc.w (failed)", pc);
            accessLog.add(record);

            return 1; // failure
        }
    }

    /**
     * Invalidate reservations held by other cores when a write occurs.
     */
    private void invalidateReservations(int address, int writerCoreId) {
        Integer holder = reservations.get(address);
        if (holder != null && holder != writerCoreId) {
            reservations.remove(address);
        }
    }

    /**
     * Check if a new access creates a race condition with recent accesses.
     * A race exists when two accesses from different cores target the same address
     * within the detection window and at least one is a write.
     */
    private void checkForRaces(MemoryAccessRecord newAccess) {
        // Only check data segment addresses (not instruction fetches)
        int addr = newAccess.getAddress();
        
        // Look backward through recent accesses
        for (int i = accessLog.size() - 2; i >= 0; i--) {
            MemoryAccessRecord prev = accessLog.get(i);

            // Stop if outside detection window
            if (currentCycle - prev.getCycle() > RACE_DETECTION_WINDOW) break;

            if (prev.conflictsWith(newAccess)) {
                // Check if this address has been accessed via atomics (resolved)
                boolean resolved = atomicAddresses.contains(addr);
                RaceCondition race = new RaceCondition(prev, newAccess, resolved);
                detectedRaces.add(race);
            }
        }
    }

    // ==================== Getters ====================

    public List<MemoryAccessRecord> getAccessLog() {
        return Collections.unmodifiableList(accessLog);
    }

    public List<RaceCondition> getDetectedRaces() {
        return Collections.unmodifiableList(detectedRaces);
    }

    /**
     * Get only the new race conditions detected since the last call.
     */
    public List<RaceCondition> getNewRacesSince(int sinceIndex) {
        if (sinceIndex >= detectedRaces.size()) return Collections.emptyList();
        return detectedRaces.subList(sinceIndex, detectedRaces.size());
    }

    /**
     * Get memory accesses for a specific cycle.
     */
    public List<MemoryAccessRecord> getAccessesForCycle(int cycle) {
        List<MemoryAccessRecord> result = new ArrayList<>();
        for (MemoryAccessRecord r : accessLog) {
            if (r.getCycle() == cycle) result.add(r);
        }
        return result;
    }

    /**
     * Get all accesses to a specific address.
     */
    public List<MemoryAccessRecord> getAccessesForAddress(int address) {
        List<MemoryAccessRecord> result = new ArrayList<>();
        for (MemoryAccessRecord r : accessLog) {
            if (r.getAddress() == address) result.add(r);
        }
        return result;
    }

    /**
     * Get summary statistics.
     */
    public String getSummary() {
        long totalRaces = detectedRaces.size();
        long resolvedRaces = detectedRaces.stream().filter(RaceCondition::isResolved).count();
        long unresolvedRaces = totalRaces - resolvedRaces;
        return String.format("Memory accesses: %d | Races: %d (resolved: %d, unresolved: %d)",
                accessLog.size(), totalRaces, resolvedRaces, unresolvedRaces);
    }

    /**
     * Get the underlying Memory for direct access (used by single-core mode).
     */
    public Memory getMemory() {
        return memory;
    }

    /**
     * Reset all tracking state (not the memory contents).
     */
    public void resetTracking() {
        accessLog.clear();
        reservations.clear();
        detectedRaces.clear();
        atomicAddresses.clear();
        currentCycle = 0;
    }

    /**
     * Full reset including memory contents.
     */
    public void reset() {
        resetTracking();
        memory.clear();
    }
}
