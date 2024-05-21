package bguspl.set.ex;

import bguspl.set.Env;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import java.util.LinkedList;
import java.util.Queue;

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

    public BlockingQueue<Player> playersToCheck;

    /**
     * The list of card ids that are left in the dealer's deck.
     */
    private final List<Integer> deck;

    /**
     * True iff game should be terminated.
     */
    private volatile boolean terminate;

    /**
     * The time when the dealer needs to reshuffle the deck due to turn timeout.
     */
    private long reshuffleTime = Long.MAX_VALUE;

    private Thread dealerThread;

    private boolean show = true;

    public boolean canPlaceToken = false;
    private boolean under5Seconds = false;

    private Queue<Integer> slotsToRemove;
    private final long THREAD_SLEEP_TIME = 400;
    private final long ALMOST_ONE_SECOND = 999;

    public Dealer(Env env, Table table, Player[] players) {
        this.env = env;
        this.table = table;
        this.players = players;
        deck = IntStream.range(0, env.config.deckSize).boxed().collect(Collectors.toList());
        this.playersToCheck = new ArrayBlockingQueue<>(players.length);
        this.slotsToRemove = new LinkedList<>();

    }

    public void addPlayerToCheck(Player player) {
        this.playersToCheck.add(player);
    }

    /**
     * The dealer thread starts here (main loop for the dealer thread).
     */
    @Override
    public void run() {
        dealerThread = Thread.currentThread();
        for (Player p : players) {
            Thread t = new Thread(p);
            t.start();
        }
        env.logger.info("thread " + Thread.currentThread().getName() + " starting.");
        while (!shouldFinish()) {
            placeCardsOnTable();
            reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;
            timerLoop();
            updateTimerDisplay(false);
            removeAllCardsFromTable();
        }
        System.out.println("terminated because of main loop");
        announceWinners();
        env.logger.info("thread " + Thread.currentThread().getName() + " terminated.");
    }

    /**
     * The inner loop of the dealer thread that runs as long as the countdown did
     * not time out.
     */
    private void timerLoop() {
        while (!terminate && System.currentTimeMillis() < reshuffleTime) {
            sleepUntilWokenOrTimeout();
            updateTimerDisplay(false);
            removeCardsFromTable();
            placeCardsOnTable();
        }
    }

    /**
     * Called when the game should be terminated.
     */
    public void terminate() {
        terminate = true;
        for (Player p : players) {
            p.terminate();
        }
    }

    /**
     * Check if the game should be terminated or the game end conditions are met.
     *
     * @return true iff} the game should be finished.
     */
    private boolean shouldFinish() {
        return terminate || env.util.findSets(deck, 1).size() == 0;
    }

    /**
     * Checks cards should be removed from the table and removes them.
     */

    private void removeCardsFromTable() {
        // synchronized (table) {
        if (!playersToCheck.isEmpty()) {
            Player player = playersToCheck.remove();
            synchronized (player) {

                if (player.q.size() == env.config.featureSize) {

                    int[] slots = table.getSlotsToCheck(player);

                    int[] cards = { table.slotToCard[slots[0]], table.slotToCard[slots[1]],
                            table.slotToCard[slots[2]] };

                    if (env.util.testSet(cards)) {
                        for (int slot : slots) {
                            slotsToRemove.add(slot);
                            for (Player p : players) {
                                if (p.q.remove(slot)) {
                                }
                            }
                            table.removeCard(slot);
                        }

                        for (int slot : slotsToRemove) {
                            for (Player p : players) {
                                if (p.q.remove(slot)) {
                                }
                            }
                        }
                        slotsToRemove.clear();
                        show = true;
                        player.setGetPoint();
                        reshuffleTime = System.currentTimeMillis() + env.config.turnTimeoutMillis;

                    } else {
                        player.setInPenalty();
                    }

                }
                player.setCheckMe();
                player.setAsChecked();
                player.getPlayerThread().interrupt();

            }
        }
        // }

    }

    /**
     * Check if any cards can be removed from the deck and placed on the table.
     */
    private void placeCardsOnTable() {

        // synchronized (table) {
        canPlaceToken = false;
        if (deck.size() > 0) {
            for (int i = 0; i < table.slotToCard.length; i++) {
                if (table.slotToCard[i] == null) {
                    int randomIndex = (int) (Math.random() * deck.size());
                    int randomCard = deck.get(randomIndex);
                    table.placeCard(randomCard, i);
                    deck.remove(randomIndex);
                }
            }

        } else if (reshuffleTime - System.currentTimeMillis() == 0) {
            terminate();

        }
        if (show) {
            table.hints();
            show = false;
        }
        canPlaceToken = true;
        // }

    }

    /**
     * Sleep for a fixed amount of time or until the thread is awakened for some
     * purpose.
     */
    private void sleepUntilWokenOrTimeout() {
        try {
            if (!under5Seconds) {
                Thread.sleep(THREAD_SLEEP_TIME);
            }
        } catch (InterruptedException e) {
        }
    }

    /**
     * Reset and/or update the countdown and the countdown display.
     */
    private void updateTimerDisplay(boolean reset) {
        long curTime = reshuffleTime - System.currentTimeMillis() + ALMOST_ONE_SECOND;

        if (curTime <= env.config.turnTimeoutWarningMillis) {
            reset = true;
            env.ui.setCountdown(curTime, reset);
        }
        if (curTime >= 1000) {
            env.ui.setCountdown(curTime, reset);
        } else {
            env.ui.setCountdown(0, reset);
            show = true;
        }
        if (curTime <= env.config.turnTimeoutWarningMillis) {
            under5Seconds = true;
        } else {
            under5Seconds = false;
        }

    }

    /**
     * Returns all the cards from the table to the deck.
     */
    private void removeAllCardsFromTable() {

        // synchronized (table) {
        canPlaceToken = false;
        for (int i = 0; i < table.slotToCard.length; i++) {
            if (table.slotToCard[i] != null) {
                deck.add(table.slotToCard[i]);
                table.removeCard(i);
            }
        }

        for (Player player : players) {
            player.q.clear();
            player.setNotCheckMe();
        }
        playersToCheck.clear();
        // }
    }

    /**
     * Check who is/are the winner/s and displays them.
     */
    private void announceWinners() {
        int max = 0;
        List<Integer> winnersList = new ArrayList<>();

        for (int i = 0; i < players.length; i++) {
            if (players[i].score() > max) {
                winnersList.clear();
                winnersList.add(players[i].id);
                max = players[i].score();
            } else if (players[i].score() == max) {
                winnersList.add(players[i].id);
            }
        }
        int[] finalWinnersList = new int[winnersList.size()];
        int j = 0;
        for (int id : winnersList) {
            finalWinnersList[j] = id;
            j++;
        }
        env.ui.announceWinner(finalWinnersList);

    }

    public Thread getDelaerThread() {
        return dealerThread;
    }

}
