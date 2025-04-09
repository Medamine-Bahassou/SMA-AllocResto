package restaurant;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class EnvironmentAgent extends Agent {

    // Map pour stocker l'état actuel de chaque restaurant (ID -> État)
    private Map<String, RestaurantState> restaurantsState;
    // Compteurs pour les statistiques
    private int totalCallsReceived = 0;
    private int successfulReservations = 0;
    private int totalCapacityN; // Nombre total de personnes à placer

    // Constantes pour les types de service et les protocoles (bonnes pratiques)
    public static final String RESTAURANT_SERVICE_TYPE = "restaurant-manager";
    public static final String RESERVATION_PROTOCOL = "ReservationProtocol";
    public static final String REASON_FULL = "full";
    public static final String REASON_UNKNOWN = "unknown-restaurant";

    @Override
    protected void setup() {
        System.out.println("EnvironmentAgent " + getAID().getName() + " is ready.");

        // Récupérer les arguments (liste des restaurants et N)
        Object[] args = getArguments();
        if (args != null && args.length == 2 && args[0] instanceof List && args[1] instanceof Integer) {
            List<RestaurantInfo> restaurantList = (List<RestaurantInfo>) args[0];
            this.totalCapacityN = (Integer) args[1];
            this.restaurantsState = new HashMap<>();

            System.out.println("EnvironmentAgent: Initializing restaurants:");
            for (RestaurantInfo info : restaurantList) {
                restaurantsState.put(info.getId(), new RestaurantState(info.getCapacity()));
                System.out.println("  - " + info.getId() + " (Capacity: " + info.getCapacity() + ")");
            }
            System.out.println("EnvironmentAgent: Total persons to seat: " + totalCapacityN);

        } else {
            System.err.println("EnvironmentAgent: Invalid arguments. Expecting List<RestaurantInfo> and Integer N.");
            doDelete(); // Termine l'agent s'il ne peut pas s'initialiser
            return;
        }

        // Enregistrer le service de gestion des restaurants auprès du DF
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(RESTAURANT_SERVICE_TYPE);
        sd.setName("JADE-Restaurant-Reservation"); // Nom unique pour le service
        sd.addProtocols(RESERVATION_PROTOCOL); // Indique le protocole géré
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            System.out.println("EnvironmentAgent: Registered service '" + RESTAURANT_SERVICE_TYPE + "' with DF.");
        } catch (FIPAException fe) {
            System.err.println("EnvironmentAgent: Error registering service with DF: " + fe.getMessage());
            fe.printStackTrace();
            doDelete();
        }

        // Ajouter le comportement pour gérer les demandes de réservation
        addBehaviour(new ReservationHandlerBehaviour());
    }

    @Override
    protected void takeDown() {
        // Se désenregistrer du DF
        try {
            DFService.deregister(this);
            System.out.println("EnvironmentAgent: Deregistered service from DF.");
        } catch (FIPAException fe) {
            fe.printStackTrace();
        }

        // Afficher les statistiques finales
        System.out.println("\n--- EnvironmentAgent " + getAID().getName() + " terminating ---");
        System.out.println("Final Restaurant States:");
        for (Map.Entry<String, RestaurantState> entry : restaurantsState.entrySet()) {
            System.out.println("  - " + entry.getKey() + ": " + entry.getValue());
        }
        System.out.println("Total reservation calls received: " + totalCallsReceived);
        System.out.println("Total successful reservations: " + successfulReservations);
        System.out.println("--------------------------------------------------");
    }

    // Comportement interne pour gérer les demandes de réservation
    private class ReservationHandlerBehaviour extends CyclicBehaviour {
        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.REQUEST), // On écoute les REQUEST
                MessageTemplate.MatchProtocol(RESERVATION_PROTOCOL)    // Qui suivent notre protocole
        );

        @Override
        public void action() {
            ACLMessage msg = myAgent.receive(template);
            if (msg != null) {
                totalCallsReceived++; // Compter chaque appel reçu
                String restaurantId = msg.getContent(); // Le contenu est l'ID du restaurant demandé
                AID requester = msg.getSender();

                System.out.println("EnvironmentAgent: Received reservation request for '" + restaurantId + "' from " + requester.getLocalName());

                ACLMessage reply = msg.createReply(); // Préparer la réponse
                reply.setProtocol(RESERVATION_PROTOCOL);

                RestaurantState state = restaurantsState.get(restaurantId);

                if (state == null) {
                    // Restaurant inconnu
                    reply.setPerformative(ACLMessage.FAILURE);
                    reply.setContent(REASON_UNKNOWN);
                    System.out.println("EnvironmentAgent: FAILURE for " + requester.getLocalName() + " at '" + restaurantId + "' (Reason: Unknown Restaurant)");
                } else {
                    // Essayer d'occuper une place (méthode synchronisée dans RestaurantState)
                    if (state.occupyPlace()) {
                        // Succès
                        successfulReservations++;
                        reply.setPerformative(ACLMessage.CONFIRM);
                        reply.setContent("Reservation confirmed at " + restaurantId); // Contenu optionnel
                        System.out.println("EnvironmentAgent: CONFIRM for " + requester.getLocalName() + " at '" + restaurantId + "'. New state: " + state + ". Total seated: " + successfulReservations);

                        // Vérifier si tout le monde est assis (optionnel, pour arrêter la simu plus tôt)
                        // if (successfulReservations == totalCapacityN) {
                        //     System.out.println("\nEnvironmentAgent: All " + totalCapacityN + " agents have been seated!\n");
                        //     // On pourrait déclencher un arrêt global ici si nécessaire
                        // }

                    } else {
                        // Échec (restaurant plein)
                        reply.setPerformative(ACLMessage.FAILURE);
                        reply.setContent(REASON_FULL);
                        System.out.println("EnvironmentAgent: FAILURE for " + requester.getLocalName() + " at '" + restaurantId + "' (Reason: Full)");
                    }
                }
                myAgent.send(reply); // Envoyer la réponse (CONFIRM ou FAILURE)

            } else {
                // Si aucun message ne correspond, bloquer le comportement jusqu'au prochain message
                block();
            }
        }
    }
}