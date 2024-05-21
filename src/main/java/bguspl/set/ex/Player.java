package bguspl.set.ex;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;

import bguspl.set.Env;

import java.util.LinkedList;
import java.util.Queue;

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

    private final Dealer dealer;

    /**
     * The thread representing the current player.
     */
    private Thread playerThread;

    /**
     * The thread of the AI (computer) player (an additional thread used to generate
     * key presses).
     */
    private Thread aiThread;

    /**
     * True iff the player is human (not a computer player).
     */
    private final boolean human;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The current score of the player.
     */
    private int score;

    private boolean inPenalty;
    private boolean getPoint;
    private boolean checkMe;
    private boolean checked;

    private final long AI_SLEEP_TIME = 50;
    private final long ONE_SECOND = 1000;
    private final long RESET_FREEZE = -100;

    BlockingQueue<Integer> q;

    /**
     * The class constructor.
     *
     * @param env    - the environment object.
     * @param dealer - the dealer object.
     * @param table  - the table object.
     * @param id     - the id of the player.
     * @param human  - true iff the player is a human player (i.e. input is provided
     *               manually, via the keyboard).
     */

    public Player(Env env, Dealer dealer, Table table, int id, boolean human) {
        this.env = env;
        this.table = table;
        this.id = id;
        this.human = human;
        this.dealer = dealer;
        this.score = 0;
        this.inPenalty = false;
        this.getPoint = false;
        this.checkMe = false;
        this.checked = false;
        this.q = new ArrayBlockingQueue<>(env.config.featureSize);

    }

    /**
     * The main player thread of each player starts here (main loop for the player
     * thread).
     */
    @Override
    public void run() {
        playerThread = Thread.currentThread();
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        if (!human)
            createArtificialIntelligence();

        while (!terminate) {
            synchronized (this) {
                if (checkMe) {
                    while (!checked) {
                        try {
                            wait();
                        } catch (InterruptedException ignored) {
                        }
                    }
                    if (inPenalty) {
                        penalty();
                    } else if (getPoint) {
                        point();
                    }
                    checkMe = false;
                }
            }

        }
        if (!human)
            try {
                aiThread.join();
            } catch (InterruptedException ignored) {
            }
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * Creates an additional thread for an AI (computer) player. The main loop of
     * this thread repeatedly generates
     * key presses. If the queue of key presses is full, the thread waits until it
     * is not full.
     */
    private void createArtificialIntelligence() {
        // note: this is a very, very smart AI (!)
        aiThread = new Thread(() -> {
            env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
            while (!terminate) {

                int table = env.config.tableSize;
                int aiSlot = (int) (Math.random() * table);
                keyPressed(aiSlot);
                try {
                    Thread.sleep(AI_SLEEP_TIME);

                } catch (InterruptedException ignored) {
                }
            }

            env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
        }, "computer-" + id);
        aiThread.start();
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
    }

    /**
     * This method is called when a key is pressed.
     *
     * @param slot - the slot corresponding to the key pressed.
     */
    public void keyPressed(int slot) {
        if (dealer.canPlaceToken) {
            if (!inPenalty && !checkMe) {
                synchronized (this) {
                    if (!inPenalty && !checkMe) {
                        if (table.removeToken(this.id, slot) == true || q.contains(slot)) {
                            q.remove(slot);

                        } else {
                            if (q.size() < env.config.featureSize) {
                                if (table.slotToCard[slot] != null) {
                                    table.placeToken(this.id, slot);
                                    q.add(slot);
                                }
                            }
                        }
                    }
                    if (q.size() == env.config.featureSize && !checkMe) {
                        checkMe = true;
                        checked = false;
                        dealer.addPlayerToCheck(this);
                        dealer.getDelaerThread().interrupt();
                    }

                }
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
        // long pt = env.config.pointFreezeMillis + System.currentTimeMillis();
        // env.ui.setFreeze(id, env.config.pointFreezeMillis);
        // while (pt - System.currentTimeMillis() >= ONE_SECOND) {
        // env.ui.setFreeze(id, pt - System.currentTimeMillis());

        // }
        env.ui.setFreeze(id, env.config.pointFreezeMillis);
        try {
            Thread.sleep(env.config.pointFreezeMillis);
        } catch (InterruptedException e) {
        }
        env.ui.setFreeze(id, RESET_FREEZE);
        q.clear();

        int ignored = table.countCards(); // this part is just for demonstration in
        // the unit tests
        env.ui.setScore(id, ++score);
        getPoint = false;

    }

    /**
     * Penalize a player and perform other related actions.
     */
    public void penalty() {

        long pt = env.config.penaltyFreezeMillis + System.currentTimeMillis();
        while (pt - System.currentTimeMillis() >= ONE_SECOND) {
            env.ui.setFreeze(id, pt - System.currentTimeMillis());
            try {
                Thread.sleep(Math.max(0, (int) (ONE_SECOND - 100)));
            } catch (InterruptedException e) {
            }

        }
        inPenalty = false;
        env.ui.setFreeze(id, RESET_FREEZE);

    }

    public int score() {
        return score;
    }

    public void setInPenalty() {
        inPenalty = true;
    }

    public void setGetPoint() {
        getPoint = true;
    }

    public void setCheckMe() {
        checkMe = true;
    }

    public void setNotCheckMe() {
        checkMe = false;
    }

    public void setAsChecked() {
        checked = true;
    }

    public Thread getPlayerThread() {
        return playerThread;
    }

}
