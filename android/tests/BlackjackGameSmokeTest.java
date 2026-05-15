package com.siggeix7.blackjack;

final class BlackjackGameSmokeTest {
    public static void main(String[] args) {
        testScoringWithAces();
        testRestoreBelowMinimumEndsGame();
        stressRoundFlow();
        System.out.println("BlackjackGameSmokeTest passed");
    }

    private static void testScoringWithAces() {
        java.util.ArrayList<Card> hand = new java.util.ArrayList<Card>();
        hand.add(new Card("A", 'S'));
        hand.add(new Card("9", 'H'));
        hand.add(new Card("A", 'D'));
        assertEquals(21, Player.score(hand), "soft ace score");
        hand.add(new Card("K", 'C'));
        assertEquals(21, Player.score(hand), "multiple ace score");
    }

    private static void testRestoreBelowMinimumEndsGame() {
        BlackjackGame game = new BlackjackGame("QA");
        game.restoreProgress(BlackjackGame.MIN_BET - 1, BlackjackGame.START_DEALER_BANK, BlackjackGame.MIN_BET, null);
        assertTrue(game.phase == BlackjackGame.Phase.GAME_OVER, "low restore should end game");
        assertTrue(!game.canDeal(), "low restore should block dealing");
    }

    private static void stressRoundFlow() {
        for (int seed = 0; seed < 120; seed++) {
            BlackjackGame game = new BlackjackGame("QA");
            game.random.setSeed(seed);
            for (int round = 0; round < 35 && game.canDeal(); round++) {
                assertTrue(game.prepareRound(), "round should prepare");
                dealInitialCards(game);
                game.completeInitialDeal();
                if (game.phase == BlackjackGame.Phase.INSURANCE) {
                    game.answerInsurance((seed + round) % 2 == 0);
                }
                playHumanBasicStrategy(game);
                while (game.phase == BlackjackGame.Phase.CPU_TURN) {
                    game.playNextCpuStep();
                    assertValidBalances(game);
                }
                settleDealer(game);
                assertTrue(game.phase == BlackjackGame.Phase.ROUND_OVER || game.phase == BlackjackGame.Phase.GAME_OVER,
                    "round should reach terminal phase");
                assertValidBalances(game);
            }
        }
    }

    private static void dealInitialCards(BlackjackGame game) {
        for (int i = 0; i < game.tablePlayers.size(); i++) {
            game.dealCardToPlayer(i, 0);
        }
        game.dealCardToDealer();
        for (int i = 0; i < game.tablePlayers.size(); i++) {
            game.dealCardToPlayer(i, 0);
        }
        game.dealCardToDealer();
    }

    private static void playHumanBasicStrategy(BlackjackGame game) {
        int guard = 0;
        while (game.phase == BlackjackGame.Phase.PLAYER_TURN && guard++ < 40) {
            int score = Player.score(game.human.hands.get(game.activeHandIndex));
            if (game.canDouble() && score == 11) {
                game.doubleDown();
            } else if (game.canHit() && score < 17) {
                game.hit();
            } else {
                game.stand();
            }
            assertValidBalances(game);
        }
        assertTrue(guard < 40, "human turn guard tripped");
    }

    private static void settleDealer(BlackjackGame game) {
        if (game.phase != BlackjackGame.Phase.DEALER_TURN) {
            return;
        }
        if (game.dealerHasBlackjack()) {
            game.settleCurrentRound(true);
            return;
        }
        int guard = 0;
        while (game.dealerShouldDraw() && guard++ < 12) {
            game.dealerDrawStep();
        }
        assertTrue(guard < 12, "dealer draw guard tripped");
        game.settleCurrentRound(false);
    }

    private static void assertValidBalances(BlackjackGame game) {
        assertTrue(game.human.balance >= 0, "human balance negative");
        assertTrue(game.dealerBankroll >= 0, "dealer bankroll negative");
        for (int i = 0; i < game.tablePlayers.size(); i++) {
            Player player = game.tablePlayers.get(i);
            assertTrue(player.balance >= 0, player.name + " balance negative");
            assertEquals(player.hands.size(), player.bets.size(), player.name + " hand/bet mismatch");
            assertEquals(player.hands.size(), player.insurance.size(), player.name + " hand/insurance mismatch");
            assertEquals(player.hands.size(), player.surrendered.size(), player.name + " hand/surrender mismatch");
        }
    }

    private static void assertEquals(int expected, int actual, String message) {
        if (expected != actual) {
            throw new AssertionError(message + ": expected " + expected + ", got " + actual);
        }
    }

    private static void assertTrue(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }
}
