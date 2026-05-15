package com.siggeix7.blackjack;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Random;

final class Card {
    static final String[] RANKS = {"2", "3", "4", "5", "6", "7", "8", "9", "10", "J", "Q", "K", "A"};
    static final char[] SUITS = {'♠', '♥', '♦', '♣'};

    final String rank;
    final char suit;

    Card(String rank, char suit) {
        this.rank = rank;
        this.suit = suit;
    }

    int value() {
        if ("A".equals(rank)) {
            return 11;
        }
        if ("J".equals(rank) || "Q".equals(rank) || "K".equals(rank)) {
            return 10;
        }
        return Integer.parseInt(rank);
    }

    boolean isRed() {
        return suit == '♥' || suit == '♦';
    }

    String label() {
        return rank + String.valueOf(suit);
    }

    static ArrayList<Card> buildShoe(int decks, Random random) {
        ArrayList<Card> cards = new ArrayList<Card>();
        for (int d = 0; d < decks; d++) {
            for (int r = 0; r < RANKS.length; r++) {
                for (int s = 0; s < SUITS.length; s++) {
                    cards.add(new Card(RANKS[r], SUITS[s]));
                }
            }
        }
        Collections.shuffle(cards, random);
        return cards;
    }
}
