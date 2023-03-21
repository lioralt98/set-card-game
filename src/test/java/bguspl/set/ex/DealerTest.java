package bguspl.set.ex;

import bguspl.set.Config;
import bguspl.set.Env;
import bguspl.set.UserInterface;
import bguspl.set.Util;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.List;
import java.util.Properties;
import java.util.logging.Logger;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
public class DealerTest {

    Dealer dealer;

    @Mock
    private Table table;

    @BeforeEach
    void setUp() {
        Player[] players = new Player[2];
        for (int i = 0; i < 2; i++) {
            players[i] = mock(Player.class);
        }

        Properties properties = new Properties();
        properties.put("Rows", "2");
        properties.put("Columns", "2");
        properties.put("FeatureSize", "1");
        properties.put("FeatureCount", "0");

        Logger logger = new MockLogger();
        Config config = new Config(logger, properties);
        UserInterface ui = new MockUserInterface();
        Util util = new MockUtil();

        Env env = new Env(logger, config, ui, util);
        dealer = new Dealer(env, table, players);
    }

    @Test
    void placeCardsOnTable_DeckSizeReduced() {
        when(table.countCards()).thenReturn(0);

        List<Integer> cardsInDeck = Arrays.asList(1, 2, 3, 4);

        dealer.deck.remove(0);
        dealer.deck.addAll(cardsInDeck);

        dealer.placeCardsOnTable();

        for (int i = 0; i < cardsInDeck.size(); i++) {
            assertFalse(dealer.deck.contains(cardsInDeck.get(i)));

            verify(table).placeCard(cardsInDeck.get(i), eq(anyInt()));
        }
    }

    @Test
    void removeAllCardsFromTable_DeckSizeIncreased() {
        int[] slots = {0, 1, 2, 3};
        int[] cards = {4, 5, 6, 7};

        for (int i = 0; i < 4; i++) {
            when(table.slotToCard(slots[i])).thenReturn(cards[i]);
        }

        dealer.removeAllCardsFromTable();

        for (int i = 0; i < 4; i++) {
            assertTrue(dealer.deck.contains(cards[i]));

            verify(table).removeCard(slots[i]);
            verify(table).removeTokens(slots[i]);
        }
    }

    static class MockUserInterface implements UserInterface {
        @Override
        public void dispose() {}
        @Override
        public void placeCard(int card, int slot) {}
        @Override
        public void removeCard(int slot) {}
        @Override
        public void setCountdown(long millies, boolean warn) {}
        @Override
        public void setElapsed(long millies) {}
        @Override
        public void setScore(int player, int score) {}
        @Override
        public void setFreeze(int player, long millies) {}
        @Override
        public void placeToken(int player, int slot) {}
        @Override
        public void removeTokens() {}
        @Override
        public void removeTokens(int slot) {}
        @Override
        public void removeToken(int player, int slot) {}
        @Override
        public void announceWinner(int[] players) {}
    };

    static class MockUtil implements Util {
        @Override
        public int[] cardToFeatures(int card) {
            return new int[0];
        }

        @Override
        public int[][] cardsToFeatures(int[] cards) {
            return new int[0][];
        }

        @Override
        public boolean testSet(int[] cards) {
            return false;
        }

        @Override
        public List<int[]> findSets(List<Integer> deck, int count) {
            return null;
        }

        @Override
        public void spin() {}
    }

    static class MockLogger extends Logger {
        protected MockLogger() {
            super("", null);
        }
    }
}


