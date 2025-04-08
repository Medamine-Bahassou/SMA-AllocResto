import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

import java.util.Random;

public class AgentRestaurant extends Agent {
    private int capaciteInitiale;
    private int placesRestantes;
    private double saturationProbability; // Chance to refuse even if places are available
    private Random random = new Random();

    protected void setup() {
        Object[] args = getArguments();
        if (args != null && args.length == 2) {
            capaciteInitiale = (int) args[0];
            saturationProbability = (double) args[1];
            placesRestantes = capaciteInitiale;
            System.out.println("Restaurant Agent " + getLocalName() + " started. Capacity: " + capaciteInitiale + ", Saturation Prob: " + saturationProbability);

            addBehaviour(new RestaurantBehaviour());

        } else {
            System.err.println("Restaurant Agent " + getLocalName() + " requires 2 arguments: capacity (int), saturationProbability (double)");
            doDelete(); // Self-terminate if arguments are wrong
        }
    }

    private class RestaurantBehaviour extends CyclicBehaviour {
        // Template to only receive REQUEST messages for reservation
        private MessageTemplate template = MessageTemplate.MatchPerformative(ACLMessage.REQUEST);

        public void action() {
            ACLMessage msg = myAgent.receive(template);
            if (msg != null) {
                System.out.println(getLocalName() + ": Received reservation request from " + msg.getSender().getLocalName());
                ACLMessage reply = msg.createReply();

                // Decision Logic:
                // 1. Check if places are available
                // 2. Check against random saturation probability
                if (placesRestantes > 0) {
                    if (random.nextDouble() > saturationProbability) {
                        // Accept reservation
                        placesRestantes--;
                        reply.setPerformative(ACLMessage.CONFIRM);
                        reply.setContent("Reservation confirmed. Places left: " + placesRestantes);
                        System.out.println(getLocalName() + ": CONFIRMED reservation for " + msg.getSender().getLocalName() + ". Places left: " + placesRestantes);
                    } else {
                        // Refuse due to saturation probability
                        reply.setPerformative(ACLMessage.REFUSE);
                        reply.setContent("Restaurant saturated (random chance).");
                        System.out.println(getLocalName() + ": REFUSED (Saturated) reservation for " + msg.getSender().getLocalName() + ". Places available: " + placesRestantes);
                    }
                } else {
                    // Refuse because full
                    reply.setPerformative(ACLMessage.REFUSE);
                    reply.setContent("Restaurant completely full.");
                    System.out.println(getLocalName() + ": REFUSED (Full) reservation for " + msg.getSender().getLocalName());
                }
                myAgent.send(reply);
            } else {
                block(); // Wait for messages efficiently
            }
        }
    } // End RestaurantBehaviour

    @Override
    protected void takeDown() {
        System.out.println("Restaurant Agent " + getLocalName() + " terminating.");
    }
}