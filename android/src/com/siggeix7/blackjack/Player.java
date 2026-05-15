package com.siggeix7.blackjack;

import java.util.ArrayList;
import java.util.List;

final class Player {
    static final int MAX_SPLIT_HANDS = 4;

    final boolean cpu;
    final String difficulty;
    String name;
    int balance;
    final ArrayList<ArrayList<Card>> hands = new ArrayList<ArrayList<Card>>();
    final ArrayList<Integer> bets = new ArrayList<Integer>();
    final ArrayList<Integer> insurance = new ArrayList<Integer>();
    final ArrayList<Boolean> surrendered = new ArrayList<Boolean>();
    final Stats stats = new Stats();

    Player(String name, int balance, boolean cpu, String difficulty) {
        this.name = name;
        this.balance = balance;
        this.cpu = cpu;
        this.difficulty = difficulty;
        resetForRound();
    }

    void resetForRound() {
        hands.clear();
        bets.clear();
        insurance.clear();
        surrendered.clear();
        addEmptyHand(0);
    }

    void addEmptyHand(int bet) {
        hands.add(new ArrayList<Card>());
        bets.add(Integer.valueOf(bet));
        insurance.add(Integer.valueOf(0));
        surrendered.add(Boolean.FALSE);
    }

    static int score(List<Card> hand) {
        int total = 0;
        int aces = 0;
        for (int i = 0; i < hand.size(); i++) {
            Card card = hand.get(i);
            total += card.value();
            if ("A".equals(card.rank)) {
                aces++;
            }
        }
        while (total > 21 && aces > 0) {
            total -= 10;
            aces--;
        }
        return total;
    }

    static boolean isBlackjack(List<Card> hand) {
        return hand.size() == 2 && score(hand) == 21;
    }

    boolean canDouble(int handIndex) {
        return validHand(handIndex)
            && hands.get(handIndex).size() == 2
            && !surrendered.get(handIndex).booleanValue()
            && balance >= bets.get(handIndex).intValue();
    }

    boolean canSplit(int handIndex) {
        if (!validHand(handIndex) || hands.size() >= MAX_SPLIT_HANDS) {
            return false;
        }
        ArrayList<Card> hand = hands.get(handIndex);
        return hand.size() == 2
            && hand.get(0).rank.equals(hand.get(1).rank)
            && balance >= bets.get(handIndex).intValue();
    }

    boolean canSurrender(int handIndex) {
        return validHand(handIndex)
            && hands.get(handIndex).size() == 2
            && !surrendered.get(handIndex).booleanValue();
    }

    boolean validHand(int handIndex) {
        return handIndex >= 0 && handIndex < hands.size();
    }

    boolean decideHit(int total) {
        int threshold;
        if ("cauta".equals(difficulty)) {
            threshold = 15;
        } else if ("aggressiva".equals(difficulty)) {
            threshold = 18;
        } else {
            threshold = 17;
        }
        return total < threshold;
    }

    boolean decideDouble(int total) {
        if ("aggressiva".equals(difficulty)) {
            return total >= 8 && total <= 12;
        }
        return total >= 9 && total <= 11;
    }

    boolean decideSplit(String rank) {
        if ("cauta".equals(difficulty)) {
            return "A".equals(rank) || "8".equals(rank);
        }
        if ("aggressiva".equals(difficulty)) {
            return "A".equals(rank) || "2".equals(rank) || "3".equals(rank)
                || "6".equals(rank) || "7".equals(rank) || "8".equals(rank) || "9".equals(rank);
        }
        return "A".equals(rank) || "8".equals(rank) || "9".equals(rank);
    }

    boolean decideSurrender(int total) {
        return "cauta".equals(difficulty) && (total == 15 || total == 16);
    }

    boolean decideInsurance() {
        if ("cauta".equals(difficulty)) {
            return true;
        }
        if ("aggressiva".equals(difficulty)) {
            return false;
        }
        return Math.random() >= 0.5d;
    }

    static final class Stats {
        int hands;
        int wins;
        int losses;
        int pushes;
        int busts;
        int blackjacks;
        int doubles;
        int splits;
        int surrenders;
        int insuranceWon;
        int net;
    }
}
