package st_ccp;
import java.util.LinkedList;

public class ATC implements Runnable{
    private final Airport airport;

    // 1 lock for all ATC coordination
    private final Object atcLock = new Object();

    // Manual queues no PriorityBlockingQueue
    private final LinkedList<LandingRequest> normalQueue = new LinkedList<>();
    private final LinkedList<LandingRequest> emergencyQueue = new LinkedList<>();

    private volatile boolean running = true;

    public ATC(Airport airport) {
        this.airport = airport;
    }

   //call a Plane thread.
    public LandingRequest requestLanding(int planeId, boolean emergency) throws InterruptedException {
        LandingRequest req = new LandingRequest(planeId, emergency);

        synchronized (atcLock) {
            if (emergency) emergencyQueue.addLast(req);
            else normalQueue.addLast(req);

            // Wake ATC thread if it is waiting for requests
            atcLock.notifyAll();

            // waits until ATC grants permission
            while (!req.granted) {
                atcLock.wait();
            }

            return req;
        }
    }

    //call plane when it leaves gate
    public void notifyGateFreed() {
        synchronized (atcLock) {
            atcLock.notifyAll();
        }
    }

    //stop ATC loop (call after all planes finished)
    public void shutdown() {
        running = false;
        synchronized (atcLock) {
            atcLock.notifyAll();
        }
    }

    @Override
    public void run() {
        while (running) {
            LandingRequest req;

            synchronized (atcLock) {
                // wait until at least one landing request or shutdown
                while (running && emergencyQueue.isEmpty() && normalQueue.isEmpty()) {
                    try {
                        atcLock.wait();
                    } catch (InterruptedException ignored) {}
                }
                if (!running) break;

                //emergency requests get priority
                req = !emergencyQueue.isEmpty() ? emergencyQueue.pollFirst() : normalQueue.pollFirst();

                // attempt to allocate a gate
                Gate gate = airport.tryAssignGate(req.planeId);

                // Check airport ground capacity
                boolean groundHasSpace = airport.getGroundSlots().availablePermits() > 0;

                if (gate != null && groundHasSpace) {
                    // Grant permission
                    req.assignedGate = gate;
                    req.granted = true;
                    req.grantedTimeMs = System.currentTimeMillis();

                    // notify all waiting planes
                    atcLock.notifyAll();
                } else {
                    // rollback gate allocation if we took one
                    if (gate != null) {
                        airport.releaseGate(gate);
                    }
                    
                    //print "wait" message per request
                    if (!req.alreadyToldToWait) {
                        String type = req.emergency ? "EMERGENCY" : "Normal";
                        String reason = (gate == null) ? "No free gate" : "Airport full (ground slots)";
                        Log.info("ATC", "Landing DENIED for Plane-" + req.planeId +
                                " (" + type + "). Reason: " + reason + ". Plane must WAIT.");
                        req.alreadyToldToWait = true;
                    }

                    // put request back at front so doesn't lose its place
                    if (req.emergency) emergencyQueue.addFirst(req);
                    else normalQueue.addFirst(req);

                    // avoid busy-waiting
                    try {
                        atcLock.wait(25);
                    } catch (InterruptedException ignored) {}
                }
            }
        }
    }
}
