package com.siggeix7.blackjack;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Random;

final class BlackjackGame {
    static final int NUM_DECKS = 8;
    static final int START_BALANCE = 500;
    static final int START_DEALER_BANK = 10000;
    static final int MIN_BET = 10;

    enum Phase {
        BETTING,
        DEALING,
        INSURANCE,
        PLAYER_TURN,
        CPU_TURN,
        DEALER_TURN,
        ROUND_OVER,
        GAME_OVER
    }

    private static final String[] CPU_NAMES = {
        "John", "Francesco", "Michael", "Luca", "David", "Marco", "James", "Giovanni",
        "Robert", "Matteo", "William", "Alessandro", "Anthony", "Federico", "Daniel",
        "Stefano", "Joseph", "Angelo", "Christopher", "Antonio"
    };
    private static final String[] DIFFICULTIES = {"cauta", "equilibrata", "aggressiva"};

    final Random random = new Random();
    final ArrayList<Player> roster = new ArrayList<Player>();
    final ArrayList<Player> tablePlayers = new ArrayList<Player>();
    final ArrayList<Card> dealerHand = new ArrayList<Card>();
    final ArrayList<String> events = new ArrayList<String>();
    final Rules rules = new Rules();
    final LinkedHashSet<String> achievements = new LinkedHashSet<String>();

    Player human;
    Phase phase = Phase.BETTING;
    int dealerBankroll = START_DEALER_BANK;
    int currentBet = MIN_BET;
    int sideBetPairs;
    int sideBetTwentyOneThree;
    int activeHandIndex;
    int activeCpuPlayerIndex;
    int activeCpuHandIndex;
    int tableIndex;
    int winStreak;
    int bestBalance = START_BALANCE;
    boolean hallRecorded;
    String lastRoundSummary = "Nessuna mano conclusa ancora.";

    private ArrayList<Card> shoe = Card.buildShoe(NUM_DECKS, random);
    private int usedCards;
    private int activeSideBetPairs;
    private int activeSideBetTwentyOneThree;
    private int lastMainBet = MIN_BET;
    private int lastRoundNet;
    private int lastSideBetNet;
    private String lastSideBetSummary = "";

    BlackjackGame(String playerName) {
        newSession(playerName);
    }

    void newSession(String playerName) {
        roster.clear();
        tablePlayers.clear();
        dealerHand.clear();
        events.clear();
        shoe = Card.buildShoe(rules.decks, random);
        usedCards = 0;
        dealerBankroll = START_DEALER_BANK;
        currentBet = rules.minBet;
        sideBetPairs = 0;
        sideBetTwentyOneThree = 0;
        activeSideBetPairs = 0;
        activeSideBetTwentyOneThree = 0;
        lastMainBet = rules.minBet;
        lastRoundNet = 0;
        winStreak = 0;
        bestBalance = START_BALANCE;
        lastRoundSummary = "Nessuna mano conclusa ancora.";
        phase = Phase.BETTING;
        hallRecorded = false;
        human = new Player(cleanName(playerName), START_BALANCE, false, "giocatore");
        roster.add(human);
        ArrayList<String> names = new ArrayList<String>();
        for (int i = 0; i < CPU_NAMES.length; i++) {
            names.add(CPU_NAMES[i]);
        }
        Collections.shuffle(names, random);
        for (int i = 0; i < names.size(); i++) {
            String difficulty = DIFFICULTIES[random.nextInt(DIFFICULTIES.length)];
            roster.add(new Player(names.get(i), START_BALANCE, true, difficulty));
        }
        recordAchievement("Primo ingresso al tavolo");
        log("Benvenuto al tavolo, " + human.name + ". Scegli la puntata e distribuisci.");
    }

    void restoreProgress(int balance, int bank, int bet, Player.Stats savedStats) {
        human.balance = Math.max(0, balance);
        dealerBankroll = Math.max(0, bank);
        currentBet = human.balance >= rules.minBet
            ? Math.max(rules.minBet, Math.min(Math.max(rules.minBet, bet), Math.min(rules.maxBet, human.balance)))
            : rules.minBet;
        if (savedStats != null) {
            human.stats.hands = savedStats.hands;
            human.stats.wins = savedStats.wins;
            human.stats.losses = savedStats.losses;
            human.stats.pushes = savedStats.pushes;
            human.stats.busts = savedStats.busts;
            human.stats.blackjacks = savedStats.blackjacks;
            human.stats.doubles = savedStats.doubles;
            human.stats.splits = savedStats.splits;
            human.stats.surrenders = savedStats.surrenders;
            human.stats.insuranceWon = savedStats.insuranceWon;
            human.stats.sideBetsWon = savedStats.sideBetsWon;
            human.stats.net = savedStats.net;
        }
        bestBalance = Math.max(bestBalance, human.balance);
        if (human.balance < rules.minBet || dealerBankroll <= 0) {
            phase = Phase.GAME_OVER;
        }
    }

    void changeBet(int delta) {
        if (phase != Phase.BETTING && phase != Phase.ROUND_OVER) {
            return;
        }
        int max = maxMainBetAllowed();
        currentBet = Math.max(rules.minBet, Math.min(max, currentBet + delta));
    }

    void setMaxBet() {
        if (phase == Phase.BETTING || phase == Phase.ROUND_OVER) {
            currentBet = Math.max(rules.minBet, maxMainBetAllowed());
        }
    }

    void clearBet() {
        if (phase == Phase.BETTING || phase == Phase.ROUND_OVER) {
            currentBet = Math.min(rules.minBet, Math.max(rules.minBet, human.balance));
        }
    }

    void repeatLastBet() {
        if (phase == Phase.BETTING || phase == Phase.ROUND_OVER) {
            currentBet = Math.max(rules.minBet, Math.min(maxMainBetAllowed(), lastMainBet));
        }
    }

    void changeSideBet(boolean pairs, int delta) {
        if (phase != Phase.BETTING && phase != Phase.ROUND_OVER) {
            return;
        }
        int current = pairs ? sideBetPairs : sideBetTwentyOneThree;
        int other = pairs ? sideBetTwentyOneThree : sideBetPairs;
        int max = Math.max(0, Math.min(rules.maxSideBet, human.balance - currentBet - other));
        int updated = Math.max(0, Math.min(max, current + delta));
        if (pairs) {
            sideBetPairs = updated;
        } else {
            sideBetTwentyOneThree = updated;
        }
    }

    void clearSideBets() {
        if (phase == Phase.BETTING || phase == Phase.ROUND_OVER) {
            sideBetPairs = 0;
            sideBetTwentyOneThree = 0;
        }
    }

    boolean canDeal() {
        return (phase == Phase.BETTING || phase == Phase.ROUND_OVER)
            && currentBet >= rules.minBet
            && human.balance >= currentBet + sideBetPairs + sideBetTwentyOneThree
            && dealerBankroll > 0;
    }

    boolean prepareRound() {
        if (!canDeal()) {
            if (human.balance < rules.minBet) {
                phase = Phase.GAME_OVER;
                log("Saldo insufficiente per continuare: partita terminata.");
            }
            return false;
        }

        tablePlayers.clear();
        dealerHand.clear();
        activeHandIndex = 0;
        activeCpuPlayerIndex = 0;
        activeCpuHandIndex = 0;
        hallRecorded = false;
        currentBet = Math.min(currentBet, maxMainBetAllowed());
        lastMainBet = currentBet;
        human.resetForRound();
        placeBet(human, currentBet);
        activeSideBetPairs = sideBetPairs;
        activeSideBetTwentyOneThree = sideBetTwentyOneThree;
        if (activeSideBetPairs > 0) {
            human.balance -= activeSideBetPairs;
        }
        if (activeSideBetTwentyOneThree > 0) {
            human.balance -= activeSideBetTwentyOneThree;
        }
        lastSideBetSummary = "";
        lastSideBetNet = 0;
        lastRoundSummary = "Mano in corso...";
        tablePlayers.add(human);

        ArrayList<Player> candidates = new ArrayList<Player>();
        for (int i = 0; i < roster.size(); i++) {
            Player player = roster.get(i);
            if (player.cpu && player.balance >= rules.minBet) {
                candidates.add(player);
            }
        }
        Collections.shuffle(candidates, random);
        int cpuCount = candidates.isEmpty() ? 0 : random.nextInt(Math.min(4, candidates.size()) + 1);
        for (int i = 0; i < cpuCount; i++) {
            Player cpu = candidates.get(i);
            cpu.resetForRound();
            int cpuBet = chooseCpuBet(cpu);
            placeBet(cpu, cpuBet);
            tablePlayers.add(cpu);
        }

        phase = Phase.DEALING;
        log("Il mazziere raccoglie le puntate e prepara la distribuzione.");
        return true;
    }

    void beginRound() {
        if (!prepareRound()) {
            return;
        }

        for (int i = 0; i < tablePlayers.size(); i++) {
            dealCardToPlayer(i, 0);
        }
        dealCardToDealer();
        for (int i = 0; i < tablePlayers.size(); i++) {
            dealCardToPlayer(i, 0);
        }
        dealCardToDealer();
        completeInitialDeal();
    }

    Card dealCardToPlayer(int playerIndex, int handIndex) {
        Player player = tablePlayers.get(playerIndex);
        Card card = drawCard();
        player.hands.get(handIndex).add(card);
        return card;
    }

    Card dealCardToDealer() {
        Card card = drawCard();
        dealerHand.add(card);
        return card;
    }

    void completeInitialDeal() {
        log("Nuova mano: " + tablePlayers.size() + " giocatori al tavolo. Il banco mostra " + dealerHand.get(0).label() + ".");
        settleSideBets();
        if ("A".equals(dealerHand.get(0).rank)) {
            phase = Phase.INSURANCE;
            log("Il banco mostra un Asso: puoi acquistare assicurazione fino a meta' puntata.");
        } else if (Player.isBlackjack(dealerHand)) {
            phase = Phase.DEALER_TURN;
            log("Il mazziere controlla la carta coperta...");
        } else {
            enterPlayerTurn();
        }
    }

    boolean answerInsurance(boolean takeInsurance) {
        if (phase != Phase.INSURANCE) {
            return false;
        }
        placeInsurance(human, 0, takeInsurance);
        for (int p = 0; p < tablePlayers.size(); p++) {
            Player player = tablePlayers.get(p);
            if (player.cpu) {
                placeInsurance(player, 0, player.decideInsurance());
            }
        }

        boolean dealerBlackjack = Player.isBlackjack(dealerHand);
        for (int p = 0; p < tablePlayers.size(); p++) {
            Player player = tablePlayers.get(p);
            for (int h = 0; h < player.insurance.size(); h++) {
                int insuranceBet = player.insurance.get(h).intValue();
                if (insuranceBet <= 0) {
                    continue;
                }
                if (dealerBlackjack) {
                    player.balance += insuranceBet * 3;
                    player.stats.insuranceWon++;
                    player.stats.net += insuranceBet * 2;
                    dealerBankroll -= insuranceBet * 2;
                } else {
                    dealerBankroll += insuranceBet;
                    player.stats.net -= insuranceBet;
                }
            }
        }

        if (dealerBlackjack) {
            phase = Phase.DEALER_TURN;
            log("Assicurazioni risolte. Il mazziere si prepara a rivelare la carta coperta.");
        } else {
            log("Il banco non ha Blackjack. Si gioca.");
            enterPlayerTurn();
        }
        return dealerBlackjack;
    }

    boolean canHit() {
        return phase == Phase.PLAYER_TURN && human.validHand(activeHandIndex)
            && !human.surrendered.get(activeHandIndex).booleanValue()
            && Player.score(human.hands.get(activeHandIndex)) < 21;
    }

    boolean canStand() {
        return phase == Phase.PLAYER_TURN && human.validHand(activeHandIndex);
    }

    boolean canDouble() {
        return phase == Phase.PLAYER_TURN && human.canDouble(activeHandIndex)
            && (rules.doubleAfterSplit || human.hands.size() == 1);
    }

    boolean canSplit() {
        return phase == Phase.PLAYER_TURN && human.canSplit(activeHandIndex);
    }

    boolean canSurrender() {
        return rules.surrenderEnabled && phase == Phase.PLAYER_TURN && human.canSurrender(activeHandIndex);
    }

    void hit() {
        if (!canHit()) {
            return;
        }
        ArrayList<Card> hand = human.hands.get(activeHandIndex);
        Card card = drawCard();
        hand.add(card);
        int total = Player.score(hand);
        log("Hai pescato " + card.label() + ". Totale mano " + (activeHandIndex + 1) + ": " + total + ".");
        if (total >= 21) {
            nextHumanHand();
        }
    }

    void stand() {
        if (!canStand()) {
            return;
        }
        log("Stai sulla mano " + (activeHandIndex + 1) + ".");
        nextHumanHand();
    }

    void doubleDown() {
        if (!canDouble()) {
            return;
        }
        int bet = human.bets.get(activeHandIndex).intValue();
        human.balance -= bet;
        human.bets.set(activeHandIndex, Integer.valueOf(bet * 2));
        human.stats.doubles++;
        Card card = drawCard();
        human.hands.get(activeHandIndex).add(card);
        log("Raddoppio: puntata a " + money(bet * 2) + ", carta " + card.label() + ".");
        nextHumanHand();
    }

    void split() {
        if (!canSplit()) {
            return;
        }
        ArrayList<Card> hand = human.hands.get(activeHandIndex);
        int bet = human.bets.get(activeHandIndex).intValue();
        Card second = hand.remove(1);
        ArrayList<Card> newHand = new ArrayList<Card>();
        newHand.add(second);
        human.hands.add(activeHandIndex + 1, newHand);
        human.bets.add(activeHandIndex + 1, Integer.valueOf(bet));
        human.insurance.add(activeHandIndex + 1, Integer.valueOf(0));
        human.surrendered.add(activeHandIndex + 1, Boolean.FALSE);
        human.balance -= bet;
        human.stats.splits++;
        hand.add(drawCard());
        newHand.add(drawCard());
        log("Split eseguito: ora hai " + human.hands.size() + " mani attive.");
        skipCompletedHumanHands();
    }

    void surrender() {
        if (!canSurrender()) {
            return;
        }
        int bet = human.bets.get(activeHandIndex).intValue();
        human.balance += bet / 2;
        human.surrendered.set(activeHandIndex, Boolean.TRUE);
        human.stats.surrenders++;
        log("Ti arrendi sulla mano " + (activeHandIndex + 1) + ": recuperi " + money(bet / 2) + ".");
        nextHumanHand();
    }

    boolean shouldRevealDealer() {
        return phase == Phase.DEALER_TURN || phase == Phase.ROUND_OVER || phase == Phase.GAME_OVER;
    }

    String eventText() {
        StringBuilder builder = new StringBuilder();
        int start = Math.max(0, events.size() - 7);
        for (int i = start; i < events.size(); i++) {
            if (builder.length() > 0) {
                builder.append('\n');
            }
            builder.append(events.get(i));
        }
        return builder.toString();
    }

    String statsText() {
        Player.Stats s = human.stats;
        double winRate = s.hands == 0 ? 0d : (100d * s.wins / s.hands);
        return "Giocatore: " + human.name
            + "\nTavolo: " + tableName()
            + "\nSaldo: " + money(human.balance)
            + "\nMiglior saldo: " + money(bestBalance)
            + "\nSerie vittorie: " + winStreak
            + "\nMani giocate: " + s.hands
            + "\nWin rate: " + String.format(Locale.ITALY, "%.1f%%", Double.valueOf(winRate))
            + "\nVittorie: " + s.wins + "  Sconfitte: " + s.losses + "  Pareggi: " + s.pushes
            + "\nBlackjack: " + s.blackjacks + "  Sballi: " + s.busts
            + "\nRaddoppi: " + s.doubles + "  Split: " + s.splits + "  Surrender: " + s.surrenders
            + "\nAssicurazioni vinte: " + s.insuranceWon
            + "\nSide bets vinte: " + s.sideBetsWon
            + "\nGuadagno netto: " + money(s.net);
    }

    static String money(int amount) {
        NumberFormat format = NumberFormat.getIntegerInstance(Locale.ITALY);
        return format.format(amount) + " €";
    }

    private void enterPlayerTurn() {
        phase = Phase.PLAYER_TURN;
        activeHandIndex = 0;
        if (Player.isBlackjack(human.hands.get(0))) {
            log("Blackjack naturale per te.");
        }
        skipCompletedHumanHands();
    }

    private void nextHumanHand() {
        activeHandIndex++;
        skipCompletedHumanHands();
    }

    private void skipCompletedHumanHands() {
        while (human.validHand(activeHandIndex)) {
            ArrayList<Card> hand = human.hands.get(activeHandIndex);
            int total = Player.score(hand);
            if (human.surrendered.get(activeHandIndex).booleanValue()) {
                activeHandIndex++;
            } else if (Player.isBlackjack(hand) || total >= 21) {
                if (total > 21) {
                    log("Mano " + (activeHandIndex + 1) + " sballata.");
                }
                activeHandIndex++;
            } else {
                log("Tocca a te: mano " + (activeHandIndex + 1) + ", totale " + total + ".");
                return;
            }
        }
        beginCpuTurns();
    }

    private void beginCpuTurns() {
        if (phase == Phase.ROUND_OVER || phase == Phase.GAME_OVER || phase == Phase.DEALER_TURN) {
            return;
        }
        activeCpuPlayerIndex = 0;
        activeCpuHandIndex = 0;
        phase = Phase.CPU_TURN;
        log("Gli altri giocatori osservano il banco e decidono con calma.");
        if (!hasPendingCpuAction()) {
            phase = Phase.DEALER_TURN;
        }
    }

    TableAction playNextCpuStep() {
        if (phase != Phase.CPU_TURN) {
            return null;
        }
        while (activeCpuPlayerIndex < tablePlayers.size()) {
            Player player = tablePlayers.get(activeCpuPlayerIndex);
            if (!player.cpu) {
                activeCpuPlayerIndex++;
                activeCpuHandIndex = 0;
                continue;
            }
            if (activeCpuHandIndex >= player.hands.size()) {
                activeCpuPlayerIndex++;
                activeCpuHandIndex = 0;
                continue;
            }
            return playCpuStep(player, activeCpuHandIndex);
        }
        phase = Phase.DEALER_TURN;
        log("Tutti i giocatori hanno completato il turno. Tocca al banco.");
        return null;
    }

    boolean dealerHasBlackjack() {
        return Player.isBlackjack(dealerHand);
    }

    boolean dealerShouldDraw() {
        int total = Player.score(dealerHand);
        return hasLiveHandsForDealer() && (total < 17 || (rules.dealerHitsSoft17 && Player.isSoft17(dealerHand)));
    }

    Card dealerDrawStep() {
        Card card = drawCard();
        dealerHand.add(card);
        log("Il banco pesca " + card.label() + ". Totale banco: " + Player.score(dealerHand) + ".");
        return card;
    }

    void settleCurrentRound(boolean dealerBlackjack) {
        settleRound(dealerBlackjack);
    }

    boolean hasLiveHandsForDealer() {
        for (int p = 0; p < tablePlayers.size(); p++) {
            Player player = tablePlayers.get(p);
            for (int h = 0; h < player.hands.size(); h++) {
                if (!player.surrendered.get(h).booleanValue() && Player.score(player.hands.get(h)) <= 21) {
                    return true;
                }
            }
        }
        return false;
    }

    private void settleRound(boolean dealerBlackjack) {
        int dealerScore = Player.score(dealerHand);
        int humanRoundNet = lastSideBetNet;
        boolean humanWonHand = false;
        boolean humanLostHand = false;
        boolean humanSplitWin = false;
        StringBuilder summary = new StringBuilder();
        summary.append("Banco: ").append(handText(dealerHand)).append(" (totale ").append(dealerScore).append(")");
        if (lastSideBetSummary.length() > 0) {
            summary.append('\n').append(lastSideBetSummary);
        }
        for (int p = 0; p < tablePlayers.size(); p++) {
            Player player = tablePlayers.get(p);
            for (int h = 0; h < player.hands.size(); h++) {
                int bet = player.bets.get(h).intValue();
                if (bet <= 0) {
                    continue;
                }
                player.stats.hands++;
                ArrayList<Card> hand = player.hands.get(h);
                int score = Player.score(hand);
                boolean blackjack = Player.isBlackjack(hand);
                if (blackjack) {
                    player.stats.blackjacks++;
                }

                int net;
                String result;
                if (player.surrendered.get(h).booleanValue()) {
                    net = -bet / 2;
                    result = "resa";
                    dealerBankroll += bet / 2;
                    player.stats.losses++;
                    player.stats.net -= bet / 2;
                } else if (score > 21) {
                    net = -bet;
                    result = "sballa";
                    dealerBankroll += bet;
                    player.stats.losses++;
                    player.stats.busts++;
                    player.stats.net -= bet;
                } else if (dealerBlackjack && blackjack) {
                    net = 0;
                    result = "pareggia Blackjack";
                    player.balance += bet;
                    player.stats.pushes++;
                } else if (dealerBlackjack) {
                    net = -bet;
                    result = "perde contro Blackjack banco";
                    dealerBankroll += bet;
                    player.stats.losses++;
                    player.stats.net -= bet;
                } else if (blackjack) {
                    int win = rules.blackjackPaysSixToFive ? bet * 6 / 5 : bet * 3 / 2;
                    net = win;
                    result = rules.blackjackPaysSixToFive ? "Blackjack paga 6:5" : "Blackjack paga 3:2";
                    player.balance += bet + win;
                    dealerBankroll -= win;
                    player.stats.wins++;
                    player.stats.net += win;
                } else if (dealerScore > 21 || score > dealerScore) {
                    net = bet;
                    result = "vince";
                    player.balance += bet * 2;
                    dealerBankroll -= bet;
                    player.stats.wins++;
                    player.stats.net += bet;
                } else if (score == dealerScore) {
                    net = 0;
                    result = "pareggia";
                    player.balance += bet;
                    player.stats.pushes++;
                } else {
                    net = -bet;
                    result = "perde";
                    dealerBankroll += bet;
                    player.stats.losses++;
                    player.stats.net -= bet;
                }
                logResult(player, h, result, net);
                if (!player.cpu) {
                    humanRoundNet += net;
                    humanWonHand = humanWonHand || net > 0;
                    humanLostHand = humanLostHand || net < 0;
                    humanSplitWin = humanSplitWin || (player.hands.size() > 1 && net > 0);
                    summary.append('\n')
                        .append("Mano ").append(h + 1).append(": ")
                        .append(handText(hand)).append(" = ").append(score).append(" -> ")
                        .append(result).append(" (").append(net >= 0 ? "+" : "").append(money(net)).append(")");
                }
            }
        }
        lastRoundNet = humanRoundNet;
        if (humanRoundNet > 0) {
            winStreak++;
        } else if (humanLostHand || humanRoundNet < 0) {
            winStreak = 0;
        }
        if (humanWonHand) {
            recordAchievement("Prima mano vincente");
        }
        if (winStreak >= 3) {
            recordAchievement("Tre vittorie consecutive");
        }
        if (humanSplitWin) {
            recordAchievement("Split vincente");
        }
        if (humanRoundNet >= Math.max(rules.minBet * 5, currentBet * 2)) {
            recordAchievement("Colpo grosso");
        }
        if (human.balance > bestBalance) {
            bestBalance = human.balance;
            recordAchievement("Nuovo record saldo");
        }
        String missionBonus = evaluateMissions();
        if (missionBonus.length() > 0) {
            summary.append('\n').append(missionBonus);
        }
        summary.append('\n').append("Netto mano: ").append(humanRoundNet >= 0 ? "+" : "").append(money(humanRoundNet));
        summary.append('\n').append("Saldo: ").append(money(human.balance));
        lastRoundSummary = summary.toString();
        if (dealerBankroll <= 0) {
            dealerBankroll = 0;
            phase = Phase.GAME_OVER;
            recordAchievement("Banco battuto");
            log("Il banco e' andato a zero. Hai vinto la sala!");
        } else if (human.balance < rules.minBet) {
            phase = Phase.GAME_OVER;
            log("Non hai abbastanza credito per un'altra mano. Partita terminata.");
        } else {
            phase = Phase.ROUND_OVER;
            currentBet = Math.min(Math.max(rules.minBet, currentBet), maxMainBetAllowed());
            log("Mano conclusa. Puoi distribuirne una nuova.");
        }
    }

    private TableAction playCpuStep(Player player, int handIndex) {
        ArrayList<Card> hand = player.hands.get(handIndex);
        if (Player.isBlackjack(hand)) {
            log(player.name + " ha Blackjack.");
            activeCpuHandIndex++;
            return new TableAction(player.name + " controlla le carte: Blackjack naturale.", 900);
        }
        if (player.surrendered.get(handIndex).booleanValue() || Player.score(hand) > 21) {
            activeCpuHandIndex++;
            return new TableAction(player.name + " passa alla mano successiva.", 650);
        }
        int total = Player.score(hand);
        if (rules.surrenderEnabled && player.canSurrender(handIndex) && player.decideSurrender(total)) {
            int bet = player.bets.get(handIndex).intValue();
            player.balance += bet / 2;
            player.surrendered.set(handIndex, Boolean.TRUE);
            player.stats.surrenders++;
            activeCpuHandIndex++;
            log(player.name + " si arrende.");
            return new TableAction(player.name + " ci pensa e si arrende.", 1000);
        }
        if (player.canSplit(handIndex) && player.decideSplit(hand.get(0).rank)) {
            int bet = player.bets.get(handIndex).intValue();
            Card second = hand.remove(1);
            ArrayList<Card> newHand = new ArrayList<Card>();
            newHand.add(second);
            player.hands.add(handIndex + 1, newHand);
            player.bets.add(handIndex + 1, Integer.valueOf(bet));
            player.insurance.add(handIndex + 1, Integer.valueOf(0));
            player.surrendered.add(handIndex + 1, Boolean.FALSE);
            player.balance -= bet;
            player.stats.splits++;
            Card firstDraw = drawCard();
            Card secondDraw = drawCard();
            hand.add(firstDraw);
            newHand.add(secondDraw);
            log(player.name + " divide la mano.");
            return new TableAction(player.name + " divide: arrivano " + firstDraw.label() + " e " + secondDraw.label() + ".", 1400);
        }
        if (player.canDouble(handIndex) && (rules.doubleAfterSplit || player.hands.size() == 1) && player.decideDouble(total)) {
            int bet = player.bets.get(handIndex).intValue();
            player.balance -= bet;
            player.bets.set(handIndex, Integer.valueOf(bet * 2));
            player.stats.doubles++;
            Card card = drawCard();
            hand.add(card);
            activeCpuHandIndex++;
            log(player.name + " raddoppia.");
            return new TableAction(player.name + " raddoppia e riceve " + card.label() + ".", 1200);
        }
        if (player.decideHit(total)) {
            Card card = drawCard();
            hand.add(card);
            int newTotal = Player.score(hand);
            if (newTotal >= 21) {
                activeCpuHandIndex++;
            }
            log(player.name + " pesca " + card.label() + ".");
            return new TableAction(player.name + " chiede carta: esce " + card.label() + " (" + newTotal + ").", 1100);
        } else {
            activeCpuHandIndex++;
            log(player.name + " sta a " + total + ".");
            return new TableAction(player.name + " resta con " + total + ".", 900);
        }
    }

    private boolean hasPendingCpuAction() {
        for (int p = 0; p < tablePlayers.size(); p++) {
            Player player = tablePlayers.get(p);
            if (player.cpu && player.balance >= 0) {
                for (int h = 0; h < player.hands.size(); h++) {
                    return true;
                }
            }
        }
        return false;
    }

    private void placeBet(Player player, int bet) {
        int safeBet = Math.max(rules.minBet, Math.min(bet, player.balance));
        player.bets.set(0, Integer.valueOf(safeBet));
        player.balance -= safeBet;
    }

    private void placeInsurance(Player player, int handIndex, boolean wantsInsurance) {
        if (!wantsInsurance || !player.validHand(handIndex)) {
            return;
        }
        int max = Math.min(player.bets.get(handIndex).intValue() / 2, player.balance);
        if (max > 0) {
            player.balance -= max;
            player.insurance.set(handIndex, Integer.valueOf(max));
            log(player.name + " prende assicurazione per " + money(max) + ".");
        }
    }

    private int chooseCpuBet(Player player) {
        int[] options = {rules.minBet, rules.minBet * 2, rules.minBet * 5};
        ArrayList<Integer> available = new ArrayList<Integer>();
        for (int i = 0; i < options.length; i++) {
            if (options[i] <= player.balance && options[i] <= rules.maxBet) {
                available.add(Integer.valueOf(options[i]));
            }
        }
        if (available.isEmpty()) {
            return Math.max(rules.minBet, Math.min(player.balance, rules.maxBet));
        }
        return available.get(random.nextInt(available.size())).intValue();
    }

    void configureRules(int decks, boolean sixToFive, boolean hitSoft17, boolean surrender, boolean doubleAfterSplit) {
        if (phase != Phase.BETTING && phase != Phase.ROUND_OVER && phase != Phase.GAME_OVER) {
            return;
        }
        int safeDecks = Math.max(1, Math.min(8, decks));
        boolean rebuildShoe = rules.decks != safeDecks;
        rules.decks = safeDecks;
        rules.blackjackPaysSixToFive = sixToFive;
        rules.dealerHitsSoft17 = hitSoft17;
        rules.surrenderEnabled = surrender;
        rules.doubleAfterSplit = doubleAfterSplit;
        currentBet = Math.max(rules.minBet, Math.min(maxMainBetAllowed(), currentBet));
        if (rebuildShoe) {
            shoe = Card.buildShoe(rules.decks, random);
            usedCards = 0;
            log("Regole aggiornate: shoe ricostruito con " + rules.decks + " mazzi.");
        } else {
            log("Regole tavolo aggiornate.");
        }
    }

    boolean selectTable(int index) {
        if (phase != Phase.BETTING && phase != Phase.ROUND_OVER && phase != Phase.GAME_OVER) {
            return false;
        }
        if (index < 0 || index >= Rules.TABLE_NAMES.length) {
            return false;
        }
        if (human.balance < Rules.TABLE_UNLOCKS[index]) {
            log("Tavolo " + Rules.TABLE_NAMES[index] + " bloccato: servono " + money(Rules.TABLE_UNLOCKS[index]) + ".");
            return false;
        }
        tableIndex = index;
        rules.minBet = Rules.TABLE_MIN_BETS[index];
        rules.maxBet = Rules.TABLE_MAX_BETS[index];
        rules.maxSideBet = Rules.TABLE_SIDE_MAX[index];
        currentBet = Math.max(rules.minBet, Math.min(maxMainBetAllowed(), currentBet));
        sideBetPairs = Math.min(sideBetPairs, rules.maxSideBet);
        sideBetTwentyOneThree = Math.min(sideBetTwentyOneThree, rules.maxSideBet);
        log("Ti sposti al tavolo " + tableName() + ".");
        return true;
    }

    String tableName() {
        return Rules.TABLE_NAMES[Math.max(0, Math.min(tableIndex, Rules.TABLE_NAMES.length - 1))];
    }

    String rulesText() {
        return "Tavolo: " + tableName()
            + "\nMazzi: " + rules.decks
            + "\nBlackjack: " + (rules.blackjackPaysSixToFive ? "paga 6:5" : "paga 3:2")
            + "\nBanco su soft 17: " + (rules.dealerHitsSoft17 ? "pesca" : "sta")
            + "\nSurrender: " + (rules.surrenderEnabled ? "attivo" : "disattivo")
            + "\nDouble after split: " + (rules.doubleAfterSplit ? "attivo" : "disattivo")
            + "\nPuntata: " + money(rules.minBet) + " - " + money(rules.maxBet)
            + "\nSide bet max: " + money(rules.maxSideBet);
    }

    String careerText() {
        StringBuilder builder = new StringBuilder();
        builder.append("Saldo: ").append(money(human.balance)).append("\n");
        for (int i = 0; i < Rules.TABLE_NAMES.length; i++) {
            builder.append(i == tableIndex ? "* " : "  ")
                .append(Rules.TABLE_NAMES[i])
                .append(" - puntate ").append(money(Rules.TABLE_MIN_BETS[i]))
                .append("/").append(money(Rules.TABLE_MAX_BETS[i]))
                .append(" - sblocco ").append(money(Rules.TABLE_UNLOCKS[i]));
            if (human.balance < Rules.TABLE_UNLOCKS[i]) {
                builder.append(" (bloccato)");
            }
            builder.append('\n');
        }
        return builder.toString();
    }

    String achievementsText() {
        StringBuilder builder = new StringBuilder();
        int index = 1;
        for (String achievement : achievements) {
            if (achievement.startsWith("Missione:")) {
                continue;
            }
            builder.append(index++).append(". ").append(achievement).append('\n');
        }
        if (builder.length() == 0) {
            return "Nessun trofeo ancora. Vinci una mano per iniziare.";
        }
        return builder.toString();
    }

    String missionsText() {
        StringBuilder builder = new StringBuilder();
        appendMission(builder, "blackjack", "Blackjack naturale", "Ottieni almeno un Blackjack.", 50,
            human.stats.blackjacks > 0);
        appendMission(builder, "streak3", "Serie calda", "Vinci 3 mani nette consecutive.", 75,
            winStreak >= 3);
        appendMission(builder, "sidebet", "Scommessa speciale", "Vinci una side bet Perfect Pairs o 21+3.", 125,
            human.stats.sideBetsWon > 0);
        appendMission(builder, "bigwin", "Colpo grosso", "Chiudi una mano con almeno " + money(Math.max(100, rules.minBet * 5)) + " di profitto.", 100,
            lastRoundNet >= Math.max(100, rules.minBet * 5));
        appendMission(builder, "hands25", "Veterano", "Gioca 25 mani complessive.", 150,
            human.stats.hands >= 25);
        appendMission(builder, "vip", "Accesso VIP", "Sblocca o entra al tavolo VIP.", 150,
            human.balance >= Rules.TABLE_UNLOCKS[1] || tableIndex >= 1);
        appendMission(builder, "highroller", "High Roller", "Sblocca o entra al tavolo High Roller.", 300,
            human.balance >= Rules.TABLE_UNLOCKS[2] || tableIndex >= 2);
        appendMission(builder, "bankhit", "Banco sotto pressione", "Porta il banco sotto " + money(9000) + ".", 100,
            dealerBankroll <= 9000);
        return builder.toString();
    }

    String guideText() {
        return "Obiettivo\n"
            + "Arriva piu' vicino possibile a 21 senza sballare e batti il banco. Il Blackjack naturale paga "
            + (rules.blackjackPaysSixToFive ? "6:5" : "3:2") + ".\n\n"
            + "Comandi\n"
            + "Pesca: prendi una carta. Stai: chiudi la mano. Raddoppia: raddoppi puntata e ricevi una sola carta. Dividi: separi coppie uguali. Arrenditi: recuperi meta' puntata se la regola e' attiva.\n\n"
            + "Side bets\n"
            + "Perfect Pairs guarda le prime due carte: coppia mista 6:1, colorata 12:1, perfetta 25:1. 21+3 usa le tue due carte e la carta scoperta del banco: colore 5:1, scala 10:1, tris 30:1, scala colore 40:1, tris suited 100:1.\n\n"
            + "Strategia rapida\n"
            + "Stai con 17+. Raddoppia spesso con 11. Dividi sempre Assi e 8, mai i 10. Con 12-16 stai se il banco mostra 2-6, altrimenti pesca o valuta surrender.\n\n"
            + "Carriera\n"
            + "Aumenta saldo e trofei per sbloccare tavoli con puntate piu' alte. Le missioni danno bonus credito una sola volta per profilo.";
    }

    String exportAchievements() {
        StringBuilder builder = new StringBuilder();
        for (String achievement : achievements) {
            if (builder.length() > 0) {
                builder.append('|');
            }
            builder.append(achievement.replace('|', '/'));
        }
        return builder.toString();
    }

    void importAchievements(String data) {
        achievements.clear();
        if (data == null || data.length() == 0) {
            return;
        }
        String[] items = data.split("\\|");
        for (int i = 0; i < items.length; i++) {
            if (items[i].trim().length() > 0) {
                achievements.add(items[i].trim());
            }
        }
    }

    String strategyHint() {
        if (phase != Phase.PLAYER_TURN || !human.validHand(activeHandIndex)) {
            return "Il suggerimento e' disponibile durante il tuo turno.";
        }
        ArrayList<Card> hand = human.hands.get(activeHandIndex);
        int total = Player.score(hand);
        int dealer = dealerHand.isEmpty() ? 0 : dealerHand.get(0).value();
        if (dealer == 11) {
            dealer = 11;
        }
        if (canSurrender() && (total == 16 && dealer >= 9 || total == 15 && dealer == 10)) {
            return "Suggerimento: arrenditi. La mano e' debole contro la carta alta del banco.";
        }
        if (canSplit()) {
            String rank = hand.get(0).rank;
            if ("A".equals(rank) || "8".equals(rank)) {
                return "Suggerimento: dividi. Assi e 8 sono gli split piu' forti.";
            }
            if (("10".equals(rank) || "J".equals(rank) || "Q".equals(rank) || "K".equals(rank))) {
                return "Suggerimento: non dividere i 10. Stai con 20.";
            }
        }
        if (canDouble() && (total == 11 || total == 10 && dealer <= 9 || total == 9 && dealer >= 3 && dealer <= 6)) {
            return "Suggerimento: raddoppia. Hai vantaggio matematico su questa carta del banco.";
        }
        boolean soft = Player.hasAce(hand) && total <= 21;
        if (soft && total >= 18) {
            return dealer >= 9 ? "Suggerimento: pesca su soft " + total + " contro banco forte." : "Suggerimento: stai su soft " + total + ".";
        }
        if (total >= 17) {
            return "Suggerimento: stai. Totale abbastanza solido.";
        }
        if (total >= 13 && dealer >= 2 && dealer <= 6) {
            return "Suggerimento: stai. Lascia rischiare il banco.";
        }
        if (total == 12 && dealer >= 4 && dealer <= 6) {
            return "Suggerimento: stai con 12 contro 4-6.";
        }
        return "Suggerimento: pesca. Devi migliorare la mano.";
    }

    private int maxMainBetAllowed() {
        int available = human == null ? rules.maxBet : human.balance - sideBetPairs - sideBetTwentyOneThree;
        return Math.max(rules.minBet, Math.min(rules.maxBet, available));
    }

    private void appendMission(StringBuilder builder, String id, String title, String description, int reward, boolean ready) {
        boolean done = isMissionDone(id);
        builder.append(done ? "[OK] " : ready ? "[Pronta] " : "[ ] ")
            .append(title)
            .append(" - premio ").append(money(reward))
            .append('\n')
            .append(description)
            .append('\n')
            .append(done ? "Completata" : ready ? "Si completa alla prossima risoluzione mano." : "In corso")
            .append("\n\n");
    }

    private String evaluateMissions() {
        StringBuilder builder = new StringBuilder();
        completeMission(builder, "blackjack", "Blackjack naturale", 50, human.stats.blackjacks > 0);
        completeMission(builder, "streak3", "Serie calda", 75, winStreak >= 3);
        completeMission(builder, "sidebet", "Scommessa speciale", 125, human.stats.sideBetsWon > 0);
        completeMission(builder, "bigwin", "Colpo grosso", 100, lastRoundNet >= Math.max(100, rules.minBet * 5));
        completeMission(builder, "hands25", "Veterano", 150, human.stats.hands >= 25);
        completeMission(builder, "vip", "Accesso VIP", 150, human.balance >= Rules.TABLE_UNLOCKS[1] || tableIndex >= 1);
        completeMission(builder, "highroller", "High Roller", 300, human.balance >= Rules.TABLE_UNLOCKS[2] || tableIndex >= 2);
        completeMission(builder, "bankhit", "Banco sotto pressione", 100, dealerBankroll <= 9000);
        if (builder.length() == 0) {
            return "";
        }
        return "Missioni completate:" + builder.toString();
    }

    private void completeMission(StringBuilder builder, String id, String title, int reward, boolean condition) {
        if (!condition || isMissionDone(id)) {
            return;
        }
        achievements.add(missionKey(id));
        human.balance += reward;
        if (human.balance > bestBalance) {
            bestBalance = human.balance;
        }
        builder.append('\n').append(title).append(" +").append(money(reward));
        log("Missione completata: " + title + " (+" + money(reward) + ").");
    }

    private boolean isMissionDone(String id) {
        return achievements.contains(missionKey(id));
    }

    private String missionKey(String id) {
        return "Missione:" + id;
    }

    private void settleSideBets() {
        lastSideBetSummary = "";
        lastSideBetNet = 0;
        if ((activeSideBetPairs <= 0 && activeSideBetTwentyOneThree <= 0)
                || human.hands.isEmpty() || human.hands.get(0).size() < 2 || dealerHand.isEmpty()) {
            return;
        }
        ArrayList<Card> hand = human.hands.get(0);
        Card first = hand.get(0);
        Card second = hand.get(1);
        Card dealer = dealerHand.get(0);
        StringBuilder summary = new StringBuilder("Side bets:");

        if (activeSideBetPairs > 0) {
            int odds = perfectPairsOdds(first, second);
            if (odds > 0) {
                int win = activeSideBetPairs * odds;
                human.balance += activeSideBetPairs + win;
                dealerBankroll -= win;
                human.stats.sideBetsWon++;
                human.stats.net += win;
                lastSideBetNet += win;
                recordAchievement("Perfect Pairs centrata");
                summary.append("\nPerfect Pairs: vince ").append(money(win));
                log("Perfect Pairs vince " + money(win) + ".");
            } else {
                dealerBankroll += activeSideBetPairs;
                human.stats.net -= activeSideBetPairs;
                lastSideBetNet -= activeSideBetPairs;
                summary.append("\nPerfect Pairs: perde ").append(money(activeSideBetPairs));
                log("Perfect Pairs non entra.");
            }
        }

        if (activeSideBetTwentyOneThree > 0) {
            int odds = twentyOneThreeOdds(first, second, dealer);
            if (odds > 0) {
                int win = activeSideBetTwentyOneThree * odds;
                human.balance += activeSideBetTwentyOneThree + win;
                dealerBankroll -= win;
                human.stats.sideBetsWon++;
                human.stats.net += win;
                lastSideBetNet += win;
                recordAchievement("21+3 centrata");
                summary.append("\n21+3: vince ").append(money(win));
                log("21+3 vince " + money(win) + ".");
            } else {
                dealerBankroll += activeSideBetTwentyOneThree;
                human.stats.net -= activeSideBetTwentyOneThree;
                lastSideBetNet -= activeSideBetTwentyOneThree;
                summary.append("\n21+3: perde ").append(money(activeSideBetTwentyOneThree));
                log("21+3 non entra.");
            }
        }
        lastSideBetSummary = summary.toString();
    }

    private int perfectPairsOdds(Card first, Card second) {
        if (!first.rank.equals(second.rank)) {
            return 0;
        }
        if (first.suit == second.suit) {
            return 25;
        }
        if (first.isRed() == second.isRed()) {
            return 12;
        }
        return 6;
    }

    private int twentyOneThreeOdds(Card first, Card second, Card dealer) {
        boolean flush = first.suit == second.suit && first.suit == dealer.suit;
        boolean trips = first.rank.equals(second.rank) && first.rank.equals(dealer.rank);
        boolean straight = isThreeCardStraight(first, second, dealer);
        if (trips && flush) {
            return 100;
        }
        if (straight && flush) {
            return 40;
        }
        if (trips) {
            return 30;
        }
        if (straight) {
            return 10;
        }
        if (flush) {
            return 5;
        }
        return 0;
    }

    private boolean isThreeCardStraight(Card a, Card b, Card c) {
        int[] values = {rankValue(a.rank), rankValue(b.rank), rankValue(c.rank)};
        java.util.Arrays.sort(values);
        if (values[0] == values[1] || values[1] == values[2]) {
            return false;
        }
        if (values[0] == 2 && values[1] == 3 && values[2] == 14) {
            return true;
        }
        return values[0] + 1 == values[1] && values[1] + 1 == values[2];
    }

    private int rankValue(String rank) {
        if ("A".equals(rank)) return 14;
        if ("K".equals(rank)) return 13;
        if ("Q".equals(rank)) return 12;
        if ("J".equals(rank)) return 11;
        return Integer.parseInt(rank);
    }

    private String handText(ArrayList<Card> hand) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < hand.size(); i++) {
            if (i > 0) {
                builder.append(' ');
            }
            builder.append(hand.get(i).label());
        }
        return builder.toString();
    }

    private void recordAchievement(String text) {
        if (achievements.add(text)) {
            log("Trofeo sbloccato: " + text + ".");
        }
    }

    private Card drawCard() {
        if (shoe.size() <= rules.decks * 52 / 2) {
            shoe = Card.buildShoe(rules.decks, random);
            usedCards = 0;
            log("Mazzo rimischiato automaticamente.");
        }
        usedCards++;
        return shoe.remove(shoe.size() - 1);
    }

    private void logResult(Player player, int handIndex, String result, int net) {
        String who = player.cpu ? player.name : "Tu";
        String amount = net == 0 ? "" : " (" + (net > 0 ? "+" : "") + money(net) + ")";
        log(who + " mano " + (handIndex + 1) + ": " + result + amount + ".");
    }

    private void log(String message) {
        events.add(message);
        if (events.size() > 60) {
            events.remove(0);
        }
    }

    private String cleanName(String name) {
        if (name == null) {
            return "Giocatore";
        }
        String trimmed = name.trim();
        return trimmed.length() == 0 ? "Giocatore" : trimmed;
    }

    static final class TableAction {
        final String message;
        final int delayMs;

        TableAction(String message, int delayMs) {
            this.message = message;
            this.delayMs = delayMs;
        }
    }

    static final class Rules {
        static final String[] TABLE_NAMES = {"Classic", "VIP", "High Roller"};
        static final int[] TABLE_MIN_BETS = {10, 50, 100};
        static final int[] TABLE_MAX_BETS = {500, 2000, 5000};
        static final int[] TABLE_SIDE_MAX = {50, 150, 300};
        static final int[] TABLE_UNLOCKS = {0, 1000, 2500};

        int decks = NUM_DECKS;
        boolean blackjackPaysSixToFive;
        boolean dealerHitsSoft17;
        boolean surrenderEnabled = true;
        boolean doubleAfterSplit = true;
        int minBet = MIN_BET;
        int maxBet = 500;
        int maxSideBet = 50;
    }
}
