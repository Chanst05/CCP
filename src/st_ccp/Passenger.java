package st_ccp;

public class Passenger implements Runnable {

    private final int passengerId;
    private final int planeId;
    private final boolean boarding; //true - boarding, false - disembarking

    public Passenger(int passengerId, int planeId, boolean boarding) {
        this.passengerId = passengerId;
        this.planeId = planeId;
        this.boarding = boarding;
    }

    @Override
    public void run() {
        String actor = "Passenger-" + passengerId;
        String action = boarding ? "Boarding" : "Disembarking";

        Log.info(actor, action + " Plane-" + planeId + " now.");
        try {
            Thread.sleep(80); // small scaled time
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
