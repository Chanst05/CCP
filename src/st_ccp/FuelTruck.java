package st_ccp;
import java.util.concurrent.Semaphore;

public class FuelTruck {
    private final Semaphore truck = new Semaphore(1, true);

    public void refuel(int planeId) throws InterruptedException {
        String actor = "FuelTruck";

        truck.acquire();
        try {
            Log.info(actor, "Refuelling started for Plane-" + planeId);
            Thread.sleep(300); // scaled time
            Log.info(actor, "Refuelling completed for Plane-" + planeId);
        } finally {
            truck.release();
        }
    }
}
