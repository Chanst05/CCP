# CT074-3-2 Concurrent Programming — Individual Assignment Report

## Asia Pacific Airport Simulation (STCCP)

**Module:** Concurrent Programming (CT074-3-2)
**Level:** 2

---

## 1. System Overview

### 1.1 What the Program Does

This program simulates an airport where 6 planes arrive, land, do ground operations, and depart — all running at the same time using threads. The challenge is that planes have to share limited resources (1 runway, 3 gates, 1 fuel truck) without conflicts.

The program uses 10 Java classes split across sub-packages:

| Package | Class | Role |
|---|---|---|
| `stccp.main` | `AirportSimulation` | Entry point — creates resources, starts planes, prints results |
| `stccp.main.atc` | `ATC` | Air Traffic Controller monitor — manages landing, gates, takeoff |
| `stccp.main.vehicles` | `Plane` | Each plane is a thread going through the full airport lifecycle |
| `stccp.main.vehicles` | `RefuellingTruck` | The single fuel truck, shared between all planes |
| `stccp.main.airport` | `Runway` | Tracks whether the runway is in use |
| `stccp.main.airport` | `Gate` | Tracks whether a gate is occupied and by which plane |
| `stccp.main.airport` | `AirportGrounds` | Tracks how many planes are on the ground (max 3) |
| `stccp.main.people` | `PassengerGroup` | Creates individual passenger threads for boarding/disembarking |
| `stccp.main.stats` | `Statistics` | Thread-safe data collection for the final report |
| `stccp.main.utils` | `SimLogger` | Centralized thread-safe logging with timestamps |

### 1.2 How a Plane Moves Through the Airport

Each plane thread follows this sequence:

1. **Request landing** — calls `atc.requestLanding(this)`, waits if runway busy or ground full
2. **Land** — holds the runway for 3 seconds
3. **Free runway** — calls `atc.notifyRunwayFreeAfterLanding()` so others can use it
4. **Coast to gate** — 1.5 seconds taxiing
5. **Dock** — 0.5 seconds
6. **Gate activities (concurrent)** — three things at once:
   - Passengers disembark (individual threads)
   - Cleaning & resupply (separate thread, 3–5 seconds)
   - Refuelling (separate thread, waits for truck if busy, 5.5 seconds)
7. **Board new passengers** — new passenger threads created
8. **Undock** — 0.5 seconds
9. **Coast to runway** — 1.5 seconds
10. **Request takeoff** — calls `atc.requestTakeoff(this)`, waits if runway busy
11. **Take off** — holds runway for 3 seconds, then calls `atc.notifyDeparture()`

### 1.3 Assumptions

**Arrival Timing:** Planes arrive at random intervals (0–2 seconds apart), staggered in the main method's loop using `Thread.sleep(rand.nextInt(MAX_ARRIVAL_DELAY))`.

**Passenger Count:** Each plane carries 1–50 passengers, randomly generated with `rand.nextInt(50) + 1`. A new random count is generated for boarding after the first group disembarks.

**Emergency:** Plane-6 is hardcoded as the emergency aircraft (`isEmergency = (i == 5)`). By the time it arrives, the ground is congested enough to demonstrate priority handling.

**Coasting Time:** A 1.5-second delay simulates taxiing between the runway and gates.

---

## 2. Concurrency Design

### 2.1 The ATC Monitor

The `ATC` class is the most important part of the concurrency design. It is **not a thread** — it is a shared monitor object. All its methods are `synchronized`, meaning only one plane thread can be inside the ATC at a time.

When a plane calls `atc.requestLanding(this)`, the **plane's own thread** enters the synchronized method and executes the code. The ATC never acts on behalf of a plane — the plane acts for itself through the ATC's methods.

```java
public synchronized Gate requestLanding(Plane plane) {
    // plane logs its own request
    SimLogger.log(plane.getPlaneName(), "Requesting Landing.");

    // Emergency goes to front of queue
    if (plane.isEmergency()) {
        landingQueue.add(0, plane);
        emergencyWaiting = true;
        SimLogger.log("ATC", "EMERGENCY landing request from " + plane.getPlaneName() + "! Prioritising.");
    } else {
        landingQueue.add(plane);
    }

    // Wait until all conditions are met
    while (runway.isOccupied() || grounds.isFull()
            || landingQueue.get(0) != plane
            || (emergencyWaiting && !plane.isEmergency())
            || getAvailableGate() == null) {
        try {
            wait();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // Grant landing
    if (plane.isEmergency())
        emergencyWaiting = false;
    landingQueue.remove(plane);
    runway.occupy();
    grounds.planeArrived();

    Gate gate = getAvailableGate();
    gate.occupy(plane);

    SimLogger.log("ATC", "Landing Permission granted for " + plane.getPlaneName() + ".");
    SimLogger.log("ATC", "Gate-" + gate.getGateNumber() + " assigned for " + plane.getPlaneName() + ".");

    return gate;
}
```

The `while` loop is critical — it checks **five conditions** before allowing landing:

| Condition | Why |
|---|---|
| `runway.isOccupied()` | Only one plane can use the runway at a time |
| `grounds.isFull()` | Max 3 planes on the ground |
| `landingQueue.get(0) != plane` | Only the plane at the front of the queue may land |
| `emergencyWaiting && !plane.isEmergency()` | Emergency plane gets priority over all others |
| `getAvailableGate() == null` | Must have a free gate to dock at |

If any condition is true, `wait()` suspends the plane's thread and releases the ATC lock so other planes can enter. When another plane departs and calls `notifyAll()`, all waiting planes wake up and re-check their conditions.

### 2.2 Why `wait()` Uses a `while` Loop, Not `if`

The `while` loop guards against two problems:

1. **Spurious wake-ups:** Java allows `wait()` to return without `notifyAll()` being called. A `while` loop re-checks the condition.
2. **Stolen conditions:** When `notifyAll()` wakes multiple threads, the first one to reacquire the lock might take the slot. The second thread must re-check and go back to waiting.

### 2.3 Thread Ownership — No Thread Acts for Another

The output format makes thread ownership visible. Every log line shows both the **thread name** (who is running) and the **actor** (what component is acting):

```
[18:50:14.286] [Thread-Plane-1    ] Plane-1: Requesting Landing.
[18:50:14.289] [Thread-Plane-1    ] ATC: Landing Permission granted for Plane-1.
```

Both lines run on `Thread-Plane-1`. The second line says `ATC:` because the ATC component made the decision, but it was **Plane-1's thread** executing the ATC's synchronized method. The ATC has no thread of its own — it is just a shared object with synchronized methods.

This is handled by `SimLogger.log()`:

```java
public static synchronized void log(String actor, String message) {
    String timestamp = LocalTime.now().format(TIME_FORMAT);
    String threadName = Thread.currentThread().getName();
    System.out.printf("[%s] [%-18s] %s: %s%n", timestamp, threadName, actor, message);
}
```

The `static synchronized` ensures no two threads print at the same time, preventing garbled output.

### 2.4 Shared Resource Protection

| Resource | Protection Method | Why |
|---|---|---|
| **Runway** | ATC's `synchronized` methods manage occupy/vacate | Only one plane uses runway at a time |
| **Gates** | ATC's `synchronized` methods manage occupy/vacate | Each gate holds one plane |
| **Ground count** | ATC's `synchronized` methods call `AirportGrounds` | Max 3 planes on ground |
| **Fuel truck** | `synchronized refuel()` method | Only one plane refuels at a time |
| **Statistics data** | `synchronized` record methods | Multiple planes record data concurrently |
| **Console output** | `static synchronized` in SimLogger | Prevents garbled output |

### 2.5 Deadlock Prevention

The system avoids deadlock through:

**Same resource ordering:** Every plane acquires resources in the same order: ATC lock → runway → gate → fuel truck. Since no plane holds two locks at once and all follow the same sequence, circular wait is impossible.

**No nested locking:** A plane calls `atc.requestLanding()` (acquires ATC lock), which returns a gate. The ATC lock is released when the method returns. The plane then proceeds to land (no lock needed — runway was already marked occupied inside ATC). At no point does a thread hold two monitors simultaneously.

**`notifyAll()` not `notify()`:** Every notification wakes all waiting threads. This prevents a situation where the one notified thread cannot proceed while another waiting thread could have.

---

## 3. Key Design Decisions

### 3.1 ATC as Monitor vs ATC as Thread

The ATC was designed as a **monitor** (shared object), not a separate thread. If the ATC were a thread processing a request queue, the ATC thread would be acting on behalf of planes — violating the assignment's "no thread acts for another" rule.

With the monitor approach, each plane calls `atc.requestLanding(this)` and the plane's own thread does all the work. The plane thread checks conditions, waits if needed, and grants itself permission — all within the ATC's synchronized method.

### 3.2 Landing Queue for Fairness and Emergency Priority

An `ArrayList<Plane>` is used as a landing queue. Normal planes are added to the end with `add()`. Emergency planes are inserted at index 0 with `add(0, plane)`:

```java
if (plane.isEmergency()) {
    landingQueue.add(0, plane);
    emergencyWaiting = true;
} else {
    landingQueue.add(plane);
}
```

The `while` loop condition `landingQueue.get(0) != plane` ensures only the plane at the front of the queue can land. Combined with the `emergencyWaiting` flag, non-emergency planes are blocked until the emergency plane has been served.

`PriorityBlockingQueue` from `java.util.concurrent` was avoided as it is a restricted library.

### 3.3 Concurrent Gate Activities (Fork/Join)

When a plane docks at a gate, three operations happen simultaneously:

```java
private void performGateActivities() {
    SimLogger.log(getPlaneName(), "Starting concurrent gate activities.");

    PassengerGroup disembarkGroup = new PassengerGroup(planeId, passengerCount, false);
    disembarkGroup.start();

    Thread suppliesThread = new Thread(() -> {
        SimLogger.log(getPlaneName(), "Refilling supplies and cleaning aircraft.");
        sleep(3000 + rand.nextInt(2000));
        SimLogger.log(getPlaneName(), "Supplies refilled and aircraft cleaned.");
    }, "Supplies-P" + planeId);
    suppliesThread.start();

    Thread refuelThread = new Thread(() -> {
        truck.refuel(getPlaneName());
    }, "Refuel-P" + planeId);
    refuelThread.start();

    try {
        disembarkGroup.join();
        suppliesThread.join();
        refuelThread.join();
    } catch (InterruptedException e) {
        e.printStackTrace();
    }

    SimLogger.log(getPlaneName(), "All gate activities complete.");
}
```

All three threads are started **before** any `join()` is called. This is important — if `join()` were called immediately after each `start()`, the operations would run sequentially instead of concurrently. The three `join()` calls at the end ensure the plane waits for all three to finish before proceeding to boarding.

### 3.4 Individual Passenger Threads

Each passenger runs as their own thread inside `PassengerGroup`. The group creates, starts, and joins all passengers:

```java
Thread[] passengers = new Thread[passengerCount];
for (int i = 0; i < passengerCount; i++) {
    final int id = i + 1;
    passengers[i] = new Thread(() -> {
        SimLogger.log(actor, "Passenger " + id + " is " + action + " " + planeName + ".");
        try {
            Thread.sleep(100 + rand.nextInt(200));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }, "Passenger-" + id + "-" + planeName);
    passengers[i].start();
}

for (Thread p : passengers) {
    try { p.join(); } catch (InterruptedException e) { e.printStackTrace(); }
}
```

Each passenger thread is explicitly named (e.g., `Passenger-3-Plane-1`) so the output clearly shows individual passengers acting on their own threads.

### 3.5 Centralized Logging with SimLogger

All threads print through `SimLogger.log()`, which is `static synchronized`. This guarantees:

1. **No garbled lines** — only one thread writes at a time
2. **Timestamps** — every line shows `[HH:mm:ss.SSS]` for timing analysis
3. **Thread identity** — `Thread.currentThread().getName()` is included automatically
4. **Actor identity** — the `actor` parameter shows which component (Plane, ATC, Passengers) is acting

### 3.6 Concurrency Primitives Used

| Primitive | Where Used | Purpose |
|---|---|---|
| `synchronized` (method) | ATC, RefuellingTruck, Statistics, SimLogger | Mutual exclusion |
| `wait()` | ATC.requestLanding(), ATC.requestTakeoff() | Suspend thread until condition met |
| `notifyAll()` | ATC.notifyRunwayFreeAfterLanding(), ATC.notifyDeparture() | Wake all waiting threads |
| `Thread.start()` | Plane, PassengerGroup, gate activity threads | Fork new threads |
| `Thread.join()` | Plane gate activities, passengers, AirportSimulation | Wait for thread completion |
| `Thread.sleep()` | All simulation steps | Simulate time passing |

No classes from `java.util.concurrent` were used. The only concurrency mechanisms are `synchronized`, `wait()`, `notifyAll()`, and direct `Thread` management — all from `java.lang`.

---

## 4. Requirements Checklist

### Basic Requirements

| Requirement | Status | Implementation |
|---|---|---|
| 1 runway for landing and departing | ✅ | `Runway` object managed by ATC |
| Max 3 planes on ground | ✅ | `AirportGrounds` with `maxCapacity = 3` |
| 3 gates | ✅ | `Gate[3]` array |
| 6 planes, each a thread | ✅ | `Plane extends Thread`, 6 created |
| Full lifecycle | ✅ | All steps in `Plane.run()` |
| Time simulation with `Thread.sleep()` | ✅ | Every step has a sleep |
| No waiting area (planes wait in air) | ✅ | `wait()` blocks in `requestLanding()` |
| Concurrent ground ops | ✅ | 3 threads fork/join in `performGateActivities()` |
| Gate check at end | ✅ | Sanity check in `Statistics.printReport()` |
| Statistics (min/max/avg, served, passengers) | ✅ | All computed and printed |
| Arrival delay 0–2s, max 50 passengers | ✅ | `rand.nextInt(2000)`, `rand.nextInt(50) + 1` |
| Simulation under 60 seconds | ✅ | Consistently ~43 seconds |

### Additional Requirements

| Requirement | Status | Implementation |
|---|---|---|
| 1 refuelling truck (exclusive) | ✅ | `RefuellingTruck` with `synchronized refuel()` |
| Emergency landing priority | ✅ | Plane-6 inserted at front of queue with `add(0, plane)` |

### Restrictions

| Restriction | Status |
|---|---|
| No `java.util.concurrent.*` | ✅ Zero matches |
| No `parallelStream` | ✅ Zero matches |
| No `Timer` | ✅ Zero matches |
| No `@Async` | ✅ Zero matches |
| No thread acts for another | ✅ ATC is monitor, plane thread executes |
