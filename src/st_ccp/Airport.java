package st_ccp;

import java.util.concurrent.Semaphore;

public class Airport {
    // 1 runway (only 1 plane can land/takeoff at a time)
    private final Object runwayLock = new Object();

    // max 3 planes on airport grounds
    private final Semaphore groundSlots = new Semaphore(3, true); // fair reduces starvation

    private final Gate[] gates;

    // protects gate allocation state (occupied/currentPlaneId)
    private final Object gateAllocLock = new Object();
    
    private final FuelTruck fuelTruck = new FuelTruck();
    
    private final Stats stats = new Stats();

    public Airport(int numberOfGates) {
        if (numberOfGates <= 0) {
            throw new IllegalArgumentException("numberOfGates must be > 0");
        }
        this.gates = new Gate[numberOfGates];
        for (int i = 0; i < numberOfGates; i++) {
            gates[i] = new Gate(i + 1); // Gate IDs 1..n
        }
    }


    public Object getRunwayLock() {
        return runwayLock;
    }


    public Semaphore getGroundSlots() {
        return groundSlots;
    }

    public Gate tryAssignGate(int planeId) {
        synchronized (gateAllocLock) {
            for (Gate g : gates) {
                if (!g.occupied) {
                    g.occupied = true;
                    g.currentPlaneId = planeId;
                    return g;
                }
            }
            return null; // no free gate
        }
    }

    public void releaseGate(Gate gate) {
        if (gate == null) return;

        synchronized (gateAllocLock) {
            gate.occupied = false;
            gate.currentPlaneId = -1;
        }
    }

    public boolean areAllGatesEmpty() {
        synchronized (gateAllocLock) {
            for (Gate g : gates) {
                if (g.occupied) return false;
            }
            return true;
        }
    }

    public String gatesStatus() {
        synchronized (gateAllocLock) {
            StringBuilder sb = new StringBuilder();
            for (Gate g : gates) {
                sb.append(g)
                  .append(g.occupied ? "(OCCUPIED by Plane-" + g.currentPlaneId + ")" : "(FREE)")
                  .append(" ");
            }
            return sb.toString().trim();
        }
    }
    
    public FuelTruck getFuelTruck() {
        return fuelTruck;
    }

    public Stats getStats() {
        return stats;
    }
}
