package st_ccp;


public class LandingRequest {
    public final int planeId;
    public final boolean emergency;

    // Assigned by ATC when permission is granted
    Gate assignedGate = null;

    // Set by ATC to release the plane from waiting
    boolean granted = false;

    // For statistics (waiting time)
    public final long requestTimeMs;
    long grantedTimeMs = -1;
    boolean alreadyToldToWait = false;

    public LandingRequest(int planeId, boolean emergency) {
        this.planeId = planeId;
        this.emergency = emergency;
        this.requestTimeMs = System.currentTimeMillis();
    }

    long waitingTimeMs() {
        if (grantedTimeMs < 0) return -1;
        return grantedTimeMs - requestTimeMs;
    }
    
}
