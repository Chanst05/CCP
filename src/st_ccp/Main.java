package st_ccp;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

public class Main {

    public static void main(String[] args) throws InterruptedException {
        
        final int NUM_GATES = 3;
        final int NUM_PLANES = 6;
        final long MAX_SIM_MS = 60_000;

        // fixed seed
        final Random arrivalRand = new Random(42);

        Log.info("Main", "Starting Asia Pacific Airport Simulation...");

        long simStart = System.currentTimeMillis();

        // create shared airport resources
        Airport airport = new Airport(NUM_GATES);

        // 2) Create ATC + start ATC thread
        ATC atc = new ATC(airport);
        Thread atcThread = new Thread(atc, "Thread-ATC");
        atcThread.start();
        Log.info("Main", "ATC started.");

        //create and start plane threads with random arrival
        //force one emergency plane to satisfy emergency landing scenario
        int emergencyPlaneId = 6; // simple deterministic choice
        List<Thread> planeThreads = new ArrayList<>();

        for (int planeId = 1; planeId <= NUM_PLANES; planeId++) {

            // new airplane arrives every 0, 1, or 2 seconds
            int delayMs = arrivalRand.nextInt(2001);
            Thread.sleep(delayMs);

            boolean emergency = (planeId == emergencyPlaneId);

            double factor;
            if (planeId <= 2) factor = 2.0;       // keep Gate-1 & Gate-2 busy longer
            else if (planeId == 3) factor = 1.5;
            else factor = 1.0;

            Plane plane = new Plane(planeId, emergency, factor, airport, atc);
            Thread t = new Thread(plane, "Thread-Plane-" + planeId);
            planeThreads.add(t);

            Log.info("Main", "Spawning " + (emergency ? "EMERGENCY " : "") + "Plane-" + planeId
                    + " after delay " + delayMs + "ms");
            t.start();
        }

        // 4) Wait for all planes to finish
        for (Thread t : planeThreads) {
            t.join();
        }

        // 5) Stop ATC and wait for it to finish
        atc.shutdown();
        atcThread.join();

        // 6) End-of-simulation sanity checks + basic runtime check
        long simEnd = System.currentTimeMillis();
        long elapsed = simEnd - simStart;

        Log.info("ATC", "Simulation finished. Performing sanity checks...");

        boolean gatesEmpty = airport.areAllGatesEmpty();
        Log.info("ATC", "Sanity check - all gates empty: " + gatesEmpty);
        Log.info("ATC", "Final gates status: " + airport.gatesStatus());

        Log.info("Main", "Total simulation time: " + elapsed + " ms");
        if (elapsed > MAX_SIM_MS) {
            Log.info("Main", "WARNING: Simulation exceeded 60 seconds requirement!");
        } else {
            Log.info("Main", "OK: Simulation within 60 seconds requirement.");
        }

               
        Stats stats = airport.getStats();
        Log.info("ATC", "===== STATISTICS =====");
        Log.info("ATC", "Planes served: " + stats.getPlanesServed());
        Log.info("ATC", "Passengers boarded: " + stats.getPassengersBoarded());
        Log.info("ATC", "Waiting time (ms) - MIN: " + stats.getMinWaitingTime()
                + " AVG: " + String.format("%.2f", stats.getAvgWaitingTime())
                + " MAX: " + stats.getMaxWaitingTime());
        Log.info("ATC", "======================");

        Log.info("Main", "Done.");
    }
}

