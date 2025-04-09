package restaurant;

import java.io.Serializable;
import java.util.Objects;

// Classe pour contenir les informations sur un restaurant (ID et capacité)
// Doit être Serializable pour être passée comme argument aux agents JADE
public class RestaurantInfo implements Serializable {
    private static final long serialVersionUID = 1L; // Bonne pratique pour Serializable
    private String id;
    private int capacity;

    public RestaurantInfo(String id, int capacity) {
        this.id = id;
        this.capacity = capacity;
    }

    public String getId() {
        return id;
    }

    public int getCapacity() {
        return capacity;
    }

    @Override
    public String toString() {
        return "RestaurantInfo{" +
                "id='" + id + '\'' +
                ", capacity=" + capacity +
                '}';
    }

    // Égalité basée sur l'ID pour faciliter les recherches
    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        RestaurantInfo that = (RestaurantInfo) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}