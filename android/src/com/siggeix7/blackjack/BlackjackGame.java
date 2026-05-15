package com.siggeix7.blackjack;

import java.text.NumberFormat;
import java.util.ArrayList;
import java.util.Collections;
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

    Player human;
    Phase phase = Phase.BETTING;
    int dealerBankroll = START_DEALER_BANK;
    int currentBet = MIN_BET;
    int activeHandIndex;
    int activeCpuPlayerIndex;
    int activeCpuHandIndex;
    boolean hallRecorded;

    private ArrayList<Card> shoe = Card.buildShoe(NUM_DECKS, random);
    private int usedCards;

    BlackjackGame(String playerName) {
        newSession(playerName);
    }

    void newSession(String playerName) {
        roster.clear();
        tablePlayers.clear();
        dealerHand.clear();
        events.clear();
        shoe = Card.buildShoe(NUM_DECKS, random);
        usedCards = 0;
        dealerBankroll = START_DEALER_BANK;
        currentBet = MIN_BET;
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
        log("Benvenuto al tavolo, " + human.name + ". Scegli la puntata e distribuisci.");
    }

    void restoreProgress(int balance, int bank, int bet, Player.Stats savedStats) {
        human.balance = Math.max(0, balance);
        dealerBankroll = Math.max(0, bank);
        currentBet = human.balance >= MIN_BET
            ? Math.max(MIN_BET, Math.min(Math.max(MIN_BET, bet), human.balance))
            : MIN_BET;
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
            human.stats.net = savedStats.net;
        }
        if (human.balance < MIN_BET || dealerBankroll <= 0) {
            phase = Phase.GAME_OVER;
        }
    }

    void changeBet(int delta) {
        if (phase != Phase.BETTING && phase != Phase.ROUND_OVER) {
            return;
        }
        int max = Math.max(MIN_BET, human.balance);
        currentBet = Math.max(MIN_BET, Math.min(max, currentBet + delta));
    }

    void setMaxBet() {
        if (phase == Phase.BETTING || phase == Phase.ROUND_OVER) {
            currentBet = Math.max(MIN_BET, human.balance);
        }
    }

    boolean canDeal() {
        return (phase == Phase.BETTING || phase == Phase.ROUND_OVER) && human.balance >= MIN_BET && dealerBankroll > 0;
    }

    boolean prepareRound() {
        if (!canDeal()) {
            if (human.balance < MIN_BET) {
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
        currentBet = Math.min(currentBet, human.balance);
        human.resetForRound();
        placeBet(human, currentBet);
        tablePlayers.add(human);

        ArrayList<Player> candidates = new ArrayList<Player>();
        for (int i = 0; i < roster.size(); i++) {
            Player player = roster.get(i);
            if (player.cpu && player.balance >= MIN_BET) {
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
        return phase == Phase.PLAYER_TURN && human.canDouble(activeHandIndex);
    }

    boolean canSplit() {
        return phase == Phase.PLAYER_TURN && human.canSplit(activeHandIndex);
    }

    boolean canSurrender() {
        return phase == Phase.PLAYER_TURN && human.canSurrender(activeHandIndex);
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
            + "\nSaldo: " + money(human.balance)
            + "\nMani giocate: " + s.hands
            + "\nWin rate: " + String.format(Locale.ITALY, "%.1f%%", Double.valueOf(winRate))
            + "\nVittorie: " + s.wins + "  Sconfitte: " + s.losses + "  Pareggi: " + s.pushes
            + "\nBlackjack: " + s.blackjacks + "  Sballi: " + s.busts
            + "\nRaddoppi: " + s.doubles + "  Split: " + s.splits + "  Surrender: " + s.surrenders
            + "\nAssicurazioni vinte: " + s.insuranceWon
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
        return hasLiveHandsForDealer() && Player.score(dealerHand) < 17;
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

                if (player.surrendered.get(h).booleanValue()) {
                    dealerBankroll += bet / 2;
                    player.stats.losses++;
                    player.stats.net -= bet / 2;
                    logResult(player, h, "resa", -bet / 2);
                } else if (score > 21) {
                    dealerBankroll += bet;
                    player.stats.losses++;
                    player.stats.busts++;
                    player.stats.net -= bet;
                    logResult(player, h, "sballa", -bet);
                } else if (dealerBlackjack && blackjack) {
                    player.balance += bet;
                    player.stats.pushes++;
                    logResult(player, h, "pareggia Blackjack", 0);
                } else if (dealerBlackjack) {
                    dealerBankroll += bet;
                    player.stats.losses++;
                    player.stats.net -= bet;
                    logResult(player, h, "perde contro Blackjack banco", -bet);
                } else if (blackjack) {
                    int win = bet * 3 / 2;
                    player.balance += bet + win;
                    dealerBankroll -= win;
                    player.stats.wins++;
                    player.stats.net += win;
                    logResult(player, h, "Blackjack paga 3:2", win);
                } else if (dealerScore > 21 || score > dealerScore) {
                    player.balance += bet * 2;
                    dealerBankroll -= bet;
                    player.stats.wins++;
                    player.stats.net += bet;
                    logResult(player, h, "vince", bet);
                } else if (score == dealerScore) {
                    player.balance += bet;
                    player.stats.pushes++;
                    logResult(player, h, "pareggia", 0);
                } else {
                    dealerBankroll += bet;
                    player.stats.losses++;
                    player.stats.net -= bet;
                    logResult(player, h, "perde", -bet);
                }
            }
        }
        if (dealerBankroll <= 0) {
            dealerBankroll = 0;
            phase = Phase.GAME_OVER;
            log("Il banco e' andato a zero. Hai vinto la sala!");
        } else if (human.balance < MIN_BET) {
            phase = Phase.GAME_OVER;
            log("Non hai abbastanza credito per un'altra mano. Partita terminata.");
        } else {
            phase = Phase.ROUND_OVER;
            currentBet = Math.min(Math.max(MIN_BET, currentBet), human.balance);
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
        if (player.canSurrender(handIndex) && player.decideSurrender(total)) {
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
        if (player.canDouble(handIndex) && player.decideDouble(total)) {
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
        int safeBet = Math.max(MIN_BET, Math.min(bet, player.balance));
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
        int[] options = {10, 20, 50};
        ArrayList<Integer> available = new ArrayList<Integer>();
        for (int i = 0; i < options.length; i++) {
            if (options[i] <= player.balance) {
                available.add(Integer.valueOf(options[i]));
            }
        }
        if (available.isEmpty()) {
            return Math.max(1, player.balance);
        }
        return available.get(random.nextInt(available.size())).intValue();
    }

    private Card drawCard() {
        if (shoe.size() <= NUM_DECKS * 52 / 2) {
            shoe = Card.buildShoe(NUM_DECKS, random);
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
}
