package restaurant;

public class RestaurantState {
    private final int capacity;
    private int occupation;

    public RestaurantState(int capacity) {
        this.capacity = capacity;
        this.occupation = 0;
    }

    public int getCapacity() {
        return capacity;
    }

    public int getOccupation() {
        return occupation;
    }

    public boolean hasSpace() {
        return occupation < capacity;
    }

    // Retourne true si une place a été attribuée, false sinon
    public synchronized boolean occupyPlace() {
        if (hasSpace()) {
            occupation++;
            return true;
        }
        return false;
    }

    @Override
    public String toString() {
        return "State[Cap:" + capacity + ", Occ:" + occupation + "]";
    }
}