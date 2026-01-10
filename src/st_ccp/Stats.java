package st_ccp;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

public class Stats {

    private final List<Long> waitingTimesMs = Collections.synchronizedList(new ArrayList<>());
    private final AtomicInteger planesServed = new AtomicInteger(0);
    private final AtomicInteger passengersBoarded = new AtomicInteger(0);
    private final AtomicInteger planesGranted = new AtomicInteger(0);
    private final AtomicInteger planesDeparted = new AtomicInteger(0);

    
    public void recordPlaneServed(long waitingTimeMs) {
        waitingTimesMs.add(waitingTimeMs);
        planesServed.incrementAndGet();
    }

    public void addPassengersBoarded(int count) {
        passengersBoarded.addAndGet(count);
    }

    public int getPlanesServed() {
        return planesServed.get();
    }

    public int getPassengersBoarded() {
        return passengersBoarded.get();
    }

    public long getMinWaitingTime() {
        synchronized (waitingTimesMs) {
            return waitingTimesMs.stream().mapToLong(Long::longValue).min().orElse(0);
        }
    }

    public long getMaxWaitingTime() {
        synchronized (waitingTimesMs) {
            return waitingTimesMs.stream().mapToLong(Long::longValue).max().orElse(0);
        }
    }

    public double getAvgWaitingTime() {
        synchronized (waitingTimesMs) {
            return waitingTimesMs.stream().mapToLong(Long::longValue).average().orElse(0.0);
        }
    }
    
    public void recordPermissionGranted(long waitingTimeMs) {
        waitingTimesMs.add(waitingTimeMs);
        planesGranted.incrementAndGet();
    }

    public void recordDeparted() {
        planesDeparted.incrementAndGet();
    }

    public int getPlanesGranted() { return planesGranted.get(); }
    public int getPlanesDeparted() { return planesDeparted.get(); }

}