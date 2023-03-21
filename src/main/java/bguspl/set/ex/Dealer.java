package bguspl.set.ex;

import bguspl.set.Env;


import java.util.*;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.concurrent.ConcurrentLinkedQueue;

/**
 * This class manages the dealer's threads and data
 */
public class Dealer implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;
    private final Player[] players;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    protected final List<Integer> deck;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long futureReshuffleTime;


    ////////////////////ADDED FIELDS

    private volatile Queue<Integer> idQueue; // queue that represents the players that added a set by their id
    private Thread[] playerThreads; // array of all the players threads, used to close the game gracefully

    protected volatile boolean legalSetWasFound; // true when a legal set was found, used to block players from pressing keys while removing set cards
    private volatile boolean isRoundStartingOrEnding; // true if the state of game is between end of round and start of a new one
                                                        // used to block players from placing tokens when it's not valid
    private List<Integer> slotsToFill; // slots of the removed legal set to place new cards on them

    private boolean isItASetReset; // true if a legal set was found and a reset of the timer is needed

    long remainingTime = -1; // will be used to represent the remaining time of the timer

    private volatile int numOfSets; // number of sets that were sumbited to the dealer to check, will be used to know if dealer should wait

    Thread dealerThread; // thread of dealer, used in termination

    ////////////////////

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        //reshuffleTime = env.config.turnTimeoutMillis;
        idQueue = new ConcurrentLinkedQueue<>();
        playerThreads = new Thread[players.length];
        legalSetWasFound = false;
        slotsToFill = new ArrayList<>();
        futureReshuffleTime = -1;
        isItASetReset = false;
        isRoundStartingOrEnding = false;
        numOfSets = 0;
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
        dealerThread = Thread.currentThread();
        isRoundStartingOrEnding = true; // round is currently in a state where players cannot place tokens
        for (int i = 0; i < players.length; i++) { // start all the player's threads
            playerThreads[i] = new Thread(players[i]);
            playerThreads[i].start();
        }

        while (!shouldFinish()) {
            placeCardsOnTable(); // place cards depending on state
            timerLoop(); // loop for each round
            updateTimerDisplay(true); // update and reset timer for next round
            removeAllCardsFromTable(); // remove all the cards from the table in before next round is starting
        }
        announceWinners(); // change ui to present the winners
        for (int i = playerThreads.length - 1; i >= 0; i--) { // used for bonus section to close the players first and then the dealer
            players[i].terminate();
            playerThreads[i].interrupt();
            try {
                playerThreads[i].join();
            } catch (InterruptedException ignored) {
            }
        }
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did not time out.
     */
    private void timerLoop() {
        // save the future time of when the round should end, used to calculate the time left in the current round
        futureReshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        // while X button was not pressed and the round is not over
        while (!terminate && System.currentTimeMillis() < futureReshuffleTime) {
            if (isItASetReset) // true if the round should be over due to legal set that was found, reset the time for next round
                futureReshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            remainingTime = futureReshuffleTime - System.currentTimeMillis(); // calculate countdown of this round
            sleepUntilWokenOrTimeout(); // sleep if no tasks should be done
            updateTimerDisplay(false); // update timer, without reset
            removeCardsFromTable(); // remove cards from table if a set was found
            placeCardsOnTable(); // place new cards instead of the removed legal set
        }
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate() {
        for (int i = playerThreads.length - 1; i >= 0; i--) { // terminate all the players first, before terminating the dealer
            players[i].terminate();
            playerThreads[i].interrupt();
            try {
                playerThreads[i].join();
            } catch (InterruptedException ignored) {}
        }
        terminate = true; // terminate dealer
        dealerThread.interrupt();
        try {
            dealerThread.join();
        } catch (InterruptedException ignored) {}
    }



    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }
    // indicates if the game should be close due to pressing the X button or having no more sets

    /**
     * Checks cards should be removed from the table and removes them.
     */
    private void removeCardsFromTable() {
        if (!idQueue.isEmpty()) {
            int firstCandidate = idQueue.poll(); // pull out the first player to check his set
            synchronized (this) {numOfSets--;} // decrease number of sets waiting to be checked
            // copy the token list of the player we are checking right now
                List<Integer> slotSet = new ArrayList<>(players[firstCandidate].getTokenList());
            // create a card array to hold the cards that the player has tokens on
                int[] cards = new int[slotSet.size()];
                for (int i = 0; i < slotSet.size(); i++) { // for each slot that the player had a token on, transform it to the card on this slot
                                                            // and insert to the card array
                    Integer card = table.slotToCard(slotSet.get(i));
                    if (card != null)
                        cards[i] = card;
                }
                // used to validate that the set is legal, contains SET_SIZE number of cards and is legal
                if (cards.length == Player.SET_SIZE && env.util.testSet(cards)) {
                    slotsToFill = new ArrayList<>(slotSet); // initialize slots that needs to be filled with new cards
                    legalSetWasFound = true; // indicate that a set was found
                    updateTimerDisplay(true); // update the timer and reset it due to set that was found legal
                    isItASetReset = true; // indicate that a reset is being done due to set that was found legal
                    removeLegalSet(slotSet);
                    removeIntersectingElementsFromAll(slotSet); // remove from all the player's token lists the slots that were removed now
                    players[firstCandidate].givePoint(); // give point to player
                }
                players[firstCandidate].notifyPlayer(); // notify the player to end his wait. wether got a point or penalty
        }
    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     *
     * @post - cardsToAdd number of cards were placed in table and removed from deck.
     */
    protected void placeCardsOnTable() {
        Collections.shuffle(deck);
        int rows = env.config.rows, cols = env.config.columns;
        int cardsToAdd = (rows*cols) - table.countCards(); // calculates the number of cards that needs to be added to the table
        if(cardsToAdd > deck.size()) // if the deck contains less cards that the number that needs to be added,
                                        // add the entire deck, because that is what is left in the game
            cardsToAdd = deck.size();
        if (legalSetWasFound) { // if we add cards due to removing a legal set
            for (int i = 0; i < cardsToAdd; i++) { // remove the cards from the deck and place on the table
                table.placeCard(deck.remove(0), slotsToFill.get(i));
            }
            legalSetWasFound = false; // reset flag for next time
            synchronized (table) { table.notifyAll(); } // notify all the players that tried to place a token while replacing cards
        }
        else { // we get here if we place cards due to round start
            List<Integer> slots = IntStream.range(0, cardsToAdd).boxed().collect(Collectors.toList());
            Collections.shuffle(slots);
            for (int i = 0 ; i < cardsToAdd ; i++) { // remove the cards from the deck and place on the table
                table.placeCard(deck.remove(0), slots.get(i));
            }
            isRoundStartingOrEnding = false; // players can now start placing tokens
        }
    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        if (remainingTime > (env.config.turnTimeoutWarningMillis)) { // if there is more that warning seconds to the round
            try {
                synchronized (this) {
                    if (numOfSets == 0) { // if there are no sets to check, enter wait, otherwise, do not wait, something needs to be done
                        this.wait(1000); // wait for at most a second to update timer
                    }
                }
            } catch (InterruptedException ignored) {}
        }
        else {
            try {
            synchronized (this) {wait(3);}} catch (InterruptedException ignored) {} // if time is less that warning seconds,
                                                                                                // wait for a very short time to update milliseconds
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        if(!reset) { // if no reset needs to be done
            if (remainingTime > (env.config.turnTimeoutWarningMillis)) // if there is more that warning seconds to the round,
                                                                        // round the excess seconds to keep the time clean
                remainingTime = Math.round(remainingTime / 1000.0) * 1000;
            env.ui.setCountdown(remainingTime, remainingTime <= (env.config.turnTimeoutWarningMillis)); // update timer in ui according to
                                                                                                            // remaining time
        }
        else { // if a reset needs to be done, calculate the end of the next round
            futureReshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
        }
        isItASetReset = false; // reset the flag for next time
    }

    /**
     * Returns all the cards from the table to the deck.
     *
     * @post - all cards were removed from table.
     * @post - all tokens were removed from table.
     * @post - all cards from table added to deck.
     */
    protected void removeAllCardsFromTable() {
        isRoundStartingOrEnding = true; // block players from placing tokens while ending the round
        int allSlots = env.config.rows * env.config.columns; // number of slots on the table
        List<Integer> slots = IntStream.range(0, allSlots).boxed().collect(Collectors.toList());
        Collections.shuffle(slots);
        for (int i = 0; i < allSlots; i++) { // for every slot that was picked randomly, remove the card from it and the tokens from all players
                                            // and return the card to the deck
            Integer card = table.slotToCard(slots.get(i));
            if (card!= null) {
                table.removeCard(slots.get(i));
                table.removeTokens(slots.get(i));
                deck.add(card);
            }
        }
        while(!idQueue.isEmpty()) { // if there are players waiting to be checked by the dealer, notify them without giving point or penalty
                                    // because the round is ending
            int playerId = idQueue.poll();
            synchronized (players[playerId]) {
                players[playerId].notifyAll();
                players[playerId].getTokenList().clear();
            }
        }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        // sort all the players by score.
        // iterate from the player with the highest score and save all the players with the highest score the present as winners
        Player[] sortedPlayers = Arrays.copyOf(players, players.length);
        Arrays.sort(sortedPlayers, Comparator.comparing(Player::score));
        List<Integer> winnerIds = new ArrayList<>();
        int max = sortedPlayers[sortedPlayers.length - 1].score();
        for (int i = sortedPlayers.length - 1; i >= 0 && sortedPlayers[i].score() == max; i--)
            winnerIds.add(sortedPlayers[i].getId());
        env.ui.announceWinner(winnerIds.stream().mapToInt(i -> i).toArray());
    }


    ///////////////////////////////
    public synchronized void notifyDealer(int id) { // used by the player who wants to notify that he formed a set
                                                    // add one to number of sets to be checked, add id to queue and notify
        numOfSets++;
        idQueue.add(id);
        notify();
    }


    private void removeLegalSet (List<Integer> slotsList) { // after finding that a set is legal, remove the cards and all the tokens from all
                                                                // players
        int[] slots = slotsList.stream().mapToInt(i -> i).toArray();
        for (int i = 0; i < slots.length; i++) {
            table.removeCard(slots[i]);
            table.removeTokens(slots[i]);
        }
    }


    private void removeIntersectingElementsFromAll(List<Integer> legalSetSlots) { //function iterates through all the players
                                                                        // slots lists and removes intersecting slots(cards)
        for (int i = 0; i < players.length; i++) {
            synchronized (players[i]) {
                List<Integer> currTokenList = players[i].getTokenList();
                currTokenList.removeAll(legalSetSlots); // removed all the elements in currTokenList that are present in legalSetSlots
                // from all the players that sumbitted a set to be checked,
                // if a player has now less than 3 tokens, remove him from the queue and notify without giving point or penalty
                if (idQueue.contains(players[i].getId()) && players[i].getTokenList().size() != Player.SET_SIZE) {
                    idQueue.remove(players[i].getId());
                    players[i].notifyAll();
                }
            }
        }
    }

    public boolean isLegalSetWasFound() {
        return legalSetWasFound;
    } // used by the player

    public boolean roundStartOrEnd() {
        return isRoundStartingOrEnding;
    } // used by the player

    //////////////////////////////
}
