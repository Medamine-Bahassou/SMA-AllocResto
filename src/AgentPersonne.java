import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.OneShotBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap; // Use thread-safe map for static intentions

public class AgentPersonne extends Agent {

    // --- Shared Intentions (Static Map - Justification: Project simplification "ask everyone") ---
    // Using ConcurrentHashMap for basic thread safety, although JADE behaviours usually run sequentially per agent.
    private static Map<String, String> currentIntentions = new ConcurrentHashMap<>();
    // -----------------------------------------------------------------------------------------

    private String[] availableRestaurants;
    private int pollSizeP;
    private Random random = new Random();
    private int myReservationAttempts = 0; // Track attempts for THIS agent
    private String finalChoice = null; // Store the final successful choice

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length == 2 && args[0] instanceof String[] && args[1] instanceof Integer) {
            availableRestaurants = (String[]) args[0];
            pollSizeP = (int) args[1];
            System.out.println("Person Agent " + getLocalName() + " started. Knows " + availableRestaurants.length + " restaurants. Poll size P=" + pollSizeP);

            // Start the decision and reservation process
            addBehaviour(new DeliberationAndReservationBehaviour());

        } else {
            System.err.println("Person Agent " + getLocalName() + " requires 2 arguments: availableRestaurants (String[]), pollSizeP (int)");
            doDelete();
        }
    }

    private class DeliberationAndReservationBehaviour extends OneShotBehaviour {

        @Override
        public void action() {
            if (availableRestaurants == null || availableRestaurants.length == 0) {
                System.err.println(getLocalName() + ": No restaurants available to choose from.");
                return; // Cannot proceed
            }

            // === Step 1: Initial Choice ===
            String currentChoice = availableRestaurants[random.nextInt(availableRestaurants.length)];
            System.out.println(getLocalName() + ": Initial choice is " + currentChoice);

            // Publish initial intention (using the static map as per project simplification)
            currentIntentions.put(getLocalName(), currentChoice);


            // === Step 2: Poll & Deliberate (using the static map) ===
            // Get a snapshot of intentions FROM OTHER AGENTS at this moment
            Map<String, String> otherIntentions = new HashMap<>(currentIntentions);
            otherIntentions.remove(getLocalName()); // Don't poll self

            // Count choices among others
            Map<String, Integer> choiceCounts = new HashMap<>();
            for (String restaurant : availableRestaurants) {
                choiceCounts.put(restaurant, 0);
            }
            for (String intendedRestaurant : otherIntentions.values()) {
                // Ensure the intended restaurant is still in our known list (it should be)
                if(choiceCounts.containsKey(intendedRestaurant)) {
                    choiceCounts.put(intendedRestaurant, choiceCounts.get(intendedRestaurant) + 1);
                }
            }
            System.out.println(getLocalName() + ": Polled intentions (counts): " + choiceCounts);


            // === Step 3: Adjust Choice (Choose least popular based on poll) ===
            String adjustedChoice = currentChoice;
            int minCount = Integer.MAX_VALUE; // Initialize with a high value

            // Find the minimum count among all restaurants
            for (Integer count : choiceCounts.values()){
                if(count < minCount){
                    minCount = count;
                }
            }
            // Collect all restaurants having the minimum count
            List<String> leastPopularOptions = new ArrayList<>();
            for(Map.Entry<String, Integer> entry : choiceCounts.entrySet()){
                if(entry.getValue() == minCount){
                    leastPopularOptions.add(entry.getKey());
                }
            }

            // If there are multiple least popular, pick one randomly among them
            if(!leastPopularOptions.isEmpty()){
                adjustedChoice = leastPopularOptions.get(random.nextInt(leastPopularOptions.size()));
            } // else stick to original random choice if something went wrong

            System.out.println(getLocalName() + ": Adjusted choice (least popular) is " + adjustedChoice);

            // Update own intention map (others might poll this updated choice)
            currentIntentions.put(getLocalName(), adjustedChoice);
            currentChoice = adjustedChoice; // Use the adjusted choice for reservation attempts


            // === Step 4: Attempt Reservation (Loop until success) ===
            boolean reserved = false;
            long timeLimit = System.currentTimeMillis() + 30000; // Add a timeout (e.g., 30 seconds) to prevent infinite loops

            while (!reserved && System.currentTimeMillis() < timeLimit) {
                System.out.println(getLocalName() + ": Attempting to reserve at " + currentChoice);
                ACLMessage request = new ACLMessage(ACLMessage.REQUEST);
                request.addReceiver(new AID(currentChoice, AID.ISLOCALNAME));
                request.setContent("Reservation request");
                request.setConversationId("dinner-reservation");
                request.setReplyWith("req" + System.currentTimeMillis()); // Unique reply identifier

                myAgent.send(request);
                myReservationAttempts++; // Increment attempt counter HERE

                // Wait for the reply
                MessageTemplate replyTemplate = MessageTemplate.and(
                        MessageTemplate.MatchConversationId("dinner-reservation"),
                        MessageTemplate.MatchInReplyTo(request.getReplyWith()));

                // Use blockingReceive with timeout
                ACLMessage reply = myAgent.blockingReceive(replyTemplate, 2000); // Wait 2 seconds for a reply

                if (reply != null) {
                    if (reply.getPerformative() == ACLMessage.CONFIRM) {
                        System.out.println("******************************************************");
                        System.out.println(getLocalName() + ": SUCCESS! Reserved at " + currentChoice + ".");
                        System.out.println(getLocalName() + ": Total reservation attempts: " + myReservationAttempts);
                        System.out.println("******************************************************");
                        reserved = true;
                        finalChoice = currentChoice; // Store final success
                        // Optional: Update intention map one last time to show final placement?
                        currentIntentions.put(getLocalName(), currentChoice + " (RESERVED)");
                    } else { // REFUSE or other failure
                        System.out.println(getLocalName() + ": FAILED at " + currentChoice + ". Reason: " + reply.getContent());
                        // Choose a different restaurant randomly for the next attempt
                        String previousChoice = currentChoice;
                        // Ensure new choice is different from the failed one, if possible
                        if (availableRestaurants.length > 1) {
                            do {
                                currentChoice = availableRestaurants[random.nextInt(availableRestaurants.length)];
                            } while (currentChoice.equals(previousChoice));
                        } else {
                            // Only one restaurant, keep trying (or maybe give up after N attempts?)
                            System.out.println(getLocalName() + ": Only one restaurant option, retrying at " + currentChoice);
                            // Add a small delay before retrying the same one?
                            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt();}
                        }
                        System.out.println(getLocalName() + ": Trying next: " + currentChoice);
                        // Update intention map with the new target for polling by others
                        currentIntentions.put(getLocalName(), currentChoice);
                    }
                } else {
                    System.out.println(getLocalName() + ": FAILED at " + currentChoice + ". No reply received (Timeout).");
                    // Decide what to do on timeout: retry same, pick new, give up?
                    // Let's pick a new random one for robustness
                    String previousChoice = currentChoice;
                    if (availableRestaurants.length > 1) {
                        do {
                            currentChoice = availableRestaurants[random.nextInt(availableRestaurants.length)];
                        } while (currentChoice.equals(previousChoice));
                    } else {
                        System.out.println(getLocalName() + ": Only one restaurant option, retrying at " + currentChoice);
                        try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt();}
                    }
                    System.out.println(getLocalName() + ": Trying next after timeout: " + currentChoice);
                    currentIntentions.put(getLocalName(), currentChoice);
                }
            } // End reservation loop

            if (!reserved) {
                System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                System.out.println(getLocalName() + ": FAILED to reserve any restaurant after " + myReservationAttempts + " attempts and timeout.");
                System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                currentIntentions.put(getLocalName(), "FAILED_TO_RESERVE");
            }

            // Agent's main task is done after this behaviour completes.
        } // End action()
    } // End DeliberationAndReservationBehaviour

    @Override
    protected void takeDown() {
        // Clean up static map entry when agent terminates
        currentIntentions.remove(getLocalName());
        System.out.println("Person Agent " + getLocalName() + " terminating. Final status: " + (finalChoice != null ? "Reserved at " + finalChoice : "Failed reservation") + ". Attempts: " + myReservationAttempts);
    }
}