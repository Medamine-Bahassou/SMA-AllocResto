import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

public class Main {

    // --- Simulation Parameters ---
    static final int N_PERSONNES = 20; // Number of people (N)
    static final int M_RESTAURANTS = 8;  // Number of restaurants (M)
    static final int BASE_CAPACITY = 8; // Base capacity for random generation
    static final double RESTAURANT_SATURATION_PROB = 0.3; // Probability a restaurant REFUSES despite having space
    static final int P_POLL_SIZE = 5; // Number of agents to poll (used in deliberation logic)
    // --------------------------

    public static void main(String[] args) {
        Runtime rt = Runtime.instance();
        Profile profile = new ProfileImpl();
        profile.setParameter(Profile.GUI, "true"); // Optional: Launch JADE GUI
        AgentContainer container = rt.createMainContainer(profile);

        List<String> restaurantNames = new ArrayList<>();
        List<Integer> restaurantCapacities = new ArrayList<>();
        Random random = new Random();
        int totalCapacity = 0;

        System.out.println("--- Simulation Parameters ---");
        System.out.println("N (People): " + N_PERSONNES);
        System.out.println("M (Restaurants): " + M_RESTAURANTS);
        System.out.println("Base Capacity (approx): " + BASE_CAPACITY);
        System.out.println("Saturation Probability (Refusal Chance): " + RESTAURANT_SATURATION_PROB);
        System.out.println("P (Polling Sample Size): " + P_POLL_SIZE);
        System.out.println("-----------------------------");


        // --- Generate Restaurants ---
        System.out.println("Generating Restaurants...");
        for (int i = 0; i < M_RESTAURANTS; i++) {
            // Generate capacity Ci such that Ci < N
            int capacity = 0;
            do {
                // Example: capacity between BASE_CAPACITY/2 and BASE_CAPACITY * 1.5, but strictly less than N
                capacity = random.nextInt(BASE_CAPACITY) + BASE_CAPACITY / 2;
            } while (capacity >= N_PERSONNES);

            restaurantCapacities.add(capacity);
            totalCapacity += capacity;
            String name = "Restaurant" + i;
            restaurantNames.add(name);

            System.out.printf("  Creating %s with Capacity Ci = %d\n", name, capacity);
            try {
                Object[] restaurantArgs = new Object[]{capacity, RESTAURANT_SATURATION_PROB};
                AgentController rest = container.createNewAgent(name, "agents.AgentRestaurant", restaurantArgs);
                rest.start();
            } catch (StaleProxyException e) {
                System.err.println("Error creating restaurant agent: " + name);
                e.printStackTrace();
                return; // Exit if setup fails
            }
        }

        // --- Validate Total Capacity ---
        System.out.println("Total Capacity (Sum Ci): " + totalCapacity);
        if (totalCapacity <= 2 * N_PERSONNES) {
            System.err.printf("Error: Total capacity (%d) is not greater than 2*N (%d). Adjust parameters.\n",
                    totalCapacity, 2 * N_PERSONNES);
            // Optionally shutdown runtime here if desired
            try { rt.shutDown(); } catch (Exception e) {}
            return;
        } else {
            System.out.printf("Capacity Check OK: Total Capacity (%d) > 2*N (%d)\n",
                    totalCapacity, 2 * N_PERSONNES);
        }
        System.out.println("-----------------------------");


        // --- Create Person Agents ---
        System.out.println("Creating Person Agents...");
        // Convert List<String> to String[] for agent arguments
        String[] restaurantNameArray = restaurantNames.toArray(new String[0]);

        for (int i = 0; i < N_PERSONNES; i++) {
            String name = "Personne" + i;
            System.out.println("  Creating " + name);
            try {
                // Pass restaurant list and polling size P to each agent
                Object[] personArgs = new Object[]{restaurantNameArray, P_POLL_SIZE};
                AgentController person = container.createNewAgent(name, "agents.AgentPersonne", personArgs);
                person.start();
            } catch (StaleProxyException e) {
                System.err.println("Error creating person agent: " + name);
                e.printStackTrace();
            }
        }

        System.out.println("-----------------------------");
        System.out.println("Simulation Setup Complete. Agents are running...");
        System.out.println("Monitor agent outputs for results (reservation attempts).");
        // The simulation runs until agents complete their behaviour.
        // We don't explicitly stop it here, let agents finish.
        // For clean shutdown or result aggregation, a Coordinator agent would be better.

    }
}