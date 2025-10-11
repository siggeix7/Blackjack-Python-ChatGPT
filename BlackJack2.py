#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
Blackjack testuale da terminale (Windows/macOS/Linux/Termux)

Caratteristiche principali:
- Sabot da 6 mazzi (52 carte ciascuno) con carta di taglio ~75%.
- Rimescolo automatico al termine del round in cui si raggiunge la carta di taglio.
- Regole del banco: pesca fino a 16, sta sempre su 17 (incluso soft 17).
- 1 giocatore umano + 20 CPU divise in 4 IA (5 ciascuna): Conservativa, Aggressiva,
  Calcolatrice (basic strategy semplificata), Imprevedibile.
- Capitali iniziali: Banco 10.000€, Umano 500€, ogni CPU 500€.
- Payout: Blackjack naturale 3:2, vittoria normale 1:1, push = restituzione puntata.
- Salvataggio a fine round su blackjack_save.json (ripresa esatta all'inizio del round successivo).
- Hall of Fame (hall_of_fame.txt) alla fine della partita.
- Solo librerie standard (os, random, json, datetime, time, sys, shutil, math, typing).

Esecuzione:
    python3 blackjack.py

Controlli (durante il turno dell'umano):
    [C]arta  -> chiedi carta
    [S]tai   -> stai

A fine round:
    Opzione per salvare e uscire.
"""
import os
import sys
import json
import random
import time
import shutil
from datetime import datetime
from typing import List, Tuple, Optional, Dict

# ----------------------------- Utilità UI -----------------------------------

def clear_screen():
    os.system('cls' if os.name == 'nt' else 'clear')


def pause(msg: str = "Premi INVIO per continuare..."):
    try:
        input(msg)
    except EOFError:
        # In alcuni ambienti (p.es. piping) input può sollevare EOFError
        pass


# ----------------------------- Modello Carte --------------------------------

SUITS = ['♥', '♦', '♣', '♠']
RANKS = ['2', '3', '4', '5', '6', '7', '8', '9', '10', 'J', 'Q', 'K', 'A']
RANK_VALUES = {**{str(n): n for n in range(2, 11)}, 'J': 10, 'Q': 10, 'K': 10, 'A': 11}


class Card:
    def __init__(self, rank: str, suit: str):
        self.rank = rank
        self.suit = suit

    def value(self) -> int:
        return RANK_VALUES[self.rank]

    def __repr__(self):
        return f"[{self.rank} {self.suit}]"

    def to_json(self):
        return {"rank": self.rank, "suit": self.suit}

    @staticmethod
    def from_json(d: Dict[str, str]) -> 'Card':
        return Card(d['rank'], d['suit'])


class Hand:
    def __init__(self):
        self.cards: List[Card] = []

    def add(self, card: Card):
        self.cards.append(card)

    def totals(self) -> Tuple[int, bool]:
        """Restituisce (totale, is_soft). A è 11 finché non si sfora."""
        total = 0
        aces = 0
        for c in self.cards:
            if c.rank == 'A':
                aces += 1
            total += c.value()
        # Riduci gli Assi a 1 se necessario
        is_soft = False
        while total > 21 and aces > 0:
            total -= 10
            aces -= 1
        # Soft se esiste almeno un Asso valutato 11 e non si sfora
        # (ossia aces originali > aces ridotti)
        is_soft = any(c.rank == 'A' for c in self.cards) and total <= 21 and \
                   sum(1 for c in self.cards if c.rank == 'A') > aces
        return total, is_soft

    def total(self) -> int:
        return self.totals()[0]

    def is_soft(self) -> bool:
        return self.totals()[1]

    def is_blackjack(self) -> bool:
        return len(self.cards) == 2 and self.total() == 21

    def is_bust(self) -> bool:
        return self.total() > 21

    def __repr__(self):
        return ' '.join(repr(c) for c in self.cards)


# ----------------------------- Sabot (Shoe) ---------------------------------

class Shoe:
    def __init__(self, decks: int = 6,
                 remaining_cards: Optional[List[Card]] = None,
                 cut_offset: Optional[int] = None):
        """
        Se remaining_cards è fornito, inizializza il sabot con quelle carte
        come rimanenti (top = index 0). cut_offset è il numero di carte
        ancora da pescare PRIMA della carta di taglio. -1 indica che la
        carta di taglio è già stata superata.
        """
        self.decks = decks
        self.cards: List[Card] = []
        self.position = 0  # indice della prossima carta da pescare
        self.cut_position = None  # indice assoluto rispetto a self.cards
        self.cut_reached = False

        if remaining_cards is not None:
            # Ricostruzione da salvataggio
            self.cards = list(remaining_cards)
            self.position = 0
            if cut_offset is None:
                # fallback: 75% del rimanente
                self.cut_position = int(0.75 * len(self.cards))
                self.cut_reached = False
            else:
                if cut_offset < 0:
                    # già oltre la cut card
                    self.cut_position = 0
                    self.cut_reached = True
                else:
                    self.cut_position = cut_offset
                    self.cut_reached = (self.position >= self.cut_position)
        else:
            self._fresh_shoe()

    def _fresh_shoe(self):
        self.cards = [Card(rank, suit)
                      for _ in range(self.decks)
                      for suit in SUITS
                      for rank in RANKS]
        random.shuffle(self.cards)
        self.position = 0
        # Taglio attorno al 75% (random tra 70% e 80% per realismo)
        cut_ratio = random.uniform(0.70, 0.80)
        self.cut_position = int(len(self.cards) * cut_ratio)
        self.cut_reached = False

    def draw(self) -> Card:
        if self.position >= len(self.cards):
            # Dovrebbe essere rimescolato prima di pescare.
            # Per sicurezza, in caso estremo, rinfresca sabot.
            self._fresh_shoe()
        card = self.cards[self.position]
        self.position += 1
        if not self.cut_reached and self.position >= self.cut_position:
            self.cut_reached = True
        return card

    def remaining(self) -> int:
        return len(self.cards) - self.position

    def to_json(self) -> Dict:
        # Salva solo le carte rimanenti e la distanza dalla cut card
        remaining_cards = [c.to_json() for c in self.cards[self.position:]]
        if self.cut_reached:
            cut_offset = -1
        else:
            cut_offset = max(0, self.cut_position - self.position)
        return {"cards": remaining_cards, "cut_offset": cut_offset}

    @staticmethod
    def from_json(d: Dict) -> 'Shoe':
        rem = [Card.from_json(x) for x in d.get('cards', [])]
        cut_offset = d.get('cut_offset', None)
        return Shoe(remaining_cards=rem, cut_offset=cut_offset)


# ----------------------------- Giocatori ------------------------------------

class Player:
    def __init__(self, name: str, balance: int):
        self.name = name
        self.balance = balance
        self.bet = 0
        self.hand = Hand()
        self.active = True  # attivo nel round

    def reset_for_round(self):
        self.bet = 0
        self.hand = Hand()
        self.active = self.balance > 0

    def place_bet(self, amount: int):
        amount = max(0, min(amount, self.balance))
        self.bet = amount
        self.balance -= amount

    def win_even(self):
        # Paga 1:1
        self.balance += self.bet * 2
        self.bet = 0

    def win_blackjack(self):
        # Blackjack naturale paga 3:2 (puntata già scalata)
        payout = int(self.bet * 2 + self.bet * 0.5)
        self.balance += payout
        self.bet = 0

    def push(self):
        # Restituzione puntata
        self.balance += self.bet
        self.bet = 0

    def lose(self):
        # Puntata già scalata, nulla da fare
        self.bet = 0

    def is_human(self) -> bool:
        return False


class HumanPlayer(Player):
    def is_human(self) -> bool:
        return True


class CPUPlayer(Player):
    def __init__(self, name: str, balance: int, ai_type: str):
        super().__init__(name, balance)
        self.ai_type = ai_type  # 'conservativa' | 'aggressiva' | 'calcolatrice' | 'imprevedibile'

    def decide_bet(self) -> int:
        b = self.balance
        if b <= 0:
            return 0
        if self.ai_type == 'conservativa':
            # ~2% del capitale, minimo 1€
            amount = max(1, int(b * 0.02))
        elif self.ai_type == 'aggressiva':
            # 10-20% random
            amount = int(b * random.uniform(0.10, 0.20))
            amount = max(1, amount)
        elif self.ai_type == 'calcolatrice':
            # Moderata e costante ~5% (min 5€)
            amount = max(5, int(b * 0.05))
        else:  # imprevedibile
            cap = max(1, int(b * 0.20))
            amount = random.randint(1, max(1, cap))
        return min(amount, b)

    def _should_hit_conservativa(self, dealer_up: Card) -> bool:
        total = self.hand.total()
        up = RANK_VALUES[dealer_up.rank]
        # Sta con punteggi bassi (13+) se upcard debole (2-6)
        if 2 <= up <= 6:
            return total < 13
        # Contro 7-A gioca più "chiuso": cerca almeno 17
        return total < 17

    def _should_hit_aggressiva(self, dealer_up: Card) -> bool:
        # Chiama anche su punteggi rischiosi (16) indipendentemente dal banco
        return self.hand.total() < 17

    def _should_hit_calcolatrice(self, dealer_up: Card) -> bool:
        # Basic Strategy molto semplificata senza split/double
        total, is_soft = self.hand.totals()
        up = RANK_VALUES[dealer_up.rank]
        if is_soft:
            # Soft totals
            if total <= 17:
                return True
            if total == 18:
                # Soft 18: stai vs 2,7,8 — chiedi vs 9,A — stai vs 3-6
                if up in (2, 7, 8):
                    return False
                if up in (9, 10, 11):  # 10 = 10/J/Q/K, 11 = A
                    return True
                return False  # 3-6
            return False  # 19+ soft = stai
        else:
            # Hard totals
            if total <= 11:
                return True
            if total == 12:
                return not (4 <= up <= 6)
            if 13 <= total <= 16:
                return not (2 <= up <= 6)
            return False  # 17+

    def _should_hit_imprevedibile(self, dealer_up: Card) -> bool:
        return random.choice([True, False])

    def should_hit(self, dealer_up: Card) -> bool:
        if self.hand.is_blackjack() or self.hand.is_bust():
            return False
        if self.ai_type == 'conservativa':
            return self._should_hit_conservativa(dealer_up)
        if self.ai_type == 'aggressiva':
            return self._should_hit_aggressiva(dealer_up)
        if self.ai_type == 'calcolatrice':
            return self._should_hit_calcolatrice(dealer_up)
        return self._should_hit_imprevedibile(dealer_up)


class Dealer(Player):
    def __init__(self):
        super().__init__("Banco", balance=0)
        self.hole_hidden = True

    def reset_for_round(self):
        super().reset_for_round()
        self.hole_hidden = True

    def play(self, shoe: Shoe):
        # Banco: pesca fino a 16, sta su 17 (soft incluso)
        self.hole_hidden = False
        while self.hand.total() < 17:
            self.hand.add(shoe.draw())


# ----------------------------- Gioco ----------------------------------------

class Game:
    SAVE_FILE = 'blackjack_save.json'
    HOF_FILE = 'hall_of_fame.txt'

    def __init__(self):
        self.rounds_played = 0
        self.bank_balance = 10000  # capitale del banco
        self.shoe = Shoe()
        self.dealer = Dealer()
        self.human = HumanPlayer("Tu", 500)
        self.cpus: List[CPUPlayer] = []
        self.reshuffle_next_round = False
        self._init_cpus()

    def _init_cpus(self):
        ai_types = ['conservativa', 'aggressiva', 'calcolatrice', 'imprevedibile']
        names = [f"CPU-{i:02d}" for i in range(1, 21)]
        for i, name in enumerate(names):
            ai = ai_types[(i // 5) % 4]  # 5 per tipo
            self.cpus.append(CPUPlayer(name, 500, ai))

    # ---------------------- Salvataggio/Caricamento -------------------------

    def save(self):
        data = {
            "version": 1,
            "rounds_played": self.rounds_played,
            "bank_balance": self.bank_balance,
            "human": {"name": self.human.name, "balance": self.human.balance},
            "cpus": [{
                "name": c.name,
                "balance": c.balance,
                "ai_type": c.ai_type
            } for c in self.cpus],
            "shoe": self.shoe.to_json(),
            "reshuffle_next_round": self.reshuffle_next_round,
        }
        with open(self.SAVE_FILE, 'w', encoding='utf-8') as f:
            json.dump(data, f, ensure_ascii=False, indent=2)

    def load(self) -> bool:
        if not os.path.exists(self.SAVE_FILE):
            return False
        try:
            with open(self.SAVE_FILE, 'r', encoding='utf-8') as f:
                data = json.load(f)
            self.rounds_played = data.get("rounds_played", 0)
            self.bank_balance = data.get("bank_balance", 10000)
            human = data.get("human", {})
            self.human = HumanPlayer(human.get("name", "Tu"), int(human.get("balance", 500)))
            # Ricostruisci CPU preservando ordine e IA
            self.cpus = []
            for c in data.get("cpus", []):
                self.cpus.append(CPUPlayer(c.get("name", "CPU"), int(c.get("balance", 500)), c.get("ai_type", "conservativa")))
            # Se mancassero CPU nel salvataggio (retrocompatibilità), riempi
            while len(self.cpus) < 20:
                idx = len(self.cpus) + 1
                ai_types = ['conservativa', 'aggressiva', 'calcolatrice', 'imprevedibile']
                ai = ai_types[((idx - 1) // 5) % 4]
                self.cpus.append(CPUPlayer(f"CPU-{idx:02d}", 500, ai))
            self.shoe = Shoe.from_json(data.get("shoe", {}))
            self.reshuffle_next_round = data.get("reshuffle_next_round", False)
            return True
        except Exception as e:
            print(f"Errore nel caricamento: {e}")
            return False

    def delete_save(self):
        try:
            if os.path.exists(self.SAVE_FILE):
                os.remove(self.SAVE_FILE)
        except Exception:
            pass

    # ----------------------------- Layout -----------------------------------

    def render_header(self):
        cols = shutil.get_terminal_size((80, 20)).columns
        title = "BLACKJACK — Sabot 6 mazzi (cut ~75%)"
        print(title)
        print('-' * min(len(title), cols))
        print(f"Round giocati: {self.rounds_played}")
        print(f"Capitale Banco: €{self.bank_balance}")
        print(f"Saldo {self.human.name}: €{self.human.balance}")
        print()

    def render_table(self, show_dealer_hole: bool = False, cpu_summary: Optional[str] = None):
        # Dealer
        print("Banco:", end=' ')
        if show_dealer_hole or not self.dealer.hole_hidden:
            print(f"{self.dealer.hand}  (Totale: {self.dealer.hand.total()})")
        else:
            if len(self.dealer.hand.cards) >= 2:
                shown = repr(self.dealer.hand.cards[0]) + ' [ ? ]'
            else:
                shown = ' '.join(repr(c) for c in self.dealer.hand.cards)
            print(shown)
        print()

        # Umano
        print(f"{self.human.name}: {self.human.hand}  (Totale: {self.human.hand.total()})")
        print()

        # Riassunto CPU
        if cpu_summary:
            print(cpu_summary)
        print()

    # --------------------------- Logica di Round ----------------------------

    def initial_bets(self):
        # Puntate CPU
        for cpu in self.cpus:
            cpu.reset_for_round()
            if cpu.active:
                bet = cpu.decide_bet()
                cpu.place_bet(bet)

        # Puntata umano
        self.human.reset_for_round()
        if self.human.active:
            while True:
                clear_screen()
                self.render_header()
                print("Inizio round: imposta la tua puntata.")
                print("Suggerimento: numeri interi in euro. Minimo 1€, massimo il tuo saldo.")
                print("(Per lasciare a 0 e saltare il round, inserisci 0 se proprio vuoi osservare.)")
                try:
                    raw = input("Puntata (€): ").strip()
                    amount = int(raw)
                    if amount < 0:
                        raise ValueError
                    if amount > self.human.balance:
                        print("\nNon puoi puntare più del tuo saldo!")
                        pause()
                        continue
                    self.human.place_bet(amount)
                    break
                except ValueError:
                    print("\nInserisci un numero valido (es. 25).")
                    pause()

    def deal_initial(self):
        # Se abbiamo raggiunto la cut card nel round precedente, rimescola ora
        if self.reshuffle_next_round or self.shoe.cut_reached:
            clear_screen()
            self.render_header()
            print("La carta di taglio è stata raggiunta nel round precedente. Rimescolo il sabot...")
            time.sleep(1.0)
            self.shoe = Shoe()
            self.reshuffle_next_round = False

        # Pulisci mani
        self.dealer.reset_for_round()
        for p in [self.human] + self.cpus:
            if p.active:
                p.hand = Hand()

        # Distribuzione iniziale (due carte a tutti i partecipanti attivi ed al banco)
        participants = [p for p in [self.human] + self.cpus if p.active and p.bet > 0]
        # Anche chi ha puntata 0 non riceve carte
        for _ in range(2):
            for p in participants:
                p.hand.add(self.shoe.draw())
            self.dealer.hand.add(self.shoe.draw())

    def cpu_play_all(self):
        # Le CPU giocano in background, con regole IA.
        dealer_up = self.dealer.hand.cards[0]
        for cpu in self.cpus:
            if not cpu.active or cpu.bet <= 0:
                continue
            # Se blackjack naturale, sta
            if cpu.hand.is_blackjack():
                continue
            # Decidi iterativamente
            while not cpu.hand.is_bust() and cpu.should_hit(dealer_up):
                cpu.hand.add(self.shoe.draw())

    def human_turn(self):
        if not self.human.active or self.human.bet <= 0:
            return
        # Se blackjack naturale, turno saltato automaticamente
        if self.human.hand.is_blackjack():
            return
        while True:
            clear_screen()
            self.render_header()
            cpu_in_game = sum(1 for c in self.cpus if c.active and c.bet > 0)
            cpu_summary = f"CPU in gioco questo round: {cpu_in_game}"
            self.render_table(show_dealer_hole=False, cpu_summary=cpu_summary)
            if self.human.hand.is_bust():
                print("Sei sballato! (oltre 21)")
                pause()
                break
            print("Azioni: [C]arta / [S]tai")
            choice = input("> ").strip().lower()
            if choice in ("c", "carta", "h", "hit"):
                self.human.hand.add(self.shoe.draw())
                continue
            elif choice in ("s", "stai", "stand"):
                break
            else:
                print("Scelta non valida.")
                time.sleep(0.8)

    def dealer_turn(self):
        self.dealer.play(self.shoe)

    def settle_bets(self):
        # Risoluzione risultati: aggiorna saldo del banco e dei giocatori
        dealer_total = self.dealer.hand.total()
        dealer_bust = self.dealer.hand.is_bust()
        dealer_blackjack = self.dealer.hand.is_blackjack()

        human_result = None  # 'win'|'bj'|'lose'|'push'|None (se non ha giocato)
        cpu_wins = 0
        cpu_losses = 0
        cpu_pushes = 0

        def pay_win(p: Player):
            nonlocal self
            # Vincita 1:1
            payout = p.bet  # profitto netto
            p.win_even()
            self.bank_balance -= payout

        def pay_blackjack(p: Player):
            nonlocal self
            profit = int(p.bet * 1.5)
            p.win_blackjack()
            self.bank_balance -= profit

        def take_loss(p: Player):
            nonlocal self
            # La puntata del giocatore viene trattenuta dal banco
            self.bank_balance += p.bet
            p.lose()

        def pay_push(p: Player):
            p.push()

        # Umano
        p = self.human
        if p.active and p.bet > 0:
            if p.hand.is_bust():
                take_loss(p)
                human_result = 'lose'
            elif p.hand.is_blackjack() and not dealer_blackjack:
                pay_blackjack(p)
                human_result = 'bj'
            elif dealer_bust:
                pay_win(p)
                human_result = 'win'
            else:
                pt = p.hand.total()
                dt = dealer_total
                if dt > pt:
                    take_loss(p)
                    human_result = 'lose'
                elif dt < pt:
                    pay_win(p)
                    human_result = 'win'
                else:
                    pay_push(p)
                    human_result = 'push'

        # CPU
        for c in self.cpus:
            if not c.active or c.bet <= 0:
                continue
            if c.hand.is_bust():
                take_loss(c)
                cpu_losses += 1
            elif c.hand.is_blackjack() and not dealer_blackjack:
                pay_blackjack(c)
                cpu_wins += 1
            elif dealer_bust:
                pay_win(c)
                cpu_wins += 1
            else:
                pt = c.hand.total()
                dt = dealer_total
                if dt > pt:
                    take_loss(c)
                    cpu_losses += 1
                elif dt < pt:
                    pay_win(c)
                    cpu_wins += 1
                else:
                    pay_push(c)
                    cpu_pushes += 1

        # Se il banco è andato sotto 0 nel pagamento, portalo a 0 (fine partita gestita dopo)
        if self.bank_balance < 0:
            self.bank_balance = 0

        # Prepariamo riassunto testo per la schermata di fine round
        return human_result, cpu_wins, cpu_losses, cpu_pushes

    # -------------------------- Hall of Fame ---------------------------------

    def hall_of_fame(self, result_text: str):
        now = datetime.now().strftime('%d-%m-%Y %H:%M')
        winner_balance = self.human.balance if 'Giocatore' in result_text else self.bank_balance
        line = f"Data: [{now}] - Esito: {result_text} - Saldo Finale Vincitore: [€ {winner_balance}] - Round Totali: [{self.rounds_played}]\n"
        try:
            with open(self.HOF_FILE, 'a', encoding='utf-8') as f:
                f.write(line)
        except Exception:
            pass

    # --------------------------- Ciclo Principale ----------------------------

    def start_menu(self):
        if os.path.exists(self.SAVE_FILE):
            while True:
                clear_screen()
                print("Salvataggio trovato.")
                print("[L]oad per caricare, [N]uova partita per iniziare da capo.")
                choice = input("> ").strip().lower()
                if choice in ("l", "load", "carica"):
                    if self.load():
                        break
                    else:
                        print("Impossibile caricare, si parte con nuova partita.")
                        time.sleep(1.2)
                        break
                elif choice in ("n", "nuova", "new"):
                    self.delete_save()
                    break
                else:
                    print("Scelta non valida.")
                    time.sleep(0.7)
        else:
            # Nessun salvataggio, prosegui
            pass

    def end_of_round_save_prompt(self) -> bool:
        print()
        print("Vuoi salvare e uscire? [S/N]")
        choice = input("> ").strip().lower()
        if choice in ("s", "si", "sì", "y", "yes"):
            self.save()
            print(f"\nPartita salvata su {self.SAVE_FILE}. A presto!")
            return True
        return False

    def check_game_end(self) -> Optional[str]:
        if self.human.balance <= 0:
            return "Vittoria del Banco"
        if self.bank_balance <= 0:
            return "Vittoria del Giocatore"
        return None

    def round_summary_screen(self, human_result, cpu_wins, cpu_losses, cpu_pushes):
        clear_screen()
        self.render_header()
        self.render_table(show_dealer_hole=True,
                          cpu_summary=(f"CPU: VINTE {cpu_wins}  PERSE {cpu_losses}  PUSH {cpu_pushes}"))
        # Messaggio risultato umano
        if self.human.bet == 0:
            # Nota: a questo punto le puntate sono state regolate, quindi bet=0 sempre.
            if human_result is None:
                print("Non hai giocato questo round.")
            else:
                mapping = {
                    'bj': 'Blackjack! Vincita 3:2',
                    'win': 'Hai vinto!',
                    'lose': 'Hai perso.',
                    'push': 'Push. Puntata restituita.'
                }
                print("Risultato: ", mapping.get(human_result, '-'))
        print()
        print(f"Saldo {self.human.name}: €{self.human.balance}")
        print(f"Capitale Banco: €{self.bank_balance}")
        print()

    def run(self):
        self.start_menu()
        while True:
            # Controllo fine partita prima di iniziare un nuovo round
            end = self.check_game_end()
            if end:
                clear_screen()
                self.render_header()
                print("\n=== PARTITA CONCLUSA ===")
                if end == "Vittoria del Giocatore":
                    print("Complimenti! Hai sconfitto il banco.")
                else:
                    print("Peccato! Il banco ha vinto.")
                print()
                self.hall_of_fame(end)
                self.delete_save()  # pulizia del salvataggio se esiste
                pause()
                break

            # Fase puntate
            self.initial_bets()

            # Deal
            self.deal_initial()

            # Turno umano
            self.human_turn()

            # Turno CPU
            self.cpu_play_all()

            # Turno banco
            self.dealer_turn()

            # Risoluzione puntate
            human_result, w, l, psh = self.settle_bets()

            # Round finito
            self.rounds_played += 1

            # Mostra riassunto
            self.round_summary_screen(human_result, w, l, psh)

            # Se durante il round si è raggiunta la cut card, rimescoleremo al prossimo
            if self.shoe.cut_reached:
                self.reshuffle_next_round = True

            # Scelta salvataggio & uscita
            if self.end_of_round_save_prompt():
                break

            # Attendi e passa al prossimo round
            pause()


if __name__ == '__main__':
    try:
        Game().run()
    except KeyboardInterrupt:
        print("\n\nUscita forzata. Alla prossima!")
