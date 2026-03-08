
## Asia Pacific Airport Simulation (STCCP)
**Module:** CT074-3-2 Concurrent Programming  
**Level:** 2  
---
## Table of Contents
1. [Introduction](#1-introduction)
2. [Project Structure](#2-project-structure)
3. [System Architecture](#3-system-architecture)
4. [Class Documentation](#4-class-documentation)
   - 4.1 [AirportSimulation (Entry Point)](#41-airportsimulation--entry-point)
   - 4.2 [ATC (Air Traffic Control Monitor)](#42-atc--air-traffic-control-monitor)
   - 4.3 [Plane](#43-plane)
   - 4.4 [Runway](#44-runway)
   - 4.5 [Gate](#45-gate)
   - 4.6 [AirportGrounds](#46-airportgrounds)
   - 4.7 [RefuellingTruck](#47-refuellingtruck)
   - 4.8 [PassengerGroup](#48-passengergroup)
   - 4.9 [Statistics](#49-statistics)
   - 4.10 [SimLogger](#410-simlogger)
5. [Concurrency Design](#5-concurrency-design)
   - 5.1 [The ATC Monitor Pattern](#51-the-atc-monitor-pattern)
   - 5.2 [The `while` Loop Guard](#52-the-while-loop-guard)
   - 5.3 [Thread Ownership — No Thread Acts for Another](#53-thread-ownership--no-thread-acts-for-another)
   - 5.4 [Shared Resource Protection](#54-shared-resource-protection)
   - 5.5 [Deadlock Prevention](#55-deadlock-prevention)
6. [Thread Summary](#6-thread-summary)
7. [Key Design Decisions](#7-key-design-decisions)
8. [Simulation Configuration](#8-simulation-configuration)
9. [Sample Output Format](#9-sample-output-format)
10. [Requirements Compliance](#10-requirements-compliance)
---
## 1. Introduction
This system simulates an airport named **Asia Pacific Airport** where **6 planes** arrive, land, perform ground operations, and depart — all running concurrently using Java threads. The core challenge is that planes must share **limited resources** (1 runway, 3 gates, 1 fuel truck, max 3 planes on the ground) without conflicts, race conditions, or deadlocks.
The simulation demonstrates the following concurrent programming concepts:
- **Monitor pattern** — ATC as a shared synchronization object
- **Mutual exclusion** — `synchronized` methods on shared resources
- **Condition synchronization** — `wait()` / `notifyAll()` for coordinating threads
- **Fork/Join concurrency** — Concurrent gate activities with `Thread.start()` and `Thread.join()`
- **Priority handling** — Emergency plane gets precedence in the landing queue
The program uses **10 Java classes** split across 6 packages, all using only `java.lang` concurrency primitives (`synchronized`, `wait()`, `notifyAll()`, `Thread`). No classes from `java.util.concurrent` are used.
---
## 2. Project Structure
```
src/stccp/main/
│
├── AirportSimulation.java              Entry point (main method)
│
├── airport/
│   ├── AirportGrounds.java             Tracks planes on the ground (max 3)
│   ├── Gate.java                       Parking spot for one plane (3 total)
│   └── Runway.java                     Single shared runway
│
├── atc/
│   └── ATC.java                        Air Traffic Control monitor
│
├── people/
│   └── PassengerGroup.java             Passenger threads for boarding/disembarking
│
├── stats/
│   └── Statistics.java                 Thread-safe data collection & final report
│
├── utils/
│   └── SimLogger.java                  Thread-safe console logging
│
└── vehicles/
    ├── Plane.java                      Each plane is a thread
    └── RefuellingTruck.java            Shared fuel truck (1 at a time)
```
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
---
## 3. System Architecture
### 3.1 High-Level Flow
The simulation follows this overall flow:
1. `AirportSimulation.main()` creates all shared resources
2. 6 `Plane` threads are created and started with random staggered arrivals (0–2s apart)
3. Each plane independently goes through its full airport lifecycle
4. The main thread waits for all planes to complete via `join()`
5. Final statistics are printed
### 3.2 Plane Lifecycle
Each plane thread follows this sequential process:
| Step | Action | Duration | Shared Resource Used |
|------|--------|----------|---------------------|
| 1 | Request landing from ATC | Waits until conditions met | ATC lock, Runway, Grounds, Gate |
| 2 | Land on runway | 3 seconds | Runway (occupied) |
| 3 | Free runway after landing | Instant | Runway (released) |
| 4 | Coast to assigned gate | 1.5 seconds | — |
| 5 | Dock at gate | 0.5 seconds | — |
| 6 | Gate activities (concurrent) | Varies (see below) | RefuellingTruck |
| 7 | Board new passengers | Varies | — |
| 8 | Undock from gate | 0.5 seconds | — |
| 9 | Coast to runway | 1.5 seconds | — |
| 10 | Request takeoff from ATC | Waits until runway free | ATC lock, Runway |
| 11 | Take off | 3 seconds | Runway (occupied) |
| 12 | Notify departure | Instant | Gate (released), Runway (released), Grounds (released) |
### 3.3 Concurrent Gate Activities (Step 6)
Three operations happen **simultaneously** while a plane is docked:
| Activity | Thread | Duration |
|----------|--------|----------|
| Passengers disembark | `PassengerGroup` thread (spawns individual passenger threads) | 100–300ms per passenger |
| Cleaning & restocking supplies | Anonymous `Supplies-PX` thread | 3–5 seconds |
| Refuelling | Anonymous `Refuel-PX` thread (acquires truck lock) | 5.5 seconds |
All three threads are started **before** any `join()` is called. This ensures they run concurrently. The plane waits for all three to finish before proceeding to boarding.
### 3.4 Emergency Handling
**Plane-6** is hardcoded as the emergency aircraft. When it requests landing:
1. It is inserted at the **front** of the landing queue (`landingQueue.add(0, plane)`)
2. The `emergencyWaiting` flag is set to `true`
3. All non-emergency planes are blocked from landing (`emergencyWaiting && !plane.isEmergency()`)
4. Once Plane-6 lands, `emergencyWaiting` is set back to `false` and other planes can proceed
---
## 4. Class Documentation
### 4.1 AirportSimulation — Entry Point
**File:** `src/stccp/main/AirportSimulation.java`  
**Type:** Main class (not a thread)
**Responsibility:** Creates all shared resources, initialises and starts the 6 plane threads, waits for completion, then prints the final statistics report.
**Constants:**
| Constant | Value | Purpose |
|----------|-------|---------|
| `TOTAL_PLANES` | 6 | Number of planes in the simulation |
| `TOTAL_GATES` | 3 | Number of parking gates |
| `MAX_ON_GROUNDS` | 3 | Maximum planes allowed on the ground simultaneously |
| `MAX_PASSENGERS` | 50 | Maximum passengers per plane |
| `MAX_ARRIVAL_DELAY` | 2000 | Maximum milliseconds between plane arrivals |
**Execution Flow:**
1. Records simulation start time
2. Creates: `Statistics`, `Runway`, `AirportGrounds(3)`, `RefuellingTruck`
3. Creates 3 `Gate` objects
4. Creates `ATC` with references to runway, gates, and grounds
5. Creates 6 `Plane` objects — Plane-6 (`i == 5`) is flagged as emergency
6. Starts each plane thread with a random delay of 0–2000ms between them
7. Calls `join()` on each plane to wait for completion
8. Prints total simulation time and final report via `stats.printReport(gates)`
---
### 4.2 ATC — Air Traffic Control Monitor
**File:** `src/stccp/main/atc/ATC.java`  
**Type:** Monitor (shared object, **not** a thread)
**Responsibility:** Coordinates all landing and takeoff operations. Manages the runway, gate assignments, ground capacity, and emergency priority. All methods are `synchronized`.
**Fields:**
| Field | Type | Purpose |
|-------|------|---------|
| `runway` | `Runway` | Reference to the single runway |
| `gates` | `Gate[]` | Array of 3 gates |
| `grounds` | `AirportGrounds` | Ground capacity tracker |
| `emergencyWaiting` | `boolean` | Flag — `true` if an emergency plane is waiting to land |
| `landingQueue` | `ArrayList<Plane>` | FIFO queue for landing requests (emergency goes to front) |
**Methods:**
#### `synchronized Gate requestLanding(Plane plane)`
A plane calls this to request landing permission. The method:
1. Adds the plane to the landing queue (emergency goes to index 0, normal goes to end)
2. Enters a `while` loop that checks **five conditions** — if any is true, the plane `wait()`s:
| Condition | Meaning |
|-----------|---------|
| `runway.isOccupied()` | Runway is in use (landing or takeoff) |
| `grounds.isFull()` | Already 3 planes on the ground |
| `landingQueue.get(0) != plane` | This plane is not at the front of the queue |
| `emergencyWaiting && !plane.isEmergency()` | An emergency plane has priority |
| `getAvailableGate() == null` | No free gate available |
3. Once all conditions are false, grants landing:
   - Clears emergency flag if this is the emergency plane
   - Removes plane from queue
   - Occupies the runway
   - Increments ground count
   - Assigns and occupies an available gate
4. Returns the assigned `Gate`
#### `synchronized void notifyRunwayFreeAfterLanding()`
Called by a plane after it has landed and cleared the runway. Vacates the runway and calls `notifyAll()` to wake all waiting planes.
#### `synchronized void requestTakeoff(Plane plane)`
A plane calls this to request takeoff. Enters a `while` loop — waits if the runway is occupied. Once free, occupies the runway and grants takeoff.
#### `synchronized void notifyDeparture(Plane plane, Gate gate)`
Called by a plane after it has taken off. Vacates the gate, vacates the runway, decrements ground count, and calls `notifyAll()` to wake all waiting planes.
#### `private Gate getAvailableGate()`
Iterates through the gates array and returns the first unoccupied gate, or `null` if all are occupied.
---
### 4.3 Plane
**File:** `src/stccp/main/vehicles/Plane.java`  
**Type:** Thread (`extends Thread`)
**Responsibility:** Represents a single aircraft. Each plane is an independent thread that goes through the full airport lifecycle from arrival to departure.
**Fields:**
| Field | Type | Purpose |
|-------|------|---------|
| `planeId` | `int` | Unique identifier (1–6) |
| `emergency` | `boolean` | `true` for Plane-6 (fuel emergency) |
| `atc` | `ATC` | Reference to the ATC monitor |
| `truck` | `RefuellingTruck` | Reference to the shared refuelling truck |
| `stats` | `Statistics` | Reference to the statistics collector |
| `assignedGate` | `Gate` | Gate assigned by ATC during landing |
| `passengerCount` | `int` | Random number of passengers (1–50) |
| `arrivalTime` | `long` | Timestamp when the plane arrived |
**Thread name:** `Thread-Plane-X` (e.g., `Thread-Plane-1`)
**`run()` method sequence:**
```
land() → coastToGate() → dock() → performGateActivities() →
embarkPassengers() → undock() → coastToRunway() → takeoff()
```
**Key method — `performGateActivities()`:**
Creates and starts **three concurrent threads**:
1. `PassengerGroup` — disembarking passengers
2. `Supplies-PX` — cleaning and restocking (anonymous thread)
3. `Refuel-PX` — refuelling via the shared truck (anonymous thread)
All three are started first, then all three are joined — ensuring they execute concurrently.
**Key method — `embarkPassengers()`:**
Creates a new `PassengerGroup` for boarding (new random count of 1–50 passengers), starts it, joins it, and records the boarding count in statistics.
---
### 4.4 Runway
**File:** `src/stccp/main/airport/Runway.java`  
**Type:** Shared resource (plain object)
**Responsibility:** Represents the airport's single runway. Tracks whether it is currently in use.
**Fields:**
| Field | Type | Purpose |
|-------|------|---------|
| `occupied` | `boolean` | `true` if a plane is using the runway |
**Methods:**
| Method | Action |
|--------|--------|
| `occupy()` | Sets `occupied = true` |
| `vacate()` | Sets `occupied = false` |
| `isOccupied()` | Returns current state |
> **Note:** The `Runway` class itself is not synchronized. Thread-safety is provided by the ATC's `synchronized` methods, which are the only callers.
---
### 4.5 Gate
**File:** `src/stccp/main/airport/Gate.java`  
**Type:** Shared resource (plain object)
**Responsibility:** Represents a single parking gate. There are 3 gates. Each gate can hold one plane at a time.
**Fields:**
| Field | Type | Purpose |
|-------|------|---------|
| `gateNumber` | `int` | Gate identifier (1–3) |
| `occupied` | `boolean` | `true` if a plane is docked |
| `currentPlane` | `Plane` | Reference to the docked plane (or `null`) |
**Methods:**
| Method | Action |
|--------|--------|
| `occupy(Plane)` | Sets occupied, stores the plane reference |
| `vacate()` | Clears occupied, sets plane to `null` |
| `isOccupied()` | Returns whether the gate has a plane |
| `getGateNumber()` | Returns the gate's number |
| `getCurrentPlane()` | Returns the docked plane |
| `toString()` | Returns e.g. `Gate-1 [OCCUPIED by Plane-3]` or `Gate-2 [FREE]` |
> **Note:** Like `Runway`, the `Gate` class is not synchronized internally. Thread-safety is ensured by the ATC's `synchronized` methods.
---
### 4.6 AirportGrounds
**File:** `src/stccp/main/airport/AirportGrounds.java`  
**Type:** Shared resource (plain object)
**Responsibility:** Tracks how many planes are currently on the ground. Maximum capacity is 3.
**Fields:**
| Field | Type | Purpose |
|-------|------|---------|
| `maxCapacity` | `int` | Maximum planes allowed (set to 3) |
| `currentCount` | `int` | Current number of planes on the ground |
**Methods:**
| Method | Action |
|--------|--------|
| `planeArrived()` | Increments `currentCount` |
| `planeDeparted()` | Decrements `currentCount` (minimum 0) |
| `isFull()` | Returns `true` if `currentCount >= maxCapacity` |
| `getCurrentCount()` | Returns current count |
| `getMaxCapacity()` | Returns max capacity |
> **Note:** Not synchronized internally — protected by ATC's synchronized methods.
---
### 4.7 RefuellingTruck
**File:** `src/stccp/main/vehicles/RefuellingTruck.java`  
**Type:** Shared resource with its own synchronization
**Responsibility:** The airport has only one refuelling truck. Only one plane can be refuelled at a time.
**Methods:**
#### `synchronized void refuel(String planeName)`
- Logs that refuelling has started
- Sleeps for **5500ms** (simulates refuelling time)
- Logs that refuelling is complete
The `synchronized` keyword ensures that if multiple planes request refuelling simultaneously, they are serialized — one completes before the next begins.
---
### 4.8 PassengerGroup
**File:** `src/stccp/main/people/PassengerGroup.java`  
**Type:** Thread (`extends Thread`)
**Responsibility:** Manages a group of passengers either boarding or disembarking a plane. Each individual passenger is created as a separate thread.
**Fields:**
| Field | Type | Purpose |
|-------|------|---------|
| `planeId` | `int` | ID of the plane this group belongs to |
| `passengerCount` | `int` | Number of passengers in the group |
| `isEmbarking` | `boolean` | `true` for boarding, `false` for disembarking |
**Thread name:** `Passengers-PX` (e.g., `Passengers-P3`)
**`run()` method:**
1. Creates an array of `passengerCount` threads
2. Each thread is named `Passenger-N-Plane-X` (e.g., `Passenger-5-Plane-3`)
3. Each passenger thread:
   - Logs their boarding/disembarking action
   - Sleeps for 100–300ms (random)
4. All passenger threads are started first
5. All passenger threads are joined (waits for every passenger to finish)
6. Logs that all passengers have completed
---
### 4.9 Statistics
**File:** `src/stccp/main/stats/Statistics.java`  
**Type:** Shared resource with its own synchronization
**Responsibility:** Collects timing and service data during the simulation. Produces the final report.
**Fields:**
| Field | Type | Purpose |
|-------|------|---------|
| `waitTimes` | `List<Long>` | Time each plane spent at the airport (arrival to departure) |
| `totalPassengers` | `int` | Cumulative passengers boarded across all planes |
| `planesServed` | `int` | Number of planes that have departed |
**Methods:**
| Method | Synchronized | Action |
|--------|-------------|--------|
| `recordWaitTime(planeId, waitMs)` | ✅ | Adds time to wait list, logs the value |
| `recordPassengersBoarded(count)` | ✅ | Adds count to total passengers |
| `recordPlaneDeparted()` | ✅ | Increments planes served |
| `printReport(gates)` | ❌ (called by main thread after all planes finish) | Prints the final summary |
**Final Report Contents:**
- Gate status (all should be `[FREE]`)
- Minimum, maximum, and average wait times (in seconds)
- Total planes served
- Total passengers boarded
---
### 4.10 SimLogger
**File:** `src/stccp/main/utils/SimLogger.java`  
**Type:** Utility class (static methods)
**Responsibility:** Provides centralized, thread-safe console logging so output from concurrent threads is never garbled.
**Method:**
#### `static synchronized void log(String actor, String message)`
- Gets the current timestamp in `HH:mm:ss.SSS` format
- Gets the current thread's name via `Thread.currentThread().getName()`
- Prints a formatted log line
**Output format:**
```
[HH:mm:ss.SSS] [ThreadName         ] Actor: Message
```
**Example:**
```
[18:50:14.286] [Thread-Plane-1    ] Plane-1: Requesting Landing.
[18:50:14.289] [Thread-Plane-1    ] ATC: Landing Permission granted for Plane-1.
```
The `static synchronized` ensures mutual exclusion at the class level — no two threads can print simultaneously.
---
## 5. Concurrency Design
### 5.1 The ATC Monitor Pattern
The `ATC` class is the most critical concurrency component. It is designed as a **monitor** — a shared object with `synchronized` methods — **not** as a separate thread.
When a plane calls `atc.requestLanding(this)`, it is the **plane's own thread** that enters the synchronized method and executes the logic. The ATC never "acts on behalf of" a plane. This is visible in the log output:
```
[18:50:14.286] [Thread-Plane-1    ] Plane-1: Requesting Landing.
[18:50:14.289] [Thread-Plane-1    ] ATC: Landing Permission granted for Plane-1.
```
Both lines execute on `Thread-Plane-1`. The second line says `ATC:` because the ATC component made the decision, but it was Plane-1's thread doing the work inside the ATC's synchronized method.
### 5.2 The `while` Loop Guard
The `requestLanding()` and `requestTakeoff()` methods use a `while` loop (not `if`) around `wait()`:
```java
while (runway.isOccupied() || grounds.isFull() || ...) {
    wait();
}
```
This guards against:
1. **Spurious wake-ups** — Java's `wait()` can return without `notifyAll()` being called. The `while` loop re-checks the condition.
2. **Stolen conditions** — When `notifyAll()` wakes multiple threads, the first to reacquire the lock may consume the resource. Other threads must re-check and go back to waiting.
### 5.3 Thread Ownership — No Thread Acts for Another
Every action in the system is performed by the thread that owns it. The ATC has no thread of its own. When ATC methods log messages with the actor `"ATC"`, the executing thread is always a plane thread. This is made visible by `SimLogger` including both:
- **Thread name** — `Thread.currentThread().getName()` — shows which thread is running
- **Actor** — the `actor` parameter — shows which component is acting
### 5.4 Shared Resource Protection
| Resource | Protection Method | Reason |
|---|---|---|
| **Runway** | ATC's `synchronized` methods manage occupy/vacate | Only one plane uses the runway at a time |
| **Gates** | ATC's `synchronized` methods manage occupy/vacate | Each gate holds one plane |
| **Ground count** | ATC's `synchronized` methods call `AirportGrounds` | Max 3 planes on the ground |
| **Fuel truck** | `RefuellingTruck.refuel()` is `synchronized` | Only one plane refuels at a time |
| **Statistics data** | `Statistics` record methods are `synchronized` | Multiple planes record data concurrently |
| **Console output** | `SimLogger.log()` is `static synchronized` | Prevents garbled/interleaved output |
### 5.5 Deadlock Prevention
The system avoids deadlock through three strategies:
1. **Consistent resource ordering:** Every plane acquires resources in the same order: ATC lock → runway → gate → fuel truck. No circular wait is possible since all threads follow the same sequence.
2. **No nested locking:** A plane calls `atc.requestLanding()` (acquires ATC lock), which returns a gate. The ATC lock is released when the method returns. At no point does a thread hold two monitors simultaneously.
3. **`notifyAll()` over `notify()`:** Every notification wakes **all** waiting threads, not just one. This prevents a scenario where the notified thread cannot proceed while another thread could have.
---
## 6. Thread Summary
| Thread | Named | Count | Role |
|--------|-------|-------|------|
| Main | `main` | 1 | Creates resources, starts planes, waits, prints report |
| Plane threads | `Thread-Plane-X` | 6 | Full airport lifecycle per plane |
| Passenger groups | `Passengers-PX` | Up to 12 | Manages disembark/embark per plane |
| Individual passengers | `Passenger-N-Plane-X` | Up to ~600 | Each passenger boards/exits individually |
| Supplies threads | `Supplies-PX` | Up to 6 | Cleaning and restocking per plane |
| Refuel threads | `Refuel-PX` | Up to 6 | Refuelling per plane (serialized by truck lock) |
---
## 7. Key Design Decisions
### 7.1 ATC as Monitor vs. ATC as Thread
The ATC was implemented as a **monitor** rather than a separate thread. If the ATC were a thread processing a request queue, the ATC thread would be acting on behalf of planes — violating the "no thread acts for another" principle. With the monitor approach, each plane calls `atc.requestLanding(this)` and the plane's own thread does all the work.
### 7.2 Landing Queue for Fairness and Emergency Priority
An `ArrayList<Plane>` serves as the landing queue:
- Normal planes are added to the end with `add()`
- Emergency planes are inserted at index 0 with `add(0, plane)`
The condition `landingQueue.get(0) != plane` ensures strict FIFO ordering, while the `emergencyWaiting` flag blocks all non-emergency planes when an emergency is pending.
`PriorityBlockingQueue` from `java.util.concurrent` was avoided as it is a restricted library for this assignment.
### 7.3 Concurrent Gate Activities (Fork/Join Pattern)
All three gate activity threads are started **before** any `join()` is called:
```java
disembarkGroup.start();
suppliesThread.start();
refuelThread.start();
disembarkGroup.join();
suppliesThread.join();
refuelThread.join();
```
If `join()` were called immediately after each `start()`, the operations would execute sequentially rather than concurrently.
### 7.4 Individual Passenger Threads
Each passenger runs as their own thread inside `PassengerGroup`, explicitly named (e.g., `Passenger-3-Plane-1`). The group creates all passenger threads, starts them all, then joins them all.
### 7.5 Centralized Logging
All threads print through `SimLogger.log()` which is `static synchronized`. This guarantees:
- No garbled/interleaved lines
- Consistent timestamps (`HH:mm:ss.SSS`)
- Thread identity visible in every line
- Actor identity visible in every line
---
## 8. Simulation Configuration
| Parameter | Value | Location |
|-----------|-------|----------|
| Number of planes | 6 | `AirportSimulation.TOTAL_PLANES` |
| Number of gates | 3 | `AirportSimulation.TOTAL_GATES` |
| Max planes on ground | 3 | `AirportSimulation.MAX_ON_GROUNDS` |
| Max passengers per plane | 50 | `AirportSimulation.MAX_PASSENGERS` |
| Max arrival delay | 2000ms | `AirportSimulation.MAX_ARRIVAL_DELAY` |
| Landing duration | 3000ms | `Plane.land()` |
| Takeoff duration | 3000ms | `Plane.takeoff()` |
| Coasting duration | 1500ms | `Plane.coastToGate()` / `coastToRunway()` |
| Docking/undocking duration | 500ms | `Plane.dock()` / `undock()` |
| Refuelling duration | 5500ms | `RefuellingTruck.refuel()` |
| Supplies/cleaning duration | 3000–5000ms | `Plane.performGateActivities()` |
| Passenger boarding/exit time | 100–300ms each | `PassengerGroup.run()` |
| Emergency plane | Plane-6 | `AirportSimulation` (`i == 5`) |
---
## 9. Sample Output Format
Each log line follows this format:
```
[Timestamp  ] [Thread Name       ] Actor: Message
```
**Example sequence:**
```
[18:50:14.286] [Thread-Plane-1    ] Plane-1: Arrived and requesting landing.
[18:50:14.286] [Thread-Plane-1    ] Plane-1: Requesting Landing.
[18:50:14.289] [Thread-Plane-1    ] ATC: Landing Permission granted for Plane-1.
[18:50:14.289] [Thread-Plane-1    ] ATC: Gate-1 assigned for Plane-1.
[18:50:14.289] [Thread-Plane-1    ] Plane-1: Landing.
[18:50:17.290] [Thread-Plane-1    ] Plane-1: Landed.
[18:50:17.291] [Thread-Plane-1    ] Plane-1: Coasting to Gate-1.
```
**Final report format:**
```
========================================
     ASIA PACIFIC AIRPORT - FINAL REPORT
========================================
Gate Status:
  Gate-1 [FREE]
  Gate-2 [FREE]
  Gate-3 [FREE]
  All gates clear: YES
[Wait Time Statistics]
  Minimum wait time : XX.XX s
  Maximum wait time : XX.XX s
  Average wait time : XX.XX s
[Service Statistics]
  Planes served     : 6
  Passengers boarded: XXX
========================================
```
---
## 10. Requirements Compliance
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
| Simulation under 60 seconds | ✅ | Consistently completes within time |
### Additional Requirements
| Requirement | Status | Implementation |
|---|---|---|
| 1 refuelling truck (exclusive) | ✅ | `RefuellingTruck` with `synchronized refuel()` |
| Emergency landing priority | ✅ | Plane-6 inserted at front of queue with `add(0, plane)` |
### Restrictions
| Restriction | Status |
|---|---|
| No `java.util.concurrent.*` | ✅ Not used |
| No `parallelStream` | ✅ Not used |
| No `Timer` | ✅ Not used |
| No `@Async` | ✅ Not used |
| No thread acts for another | ✅ ATC is monitor — plane thread executes all logic |
### Concurrency Primitives Used
| Primitive | Where Used | Purpose |
|---|---|---|
| `synchronized` (method) | ATC, RefuellingTruck, Statistics, SimLogger | Mutual exclusion |
| `wait()` | ATC.requestLanding(), ATC.requestTakeoff() | Suspend thread until condition met |
| `notifyAll()` | ATC.notifyRunwayFreeAfterLanding(), ATC.notifyDeparture() | Wake all waiting threads |
| `Thread.start()` | Plane, PassengerGroup, gate activity threads | Fork new threads |
| `Thread.join()` | Plane gate activities, passengers, AirportSimulation | Wait for thread completion |
| `Thread.sleep()` | All simulation steps | Simulate time passing |
