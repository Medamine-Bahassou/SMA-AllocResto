package restaurant;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.Runtime;
import jade.wrapper.AgentContainer;
import jade.wrapper.AgentController;
import jade.wrapper.StaleProxyException;

import java.util.ArrayList;
import java.util.List;

public class MainLauncher {

    public static void main(String[] args) {

        // ====================================================================
        //                PARAMÈTRES DE LA SIMULATION
        // ====================================================================
        int N = 15; // <--- MODIFIEZ ICI : Nombre de personnes (agents PersonAgent)

        // <--- MODIFIEZ ICI : Liste des restaurants (M restaurants) et leurs capacités (Ci)
        List<RestaurantInfo> restaurants = new ArrayList<>();
        // Assurez-vous que SUM(Ci) > 2 * N et chaque Ci < N
        restaurants.add(new RestaurantInfo("Resto_A", 7));  // M=1, C1=7
        restaurants.add(new RestaurantInfo("Resto_B", 10)); // M=2, C2=10
        restaurants.add(new RestaurantInfo("Resto_C", 5));  // M=3, C3=5
        restaurants.add(new RestaurantInfo("Resto_D", 9));  // M=4, C4=9
        // M = 4 (nombre de restaurants)

        // Vérification rapide des contraintes
        int totalCapacity = restaurants.stream().mapToInt(RestaurantInfo::getCapacity).sum();
        boolean capacityConstraint = totalCapacity > (2 * N);
        boolean individualCapacityConstraint = restaurants.stream().allMatch(r -> r.getCapacity() < N);

        System.out.println("--- Simulation Parameters ---");
        System.out.println("N (People): " + N);
        System.out.println("M (Restaurants): " + restaurants.size());
        System.out.println("Total Capacity (Sum Ci): " + totalCapacity);
        System.out.println("Constraint Sum(Ci) > 2*N : " + capacityConstraint);
        System.out.println("Constraint Ci < N       : " + individualCapacityConstraint);
        System.out.println("-----------------------------");

        if (!capacityConstraint || !individualCapacityConstraint) {
            System.err.println("ERROR: Capacity constraints are not met! Adjust N or restaurant capacities.");
            // System.exit(1); // Arrêter si les contraintes ne sont pas respectées
            // Ou juste afficher un avertissement et continuer
            System.err.println("WARNING: Running simulation even though constraints are not met.");
        }
        // ====================================================================

        // 1. Initialiser le Runtime JADE
        Runtime rt = Runtime.instance();
        rt.setCloseVM(true); // Permet à la JVM de se fermer quand le Main Container se ferme

        // 2. Créer un profil pour le Main Container
        Profile profile = new ProfileImpl(null, 1099, null); // Port par défaut, pas d'hôte spécifique
        profile.setParameter(Profile.GUI, "true"); // Démarrer l'interface graphique RMA

        // 3. Créer le Main Container
        AgentContainer mainContainer = rt.createMainContainer(profile);
        System.out.println("Main Container created. Starting agents...");

        try {
            // 4. Créer et démarrer l'EnvironmentAgent
            // On lui passe la liste des restaurants et N comme arguments
            Object[] envArgs = new Object[]{restaurants, N};
            AgentController envAgent = mainContainer.createNewAgent(
                    "environment-manager",                    // Nom de l'agent
                    EnvironmentAgent.class.getName(), // Classe de l'agent
                    envArgs);                         // Arguments
            envAgent.start();
            System.out.println("EnvironmentAgent started.");
            // Petite pause pour laisser le temps à l'env agent de s'enregistrer au DF
            try { Thread.sleep(500); } catch (InterruptedException e) {}


            // 5. Créer et démarrer les N PersonAgents
            // On leur passe la liste des restaurants comme argument
            Object[] personArgs = new Object[]{restaurants};
            for (int i = 0; i < N; i++) {
                AgentController personAgent = mainContainer.createNewAgent(
                        "person-" + i,                   // Nom unique pour chaque agent
                        PersonAgent.class.getName(), // Classe de l'agent
                        personArgs);                 // Arguments
                personAgent.start();
                // Petite pause entre les démarrages pour étaler les accès au DF (optionnel)
                // try { Thread.sleep(50); } catch (InterruptedException e) {}
            }
            System.out.println(N + " PersonAgents started.");


        } catch (StaleProxyException e) {
            System.err.println("Error starting agents: " + e.getMessage());
            e.printStackTrace();
        } catch (Exception e) {
            System.err.println("An unexpected error occurred during agent startup: " + e.getMessage());
            e.printStackTrace();
        }

        System.out.println("\nSimulation setup complete. Agents are running...");
        System.out.println("Monitor the console output and the JADE RMA GUI.");
        System.out.println("The simulation will show agent actions, choices, polling, deliberations, and reservation attempts.");
        System.out.println("Final statistics will be printed when the EnvironmentAgent terminates (manually closed via RMA or program exit).");

    }
}