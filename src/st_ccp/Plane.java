package st_ccp;
import java.util.Random;

public class Plane implements Runnable {

    private final int planeId;
    private final boolean emergency;
    private final double serviceFactor;
    private final Airport airport;
    private final ATC atc;


    private final Random rand = new Random();

    public Plane(int planeId, boolean emergency, double serviceFactor, Airport airport, ATC atc) {
        this.planeId = planeId;
        this.emergency = emergency;
        this.serviceFactor = serviceFactor;
        this.airport = airport;
        this.atc = atc;
    }


    @Override
    public void run() {
        String actor = "Plane-" + planeId;

        try {
            Log.info(actor, "Arrived in airspace. " + (emergency ? "EMERGENCY (fuel shortage)!" : "Normal flight."));
            Log.info(actor, "Requesting permission to land...");

            //Ask ATC for permission + assigned gate
            LandingRequest req = atc.requestLanding(planeId, emergency);
            Gate gate = req.assignedGate;

            Log.info(actor, "Landing permission GRANTED. Assigned " + gate + ". WaitingTime=" + req.waitingTimeMs() + "ms");
            airport.getStats().recordPlaneServed(req.waitingTimeMs());
            
            
            // max 3 planes on grounds (including runway)
            // after permission granted then enter the ground
            airport.getGroundSlots().acquire();
            Log.info(actor, "Entered airport grounds (ground slot acquired).");

            //land on runway
            synchronized (airport.getRunwayLock()) {
                Log.info(actor, "Landing on runway...");
                sleepMs(200, 400);
                Log.info(actor, "Landed. Vacating runway...");
            }

            //coast to gate + dock
            Log.info(actor, "Coasting to " + gate + "...");
            sleepMs(150, 300);
            Log.info(actor, "Docked at " + gate + ".");
            
            
            Log.info(actor, "Starting concurrent gate operations...");

            int passengersToDisembark = 4 + rand.nextInt(4);
            int passengersToEmbark = 4 + rand.nextInt(4);            
            airport.getStats().addPassengersBoarded(passengersToEmbark);

            //passenger disembark threads
            Thread[] disembarkers = new Thread[passengersToDisembark];
            for (int i = 0; i < passengersToDisembark; i++) {
                int pid = planeId * 100 + i; // unique id
                disembarkers[i] = new Thread(new Passenger(pid, planeId, false),
                        "Thread-Passenger-" + pid);
                disembarkers[i].start();
            }

            //cleaning/supplies thread
            Thread cleaningThread = new Thread(() -> {
                try {
                    Log.info("CleaningCrew", "Cleaning + supplies refill started for Plane-" + planeId);
                    scaledSleep(350);
                    Log.info("CleaningCrew", "Cleaning + supplies refill completed for Plane-" + planeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Thread-Cleaning-" + planeId);
            cleaningThread.start();

            //refuel thread (must use the single fuel truck)
            Thread refuelThread = new Thread(() -> {
                try {
                    airport.getFuelTruck().refuel(planeId);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }, "Thread-Refuel-" + planeId);
            refuelThread.start();

            // wait for disembark to finish before embarking
            for (Thread t : disembarkers) t.join();

            //passenger embark threads
            Thread[] embarkers = new Thread[passengersToEmbark];
            for (int i = 0; i < passengersToEmbark; i++) {
                int pid = planeId * 1000 + i; // different range for clarity
                embarkers[i] = new Thread(new Passenger(pid, planeId, true),
                        "Thread-Passenger-" + pid);
                embarkers[i].start();
            }

            // wait for embark + cleaning + refuel finish
            for (Thread t : embarkers) t.join();
            cleaningThread.join();
            refuelThread.join();

            Log.info(actor, "All gate operations completed.");
            scaledSleep(250);


            //undock + coast to runway
            Log.info(actor, "Undocking from " + gate + "...");
            sleepMs(100, 200);
            Log.info(actor, "Coasting to runway for takeoff...");
            sleepMs(150, 300);

            //takeoff runway
            synchronized (airport.getRunwayLock()) {
                Log.info(actor, "Taking off...");
                sleepMs(200, 400);
                Log.info(actor, "Airborne. Runway vacated.");
            }

            //release gate + notify ATC that 1 gate is free
            airport.releaseGate(gate);
            atc.notifyGateFreed();
            Log.info(actor, gate + " released.");

            //leave airport grounds
            airport.getGroundSlots().release();
            Log.info(actor, "Left airport grounds (ground slot released).");

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            Log.info(actor, "Interrupted. Exiting.");
        } catch (Exception e) {
            Log.info(actor, "ERROR: " + e.getMessage());
            e.printStackTrace();
        }
    }

    private void sleepMs(int min, int max) throws InterruptedException {
        int duration = min + rand.nextInt(max - min + 1);
        Thread.sleep(duration);
    }
    
    private void scaledSleep(long baseMs) throws InterruptedException {
        Thread.sleep((long) (baseMs * serviceFactor));
    }

}