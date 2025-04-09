package restaurant;

import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.*;
import jade.domain.DFService;
import jade.domain.FIPAAgentManagement.DFAgentDescription;
import jade.domain.FIPAAgentManagement.SearchConstraints;
import jade.domain.FIPAAgentManagement.ServiceDescription;
import jade.domain.FIPAException;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;
import java.util.stream.Collectors;

public class PersonAgent extends Agent {

    // Statut de l'agent
    private enum Status { INITIALIZING, FINDING_ENVIRONMENT, READY, POLLING, DELIBERATING, RESERVING, SEATED, FAILED }
    private Status status = Status.INITIALIZING;

    // Informations générales
    private List<RestaurantInfo> restaurantList;
    private AID environmentAgentAID; // AID de l'agent environnement
    private List<AID> otherPersonAgents; // Liste des autres agents Personne pour le sondage

    // État de la recherche
    private String currentChoiceRestaurantId = null; // ID du resto choisi actuellement
    private Map<String, Integer> pollResults; // Résultats du sondage (Restaurant ID -> Nombre de votes)
    private int retryCount = 0;
    private final int MAX_RETRIES = 5; // Limite pour éviter les boucles infinies

    // Constantes pour les services et protocoles
    public static final String POLLING_SERVICE_TYPE = "polling-responder";
    public static final String POLLING_PROTOCOL = "PollingProtocol";
    private String conversationIdBase; // Pour rendre les conversations uniques

    @Override
    protected void setup() {
        System.out.println("PersonAgent " + getAID().getLocalName() + " starting...");
        this.conversationIdBase = getAID().getLocalName() + System.currentTimeMillis(); // Base unique
        this.pollResults = new HashMap<>();
        this.otherPersonAgents = new ArrayList<>();

        // Récupérer la liste des restaurants
        Object[] args = getArguments();
        if (args != null && args.length == 1 && args[0] instanceof List) {
            this.restaurantList = (List<RestaurantInfo>) args[0];
            System.out.println("PersonAgent " + getLocalName() + " received restaurant list: " + restaurantList.size() + " restaurants.");
        } else {
            System.err.println("PersonAgent " + getLocalName() + ": Invalid arguments. Expecting List<RestaurantInfo>.");
            doDelete();
            return;
        }

        // Enregistrer le service pour répondre aux sondages
        registerPollingService();

        // Ajouter le comportement principal (Machine à états)
        addBehaviour(new FindPlaceFSM());
        // Ajouter le comportement pour répondre aux sondages des autres (tourne en parallèle)
        addBehaviour(new PollResponderBehaviour());
    }

    private void registerPollingService() {
        DFAgentDescription dfd = new DFAgentDescription();
        dfd.setName(getAID());
        ServiceDescription sd = new ServiceDescription();
        sd.setType(POLLING_SERVICE_TYPE);
        sd.setName("Polling-" + getLocalName()); // Nom unique
        sd.addProtocols(POLLING_PROTOCOL);
        dfd.addServices(sd);
        try {
            DFService.register(this, dfd);
            // System.out.println(getLocalName() + ": Registered polling service.");
        } catch (FIPAException fe) {
            System.err.println(getLocalName() + ": Error registering polling service: " + fe.getMessage());
            // On ne quitte pas forcément, peut-être qu'il peut quand même chercher sans être sondé
        }
    }

    @Override
    protected void takeDown() {
        // Se désenregistrer du DF
        try {
            DFService.deregister(this);
        } catch (FIPAException fe) {
            // Ignorer si déjà parti ou erreur
        }
        System.out.println("PersonAgent " + getLocalName() + " terminating with final status: " + status +
                (status == Status.SEATED ? " at " + currentChoiceRestaurantId : "") +
                ". Retries: " + retryCount);
    }

    // --- Machine à États Finis (FSM) pour gérer le processus de recherche ---
    private class FindPlaceFSM extends FSMBehaviour {
        // Noms des états pour la FSM
        private static final String STATE_FIND_ENVIRONMENT = "FindEnvironment";
        private static final String STATE_INITIAL_CHOICE = "InitialChoice";
        private static final String STATE_POLL_OTHERS = "PollOthers";
        private static final String STATE_DELIBERATE = "Deliberate";
        private static final String STATE_ATTEMPT_RESERVATION = "AttemptReservation";
        private static final String STATE_HANDLE_FAILURE = "HandleFailure";
        private static final String STATE_SEATED = "Seated"; // État final succès
        private static final String STATE_FAILED = "Failed"; // État final échec

        public FindPlaceFSM() {
            super(PersonAgent.this); // Lier la FSM à l'agent

            // --- Enregistrement des États ---
            registerFirstState(new FindEnvironmentBehaviour(), STATE_FIND_ENVIRONMENT);
            registerState(new InitialChoiceBehaviour(), STATE_INITIAL_CHOICE);
            registerState(new PollOthersBehaviour(), STATE_POLL_OTHERS);
            registerState(new DeliberateBehaviour(), STATE_DELIBERATE);
            registerState(new AttemptReservationBehaviour(), STATE_ATTEMPT_RESERVATION);
            registerState(new HandleFailureBehaviour(), STATE_HANDLE_FAILURE);
            registerLastState(new SeatedBehaviour(), STATE_SEATED); // État terminal
            registerLastState(new FailedBehaviour(), STATE_FAILED); // État terminal

            // --- Enregistrement des Transitions ---
            // Trouver Env -> Choix Initial (si succès) ou Échec (si échec)
            registerTransition(STATE_FIND_ENVIRONMENT, STATE_INITIAL_CHOICE, 1); // Event 1 = Success
            registerTransition(STATE_FIND_ENVIRONMENT, STATE_FAILED, 0);        // Event 0 = Failure

            // Choix Initial -> Sonder les Autres
            registerDefaultTransition(STATE_INITIAL_CHOICE, STATE_POLL_OTHERS);

            // Sonder -> Délibérer
            registerDefaultTransition(STATE_POLL_OTHERS, STATE_DELIBERATE);

            // Délibérer -> Tenter Réservation
            registerDefaultTransition(STATE_DELIBERATE, STATE_ATTEMPT_RESERVATION);

            // Tenter Réservation -> Assis (si succès) ou Gérer Échec (si échec)
            registerTransition(STATE_ATTEMPT_RESERVATION, STATE_SEATED, 1); // Event 1 = CONFIRM
            registerTransition(STATE_ATTEMPT_RESERVATION, STATE_HANDLE_FAILURE, 0); // Event 0 = FAILURE

            // Gérer Échec -> Choix Initial (si retry) ou Échec (si max retries)
            registerTransition(STATE_HANDLE_FAILURE, STATE_INITIAL_CHOICE, 1); // Event 1 = Retry possible
            registerTransition(STATE_HANDLE_FAILURE, STATE_FAILED, 0);        // Event 0 = Max retries reached
        }

        @Override
        public int onEnd() {
            String finalStateName = "UNKNOWN (FSM ended unexpectedly)"; // Default message
            Behaviour currentBehaviour = getCurrent();
            if (currentBehaviour != null) {
                finalStateName = currentBehaviour.getBehaviourName();
            }

            System.out.println(getLocalName() + ": FSM finished. Last active state: " + finalStateName);
            // L'agent se terminera via takeDown() après la fin de la FSM
            return super.onEnd();
        }
    }

    // --- Comportements pour chaque état de la FSM ---

    private class FindEnvironmentBehaviour extends OneShotBehaviour {
        private int transitionEvent = 0; // 0 = Failure, 1 = Success
        @Override
        public void action() {
            status = Status.FINDING_ENVIRONMENT;
            System.out.println(getLocalName() + ": Searching for Environment Agent...");
            DFAgentDescription template = new DFAgentDescription();
            ServiceDescription sd = new ServiceDescription();
            sd.setType(EnvironmentAgent.RESTAURANT_SERVICE_TYPE);
            template.addServices(sd);
            try {
                DFAgentDescription[] result = DFService.search(myAgent, template);
                if (result.length > 0) {
                    environmentAgentAID = result[0].getName();
                    System.out.println(getLocalName() + ": Found Environment Agent: " + environmentAgentAID.getName());
                    transitionEvent = 1; // Succès
                    status = Status.READY;
                } else {
                    System.err.println(getLocalName() + ": Environment Agent not found!");
                    status = Status.FAILED;
                    transitionEvent = 0; // Échec
                }
            } catch (FIPAException fe) {
                System.err.println(getLocalName() + ": Error searching DF: " + fe.getMessage());
                fe.printStackTrace();
                status = Status.FAILED;
                transitionEvent = 0; // Échec
            }
        }
        @Override
        public int onEnd() {
            return transitionEvent; // Déclenche la transition appropriée
        }
    }

    private class InitialChoiceBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            // Stratégie de choix initial simple : prendre un restaurant au hasard
            // Éviter de choisir celui qui a échoué juste avant (si retryCount > 0)
            List<RestaurantInfo> possibleChoices = new ArrayList<>(restaurantList);
            if (retryCount > 0 && currentChoiceRestaurantId != null) {
                possibleChoices.removeIf(r -> r.getId().equals(currentChoiceRestaurantId));
                System.out.println(getLocalName() + ": Avoiding previously failed restaurant " + currentChoiceRestaurantId);
            }

            if (possibleChoices.isEmpty()) { // Si on a échoué sur tous ? Rare mais possible
                System.out.println(getLocalName() + ": No more restaurants to try after failure avoidance.");
                possibleChoices.addAll(restaurantList); // Tenter à nouveau n'importe lequel
            }

            Random rand = new Random();
            currentChoiceRestaurantId = possibleChoices.get(rand.nextInt(possibleChoices.size())).getId();
            System.out.println(getLocalName() + ": Initial/New choice made: " + currentChoiceRestaurantId);
            // Réinitialiser les résultats du sondage pour le nouveau cycle
            pollResults.clear();
        }
    }

    private class PollOthersBehaviour extends Behaviour {
        private int step = 0;
        private MessageTemplate mt; // Template pour les réponses
        private int repliesCnt = 0; // Compteur de réponses reçues
        private long timeout;
        private String currentConvId;

        @Override
        public void onStart() {
            status = Status.POLLING;
            step = 0;
            repliesCnt = 0;
            otherPersonAgents.clear(); // Rafraîchir la liste à chaque sondage
            pollResults.clear();
            System.out.println(getLocalName() + ": Starting polling phase for choice " + currentChoiceRestaurantId);
        }

        @Override
        public void action() {
            switch (step) {
                case 0: // Trouver les autres agents Personne
                    System.out.println(getLocalName() + ": Searching for other PersonAgents to poll...");
                    DFAgentDescription template = new DFAgentDescription();
                    ServiceDescription sd = new ServiceDescription();
                    sd.setType(POLLING_SERVICE_TYPE);
                    template.addServices(sd);
                    SearchConstraints sc = new SearchConstraints();
                    sc.setMaxResults(-1L); // Tous les résultats
                    try {
                        DFAgentDescription[] result = DFService.search(myAgent, template, sc);
                        for (DFAgentDescription dfd : result) {
                            if (!dfd.getName().equals(myAgent.getAID())) { // Ne pas se sonder soi-même
                                otherPersonAgents.add(dfd.getName());
                            }
                        }
                        System.out.println(getLocalName() + ": Found " + otherPersonAgents.size() + " other agents to poll.");
                    } catch (FIPAException fe) {
                        System.err.println(getLocalName() + ": Error searching polling agents: " + fe.getMessage());
                        // Continuer sans sonder si erreur ? Ou échouer ? Pour l'instant, on continue.
                    }
                    step = 1; // Passer à l'envoi
                    break;

                case 1: // Envoyer les requêtes de sondage (QUERY_REF)
                    if (otherPersonAgents.isEmpty()) {
                        System.out.println(getLocalName() + ": No other agents to poll. Skipping polling.");
                        step = 3; // Aller directement à la fin si personne à sonder
                        break;
                    }

                    currentConvId = conversationIdBase + "-poll-" + System.currentTimeMillis(); // ID unique pour ce sondage
                    ACLMessage query = new ACLMessage(ACLMessage.QUERY_REF); // Demande d'information
                    query.setProtocol(POLLING_PROTOCOL);
                    query.setConversationId(currentConvId);
                    query.setReplyWith(currentConvId + "-reply"); // Identifiant attendu pour la réponse
                    query.setContent("What is your current restaurant choice?"); // Question simple

                    System.out.print(getLocalName() + ": Sending poll requests to: ");
                    for (AID agentAID : otherPersonAgents) {
                        query.addReceiver(agentAID);
                        System.out.print(agentAID.getLocalName() + " ");
                    }
                    System.out.println();
                    myAgent.send(query);

                    // Préparer le template pour recevoir les réponses
                    mt = MessageTemplate.and(
                            MessageTemplate.MatchProtocol(POLLING_PROTOCOL),
                            MessageTemplate.and(
                                    MessageTemplate.MatchPerformative(ACLMessage.INFORM), // On attend des INFORM
                                    MessageTemplate.MatchInReplyTo(query.getReplyWith()) // Qui répondent à notre requête
                            )
                    );
                    timeout = System.currentTimeMillis() + 2000; // Mettre un timeout (ex: 2 secondes)
                    step = 2;
                    break;

                case 2: // Recevoir les réponses (INFORM)
                    ACLMessage reply = myAgent.receive(mt);
                    if (reply != null) {
                        repliesCnt++;
                        String choice = reply.getContent();
                        if (choice != null && !choice.equalsIgnoreCase("null") && !choice.isEmpty()) {
                            pollResults.put(choice, pollResults.getOrDefault(choice, 0) + 1);
                            // System.out.println(getLocalName() + ": Received poll reply from " + reply.getSender().getLocalName() + " - Choice: " + choice);
                        } else {
                            // System.out.println(getLocalName() + ": Received null/empty poll reply from " + reply.getSender().getLocalName());
                        }
                        // Vérifier si on a reçu toutes les réponses ou si le timeout est dépassé
                        if (repliesCnt >= otherPersonAgents.size()) {
                            System.out.println(getLocalName() + ": Received all " + repliesCnt + " expected poll replies.");
                            step = 3; // Fin
                        }
                    } else if (System.currentTimeMillis() > timeout) {
                        System.out.println(getLocalName() + ": Polling timeout reached. Received " + repliesCnt + "/" + otherPersonAgents.size() + " replies.");
                        step = 3; // Fin (même si timeout)
                    } else {
                        block(50); // Attendre un peu avant de revérifier
                    }
                    break;
            }
        }

        @Override
        public boolean done() {
            return step == 3; // Le comportement est terminé quand on atteint l'étape 3
        }
    }

    private class DeliberateBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            status = Status.DELIBERATING;
            System.out.println(getLocalName() + ": Deliberating based on poll results (Current choice: " + currentChoiceRestaurantId + ")");
            System.out.println(getLocalName() + ": Poll results: " + pollResults);

            if (pollResults.isEmpty()) {
                System.out.println(getLocalName() + ": No poll results, keeping current choice: " + currentChoiceRestaurantId);
                return; // Pas de données pour changer d'avis
            }

            // Logique de délibération :
            // Si notre choix actuel est beaucoup plus populaire que les autres,
            // envisager de changer pour un moins populaire mais potentiellement disponible.
            // Ici, une logique TRES simple : si notre choix est le plus populaire ET
            // qu'il y a d'autres options moins populaires, on prend la deuxième moins populaire.

            int myChoicePopularity = pollResults.getOrDefault(currentChoiceRestaurantId, 0);

            // Trouver le choix le plus populaire globalement
            Optional<Map.Entry<String, Integer>> mostPopularEntry = pollResults.entrySet().stream()
                    .max(Map.Entry.comparingByValue());

            if (mostPopularEntry.isPresent() && mostPopularEntry.get().getKey().equals(currentChoiceRestaurantId) && pollResults.size() > 1) {
                // Notre choix est le plus populaire et il existe d'autres choix
                System.out.println(getLocalName() + ": Current choice " + currentChoiceRestaurantId + " is the most polled ("+myChoicePopularity+"). Considering alternatives...");

                // Trouver une alternative moins populaire (ex: la 2ème moins populaire pour éviter le "pire")
                List<Map.Entry<String, Integer>> sortedChoices = pollResults.entrySet().stream()
                        .sorted(Map.Entry.comparingByValue())
                        .collect(Collectors.toList());

                String alternativeChoice = null;
                // Essayer de prendre un choix différent de l'actuel
                for(Map.Entry<String, Integer> entry : sortedChoices) {
                    if (!entry.getKey().equals(currentChoiceRestaurantId)) {
                        alternativeChoice = entry.getKey();
                        break; // Prendre le premier moins populaire différent du nôtre
                    }
                }

                if (alternativeChoice != null) {
                    System.out.println(getLocalName() + ": Switching choice from " + currentChoiceRestaurantId + " to less popular alternative: " + alternativeChoice);
                    currentChoiceRestaurantId = alternativeChoice;
                } else {
                    System.out.println(getLocalName() + ": No suitable alternative found, keeping current choice: " + currentChoiceRestaurantId);
                }

            } else {
                System.out.println(getLocalName() + ": Current choice " + currentChoiceRestaurantId + " is not the most popular or no alternatives. Keeping choice.");
            }
        }
    }

    private class AttemptReservationBehaviour extends Behaviour {
        private int step = 0;
        private MessageTemplate mt;
        private int transitionEvent = 0; // 0 = FAILURE, 1 = CONFIRM
        private String currentConvId;

        @Override
        public void onStart() {
            step = 0;
            status = Status.RESERVING;
            System.out.println(getLocalName() + ": Attempting reservation at chosen restaurant: " + currentChoiceRestaurantId);
        }

        @Override
        public void action() {
            switch (step) {
                case 0: // Envoyer la requête de réservation
                    ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                    request.addReceiver(environmentAgentAID);
                    request.setProtocol(EnvironmentAgent.RESERVATION_PROTOCOL);
                    currentConvId = conversationIdBase + "-res-" + System.currentTimeMillis(); // ID unique
                    request.setConversationId(currentConvId);
                    request.setReplyWith(currentConvId + "-reply"); // Attente de réponse
                    request.setContent(currentChoiceRestaurantId); // Contenu = ID du restaurant

                    myAgent.send(request);
                    System.out.println(getLocalName() + ": Sent reservation request for " + currentChoiceRestaurantId + " to Environment Agent.");

                    // Préparer le template pour la réponse
                    mt = MessageTemplate.and(
                            MessageTemplate.MatchProtocol(EnvironmentAgent.RESERVATION_PROTOCOL),
                            MessageTemplate.and(
                                    MessageTemplate.MatchConversationId(currentConvId),
                                    MessageTemplate.MatchInReplyTo(request.getReplyWith()) // Doit répondre à notre 'reply-with'
                            )
                    );
                    step = 1;
                    break;

                case 1: // Recevoir la réponse (CONFIRM ou FAILURE)
                    ACLMessage reply = myAgent.receive(mt);
                    if (reply != null) {
                        if (reply.getPerformative() == ACLMessage.CONFIRM) {
                            System.out.println(getLocalName() + ": Reservation CONFIRMED for " + currentChoiceRestaurantId + "!");
                            status = Status.SEATED;
                            transitionEvent = 1; // Succès
                        } else if (reply.getPerformative() == ACLMessage.FAILURE) {
                            String reason = reply.getContent();
                            System.out.println(getLocalName() + ": Reservation FAILED for " + currentChoiceRestaurantId + ". Reason: " + reason);
                            status = Status.READY; // Prêt à réessayer ou échouer
                            transitionEvent = 0; // Échec
                        } else {
                            System.out.println(getLocalName() + ": Received unexpected message type: " + ACLMessage.getPerformative(reply.getPerformative()));
                            status = Status.READY;
                            transitionEvent = 0; // Considérer comme échec
                        }
                        step = 2; // Terminer ce comportement
                    } else {
                        block(100); // Attendre la réponse
                    }
                    break;
            }
        }

        @Override
        public boolean done() {
            return step == 2;
        }

        @Override
        public int onEnd() {
            return transitionEvent; // Déclenche la bonne transition dans la FSM
        }
    }

    private class HandleFailureBehaviour extends OneShotBehaviour {
        private int transitionEvent = 0; // 0 = Max retries, 1 = Retry possible
        @Override
        public void action() {
            retryCount++;
            System.out.println(getLocalName() + ": Handling reservation failure. Retry attempt " + retryCount + "/" + MAX_RETRIES);
            if (retryCount >= MAX_RETRIES) {
                System.out.println(getLocalName() + ": Max retries reached. Giving up.");
                status = Status.FAILED;
                transitionEvent = 0; // Échec final
            } else {
                System.out.println(getLocalName() + ": Will attempt another choice.");
                // currentChoiceRestaurantId est déjà marqué comme celui qui a échoué
                status = Status.READY; // Prêt pour un nouveau cycle
                transitionEvent = 1; // Tenter à nouveau
                // Ajouter un petit délai aléatoire pour désynchroniser les agents
                block(new Random().nextInt(100) + 50);
            }
        }
        @Override
        public int onEnd() {
            return transitionEvent;
        }
    }

    // États finaux (simples OneShotBehaviours)
    private class SeatedBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            status = Status.SEATED;
            System.out.println(">>>>> " + getLocalName() + " is successfully SEATED at " + currentChoiceRestaurantId + "! <<<<<");
            // L'agent pourrait s'arrêter ici s'il n'a plus rien à faire
            // myAgent.doDelete(); // Décommenter pour arrêter l'agent une fois assis
        }
    }

    private class FailedBehaviour extends OneShotBehaviour {
        @Override
        public void action() {
            status = Status.FAILED;
            System.err.println("<<<<< " + getLocalName() + " FAILED to find a seat after " + retryCount + " retries. >>>>>");
            // L'agent pourrait s'arrêter ici
            // myAgent.doDelete(); // Décommenter pour arrêter l'agent en cas d'échec
        }
    }

    // --- Comportement pour répondre aux sondages ---
    private class PollResponderBehaviour extends CyclicBehaviour {
        private final MessageTemplate template = MessageTemplate.and(
                MessageTemplate.MatchPerformative(ACLMessage.QUERY_REF), // Écoute les QUERY_REF
                MessageTemplate.MatchProtocol(POLLING_PROTOCOL)         // Avec le bon protocole
        );

        @Override
        public void action() {
            ACLMessage msg = myAgent.receive(template);
            if (msg != null) {
                // System.out.println(getLocalName() + ": Received poll request from " + msg.getSender().getLocalName());
                ACLMessage reply = msg.createReply();
                reply.setPerformative(ACLMessage.INFORM); // Répondre avec INFORM
                reply.setProtocol(POLLING_PROTOCOL);
                // Envoyer l'ID du restaurant actuellement choisi (peut être null si pas encore choisi)
                reply.setContent(currentChoiceRestaurantId != null ? currentChoiceRestaurantId : "null");
                myAgent.send(reply);
                // System.out.println(getLocalName() + ": Sent poll reply '" + reply.getContent() + "' to " + msg.getSender().getLocalName());
            } else {
                block(); // Attendre le prochain message
            }
        }
    }
}