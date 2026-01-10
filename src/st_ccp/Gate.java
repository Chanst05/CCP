package st_ccp;

public class Gate {
    public final int id;

    // have to be read/update only while airport's gate aloocation lock
    boolean occupied = false;

    // optional debugging/logging
    int currentPlaneId = -1;

    public Gate(int id) {
        this.id = id;
    }

    @Override
    public String toString() {
        return "Gate-" + id;
    }
}
