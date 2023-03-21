package bguspl.set.ex;

import java.util.concurrent.BlockingQueue;
import java.util.logging.Level;

import bguspl.set.Env;


import java.util.concurrent.ArrayBlockingQueue;
import java.util.List;
import java.util.ArrayList;
import java.util.Random;

/**
 * This class manages the players' threads and data
 *
 * @inv id >= 0
 * @inv score >= 0
 */
public class Player implements Runnable {

    /**
     * The game environment object.
     */
    private final Env env;

    /**
     * Game entities.
     */
    private final Table table;

    /**
     * The id of the player (starting from 0).
     */
    public final int id;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated due to an external event.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;


    ////////////////////////////// ADDED FIELDS
    public static final int SET_SIZE = 3; // represents the size of a set

    protected volatile List<Integer> tokenList; // list that holds the slots that the player placed tokens on

    private BlockingQueue<Integer> keyPQueue; //  represents the keypress queue

    private volatile boolean isFrozen; // true when player is in timeout from point or penalty

    private volatile boolean pointScored; // true when player got a point changed by the dealer.

    private volatile  boolean hasBeenChecked; // true if dealer checked the player's set already

    private Dealer dealer;
    ///////////////////////////////
    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided manually, via the keyboard).
     */
    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        //intializing added fields

        tokenList = new ArrayList<>(SET_SIZE);
        keyPQueue = new ArrayBlockingQueue<>(SET_SIZE);
        isFrozen = false;
        pointScored = false;
        this.dealer = dealer;
        hasBeenChecked = false;
    }

    /**
     * The main player thread of each player starts here (main loop for the player thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + "starting.");
        if (!human) createArtificialIntelligence();

        while (!terminate) {
            int slot = -1;
            try {
                slot = keyPQueue.take(); // take head of queue (first element)

            } catch (InterruptedException ignored) {continue;} // used to exit loop when terminate is called
            // checking slot condition and if tokenList has reached SET_SIZE
            if (!playerThread.isInterrupted() && operateSlot(slot) && tokenList.size() == SET_SIZE) {
                dealer.notifyDealer(id); // add players id to dealers queue, notify dealer and increase numOfSets
                try { synchronized (this) {
                    if (!hasBeenChecked) // if the player's set has already been checked by the dealer, don't enter wait, otherwise, wait
                        this.wait();
                    hasBeenChecked = false;
                } } catch(InterruptedException ignored) {}
                if (pointScored) // if true, the player's set is legal
                    point();
                else // otherwise, penalty
                    penalty();
            }
        }
        if (!human) try { // if player is computer, end ai thread first by interrupting it and joining it
            aiThread.interrupt();
            aiThread.join();
        } catch (InterruptedException ignored) {}

        env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {
                int slot = generateKeyPress(); // generate random slot
                if (table.slotToCard(slot) == null) // if there isn't a card in the selected slot, generate new one
                    continue;
                keyPressed(slot); // initiate key press with selected slot

            }
            env.logger.log(Level.INFO, "Thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated due to an external event.
     */
    public void terminate()
    {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        // if the function call is not between removing all the cards from the table
        // and placing new ones for the next round in order to not place cards in this stage
        if (!dealer.roundStartOrEnd()) {
            // if player is not frozen due to point or penalty
            if (!isFrozen) {
                try {
                    keyPQueue.put(slot); // add the slot to the key press queue
                } catch (InterruptedException ignored) {}
            }
        }
    }

    /**
     * Award a point to a player and perform other related actions.
     *
     * @post - the player's score is increased by 1.
     * @post - the player's score is updated in the ui.
     */
    public void point() {
            try {
                isFrozen = true; // true to prevent human/ai from entering keys to the key press queue when it's not valid
                env.ui.setScore(id, ++score); // give point in the table
                // update the remaining time in seconds until freeze is over
                long overallTime = System.currentTimeMillis() +env.config.pointFreezeMillis;
                // while the remaining freeze time is more than a sec, update freeze time on ui and sleep
                while (overallTime - System.currentTimeMillis() >= 1000){
                    env.ui.setFreeze(id, overallTime - System.currentTimeMillis());
                    Thread.sleep(900);
                }
                env.ui.setFreeze(id, 0); // end freeze for the player in the ui
                isFrozen = false; // player can now continue press keys
                pointScored = false; // reset for next time
            } catch (InterruptedException ignored) {}
        int ignored = table.countCards(); // this part is just for demonstration in the unit tests
    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {
        try {
            isFrozen = true; // true to prevent human/ai from entering keys to the key press queue when it's not valid
            // update the remaining time in seconds until freeze is over
            long startTime = System.currentTimeMillis() + env.config.penaltyFreezeMillis;
            // while the remaining freeze time is more than a sec, update freeze time on ui and sleep
            while (startTime - System.currentTimeMillis() >= 1000){
                env.ui.setFreeze(id, startTime - System.currentTimeMillis());
                Thread.sleep(900);
            }
            env.ui.setFreeze(id, 0); // end freeze for the player in the ui
            isFrozen = false; // player can now continue press keys
        } catch (InterruptedException ignored) {}

    }

    public int score() {
        return score;
    }

    /**
     * Places or removes players token based on tokenList entries, and performs other actions.
     *
     * @post - if slot in tokenList, remove slot from tokenList and remove token from slot.
     * @post - if slot not in tokenList, add slot to tokenList and place token on slot.
     */
    protected boolean operateSlot(int slot) {
        if (dealer.isLegalSetWasFound()) // wait for dealer to remove legal set that was found and place new cards
            synchronized (table) { try { table.wait(); } catch(InterruptedException ignored) {} }
        synchronized (this) {
            if (tokenList.contains(slot)) {
                // if slot in tokenList, remove the slot from there and remove players token from the slot
                tokenList.remove(Integer.valueOf(slot));
                table.removeToken(id, slot);
            } else if (table.slotToCard(slot) != null && !tokenList.contains(slot) && tokenList.size() < 3) {
                // otherwise, if slot is not in tokenList and slot is valid (null) and tokenList is not full,
                // add slot to tokenList and place token in slot
                tokenList.add(slot);
                table.placeToken(id, slot);
            }
            else
                return false;
            return true;
        }
    }


    public List<Integer> getTokenList() { //will be used so the dealer gets our set through the id
            synchronized (this) {return tokenList; }
        }

    private int generateKeyPress() {
        // pick random row and column and according to the formula provided in project description, calculate random slot
        Random rand = new Random();
        int randomRow = rand.nextInt(env.config.rows);
        int randomCol = rand.nextInt(env.config.columns);
        return (randomCol+env.config.columns*randomRow);
    }


    public int getId() {
        return id;
    }

    public void givePoint() {
        pointScored = true;
    } // will be used by dealer to give point to player

    public synchronized void notifyPlayer(){
        // will be used by the dealer to notify the player after his set was checked
        hasBeenChecked = true;
        notifyAll();
    }
    //////////////////////////////

}
