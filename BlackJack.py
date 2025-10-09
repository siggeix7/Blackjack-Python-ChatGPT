#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json, random, os, time, shutil
from pathlib import Path

# ===============================
# CONFIGURAZIONE
# ===============================

NUM_MAZZI = 8
CUT_PERCENT = 0.5
SALVA_FILE = Path(__file__).parent / "blackjack_save.txt"
MAX_GIOCATORI_TAVOLO = 5         # umano incluso
MAX_CPU_GLOBALI = 20
DIFFICOLTA_CPU = ["cauta", "equilibrata", "aggressiva"]

# Nomi reali
NOMI_REALI = [
    "John", "Francesco", "Michael", "Luca", "David", "Marco", "James", "Giovanni", "Robert",
    "Matteo", "William", "Alessandro", "Anthony", "Federico", "Daniel", "Stefano", "Joseph",
    "Angelo", "Christopher", "Antonio", "Paul", "Mario", "Thomas", "Giorgio", "Andrew",
    "Riccardo", "Nicholas", "Enrico", "Frank", "Simone", "Alberto", "Christian", "Giuseppe",
    "Benjamin", "Massimo", "Peter", "Michele", "Leonardo", "Patrick", "Samuel", "Alex",
    "Diego", "Jacob", "Carlo", "Kevin", "Nathan", "Gabriel", "Edward", "Franco"
]

SEMI = ['♠', '♥', '♦', '♣']
VALORI = {str(i): i for i in range(2, 11)} | {'J': 10, 'Q': 10, 'K': 10, 'A': 11}

# ===============================
# UTILITY CARTE & PUNTEGGI
# ===============================

def crea_mazzo():
    mazzo = [f"{v}{s}" for v in VALORI for s in SEMI] * NUM_MAZZI
    random.shuffle(mazzo)
    return mazzo

def calcola_punteggio(mano):
    tot = sum(VALORI[c[:-1]] for c in mano)
    assi = sum(1 for c in mano if c.startswith("A"))
    while tot > 21 and assi:
        tot -= 10
        assi -= 1
    return tot

def carta_ascii_lines(carta):
    valore = ''.join(ch for ch in carta if ch not in SEMI)
    seme = ''.join(ch for ch in carta if ch in SEMI)
    return [
        "+-----+",
        f"|{valore:<2}   |",
        f"|  {seme}  |",
        f"|   {valore:>2}|",
        "+-----+"
    ]

def mostra_carte_ascii(mano):
    if not mano:
        return "[vuoto]"
    righe = ["", "", "", "", ""]
    for c in mano:
        blocco = carta_ascii_lines(c)
        for i in range(5):
            righe[i] += blocco[i] + "  "
    return "\n".join(righe)

def block_width(text):
    return max(len(r) for r in text.split("\n")) if text else 0

def print_centered_block(text, cols):
    """
    Stampa un blocco multiline centrato rispetto alla larghezza del terminale.
    """
    lines = text.split("\n")
    w = block_width(text)
    pad = max(0, (cols - w) // 2)
    for ln in lines:
        print(" " * pad + ln)

def center_text_line(line, width):
    pad = max(0, (width - len(line)) // 2)
    return " " * pad + line

def clear_screen():
    os.system("cls" if os.name == "nt" else "clear")

# ===============================
# CLASSI DI GIOCO
# ===============================

class Mazzo:
    def __init__(self):
        self.mazzo = crea_mazzo()
        self.usate = 0
    def pesca(self):
        if self.usate >= len(self.mazzo) * CUT_PERCENT:
            print("\n🔄 Rimischio automatico (~50% carte usate).")
            self.mazzo = crea_mazzo()
            self.usate = 0
        self.usate += 1
        return self.mazzo.pop()

class Giocatore:
    def __init__(self, nome, saldo=500, cpu=False, difficolta="equilibrata", stats=None):
        self.nome = nome
        self.saldo = saldo
        self.cpu = cpu
        self.difficolta = difficolta
        self.mani = [[]]      # lista di mani (per split)
        self.puntate = [0]    # puntata per mano
        self.stats = stats or {
            "mani": 0, "vittorie": 0, "sconfitte": 0,
            "pareggi": 0, "sballi": 0, "blackjacks": 0, "guadagno": 0
        }
    def reset(self):
        self.mani = [[]]
        self.puntate = [0]
    def decide(self, punteggio):
        if self.difficolta == "cauta":
            soglia = 15
        elif self.difficolta == "aggressiva":
            soglia = 18
        else:
            soglia = 17
        return punteggio < soglia

# ===============================
# SALVATAGGIO
# ===============================

def carica_stato():
    if not SALVA_FILE.exists():
        return None
    try:
        with open(SALVA_FILE, "r") as f:
            return json.load(f)
    except Exception:
        print("⚠️ File di salvataggio corrotto. Verrà ricreato.")
        return None

def salva_stato(giocatori, mazzo):
    try:
        stato = {
            "giocatori": [g.__dict__ for g in giocatori],
            "mazzo": mazzo.mazzo,
            "usate": mazzo.usate
        }
        with open(SALVA_FILE, "w") as f:
            json.dump(stato, f)
    except Exception as e:
        print(f"⚠️ Errore nel salvataggio: {e}")

# ===============================
# GRAFICA TAVOLO (CENTRATA, CON PUNTATE, SPLIT ADATTIVO)
# ===============================

def tavolo_grande_abbastanza():
    cols, rows = shutil.get_terminal_size(fallback=(80, 24))
    return cols >= 100 and rows >= 30, cols, rows

def render_hand_block_with_bet(mano, puntata):
    """
    Ritorna un blocco multiline (string) con le carte affiancate
    e una riga 'Puntata: XX€' centrata sotto.
    """
    cards = mostra_carte_ascii(mano)
    width = block_width(cards)
    bet_line = center_text_line(f"Puntata: {puntata}€", width)
    return cards + "\n" + bet_line

def combine_blocks_horizontally(blocks, spacing=4):
    """
    Unisce N blocchi multiline su una sola riga (se possibile).
    Restituisce (combined_text, total_width).
    """
    split_blocks = [b.split("\n") for b in blocks]
    height = max(len(b) for b in split_blocks)
    # normalizza altezze
    for b in split_blocks:
        while len(b) < height:
            b.append("")
    # calcola larghezze
    widths = [max(len(line) for line in b) for b in split_blocks]
    padded_blocks = []
    for b, w in zip(split_blocks, widths):
        padded_blocks.append([line + " " * (w - len(line)) for line in b])
    # merge
    merged_lines = []
    for r in range(height):
        row = (" " * spacing).join(pb[r] for pb in padded_blocks)
        merged_lines.append(row.rstrip())
    combined = "\n".join(merged_lines)
    total_w = max(len(ln) for ln in merged_lines) if merged_lines else 0
    return combined, total_w

def mostra_giocatore_blocchi(giocatore, cols):
    """
    Mostra (centrato) il giocatore con una o più mani.
    Se le mani affiancate superano lo spazio, va a capo automaticamente.
    """
    header = f"{giocatore.nome} ({'CPU ' + giocatore.difficolta if giocatore.cpu else 'Tu'}) [{giocatore.saldo}€]"
    print(center_text_line(header, cols))
    # costruisci blocchi mano+puntata
    hand_blocks = [render_hand_block_with_bet(m, p) for m, p in zip(giocatore.mani, giocatore.puntate)]
    if len(hand_blocks) == 1:
        print_centered_block(hand_blocks[0], cols)
        return
    # prova layout orizzontale
    combined, width = combine_blocks_horizontally(hand_blocks, spacing=6)
    if width + 4 <= cols:
        print_centered_block(combined, cols)
    else:
        # stampa una per riga (verticale)
        for hb in hand_blocks:
            print_centered_block(hb, cols)
            print()

def mostra_tavolo_centrato(giocatori, banco, mostra_carta_coperta=True, pausa=True):
    ok, cols, rows = tavolo_grande_abbastanza()
    if not ok:
        return False
    clear_screen()
    fascia = "=" * min(100, cols)
    print(fascia)
    titolo = "🃏 TAVOLO DA BLACKJACK 🃏"
    print(center_text_line(titolo, cols))
    print(fascia)

    # Banco TOP-CENTER
    print()
    print(center_text_line("Banco", cols))
    mano_banco = [banco[0]] if mostra_carta_coperta and len(banco) >= 1 else banco
    print_centered_block(render_hand_block_with_bet(mano_banco, 0).replace("Puntata: 0€", " "), cols)

    print()

    # Umano bottom-center, CPU attorno
    umano = next(g for g in giocatori if not g.cpu)
    cpu = [g for g in giocatori if g.cpu]
    # Heuristica: mostra prima due CPU a sinistra (in alto), poi altre a destra, poi umano
    cpu_sx = cpu[:2]
    cpu_dx = cpu[2:]

    # Lato sinistro (stile "upper-left" centrato a blocchi)
    for g in cpu_sx:
        mostra_giocatore_blocchi(g, cols)
        print()

    # Lato destro
    for g in cpu_dx:
        mostra_giocatore_blocchi(g, cols)
        print()

    # Umano
    mostra_giocatore_blocchi(umano, cols)

    print("\n" + fascia + "\n")
    if pausa:
        input("Premi Invio per continuare...")
    return True

# ===============================
# ANIMAZIONI (COMPATIBILI)
# ===============================

def anim_distribuzione_mano_iniziale(mazzo, giocatori, banco):
    """
    Prima tutti hanno puntato.
    1 carta a ciascun giocatore, 1 al banco, poi seconda tornata.
    """
    # Primo giro
    for g in giocatori:
        print(f"→ Distribuisco a {g.nome}...")
        g.mani[0].append(mazzo.pesca())
        print(mostra_carte_ascii(g.mani[0]))
        time.sleep(0.45)
    print("→ Distribuisco al Banco...")
    banco.append(mazzo.pesca())
    print(mostra_carte_ascii([banco[0]]))
    time.sleep(0.55)
    # Secondo giro
    for g in giocatori:
        print(f"→ Distribuisco a {g.nome}...")
        g.mani[0].append(mazzo.pesca())
        print(mostra_carte_ascii(g.mani[0]))
        time.sleep(0.45)
    print("→ Distribuisco al Banco...")
    banco.append(mazzo.pesca())
    print(mostra_carte_ascii(banco))
    time.sleep(0.6)

# ===============================
# LOGICA DI GIOCO
# ===============================

def turno_giocatore(mazzo, g, i=0):
    mano = g.mani[i]
    while True:
        print(f"\n{g.nome} — Mano {i+1} ({'CPU ' + g.difficolta if g.cpu else 'Giocatore'})")
        print(mostra_carte_ascii(mano))
        tot = calcola_punteggio(mano)
        print(f"Totale: {tot}")

        if tot > 21:
            print("💥 Sballato!")
            g.stats["sballi"] += 1
            return

        if g.cpu:
            time.sleep(0.6)
            if g.decide(tot):
                print(f"{g.nome} pesca.")
                mano.append(mazzo.pesca())
            else:
                print(f"{g.nome} sta.")
                return
        else:
            opzioni = "[C]arta / [S]tai / [R]addoppia / [D]ividi > "
            sc = input(opzioni).lower().strip()
            if sc == "c":
                mano.append(mazzo.pesca())
            elif sc == "s":
                return
            elif sc == "r" and len(mano) == 2 and g.saldo >= g.puntate[i]:
                g.saldo -= g.puntate[i]
                g.puntate[i] *= 2
                mano.append(mazzo.pesca())
                return
            elif sc == "d" and len(mano) == 2 and mano[0][:-1] == mano[1][:-1] and g.saldo >= g.puntate[i]:
                c2 = mano.pop()
                g.mani.append([c2])
                g.puntate.append(g.puntate[i])
                g.saldo -= g.puntate[i]
                mano.append(mazzo.pesca())
                g.mani[-1].append(mazzo.pesca())
                print("✂️ Mano divisa!")
                # Gioca ciascuna mano appena creata/adattata
                for j in range(len(g.mani)):
                    turno_giocatore(mazzo, g, j)
                return
            else:
                print("Scelta non valida.")

def turno_banco(mazzo, banco):
    while calcola_punteggio(banco) < 17:
        banco.append(mazzo.pesca())
        print("\nBanco pesca...")
        print(mostra_carte_ascii(banco))
        time.sleep(0.65)
    return calcola_punteggio(banco)

def fase_puntate(giocatori):
    print("\n💰 Fase di puntata:")
    for g in giocatori:
        g.reset()
    for g in giocatori:
        if g.cpu:
            g.puntate[0] = random.choice([10, 20, 50])
        else:
            while True:
                try:
                    inp = input(f"Puntata per {g.nome} (saldo {g.saldo}): ") or "10"
                    puntata = int(inp)
                    if puntata < 1:
                        print("La puntata minima è 1.")
                        continue
                    if puntata > g.saldo:
                        print("Non puoi puntare più del saldo.")
                        continue
                    g.puntate[0] = puntata
                    break
                except ValueError:
                    print("Inserisci un numero valido.")
        g.saldo -= g.puntate[0]
        print(f"{g.nome} punta {g.puntate[0]}€")

def gioca_mano(mazzo, giocatori):
    # 1) PUNTATE
    fase_puntate(giocatori)

    # 2) DISTRIBUZIONE CON ANIMAZIONE
    banco = []
    print("\n🎬 Distribuzione carte...")
    anim_distribuzione_mano_iniziale(mazzo, giocatori, banco)

    # 3) TAVOLO INIZIALE (banco coperto) — con pausa
    mostrato = mostra_tavolo_centrato(giocatori, banco, mostra_carta_coperta=True, pausa=True)
    if not mostrato:
        print("\n(Modalità compatta: terminale troppo piccolo per il tavolo)")

    # 4) TURNI
    for g in giocatori:
        for i in range(len(g.mani)):
            turno_giocatore(mazzo, g, i)

    # 5) BANCO
    print("\n--- Turno del Banco ---")
    pb = turno_banco(mazzo, banco)

    # 6) TAVOLO FINALE (banco scoperto) — con pausa
    mostra_tavolo_centrato(giocatori, banco, mostra_carta_coperta=False, pausa=True)
    print(f"Banco ({pb})")

    # 7) RISULTATI
    for g in giocatori:
        for i, mano in enumerate(g.mani):
            pg = calcola_punteggio(mano)
            g.stats["mani"] += 1
            if pg == 21 and len(mano) == 2:
                g.stats["blackjacks"] += 1
            if pg > 21:
                g.stats["sconfitte"] += 1
                print(f"{g.nome} sballa.")
            elif pb > 21 or pg > pb:
                vincita = g.puntate[i] * 2
                g.saldo += vincita
                g.stats["vittorie"] += 1
                g.stats["guadagno"] += g.puntate[i]
                print(f"{g.nome} VINCE! (+{g.puntate[i]}€)")
            elif pg == pb:
                g.saldo += g.puntate[i]
                g.stats["pareggi"] += 1
                print(f"{g.nome} PAREGGIA.")
            else:
                g.stats["sconfitte"] += 1
                g.stats["guadagno"] -= g.puntate[i]
                print(f"{g.nome} perde.")

# ===============================
# MAIN LOOP
# ===============================

def main():
    clear_screen()
    print("🃏 B L A C K J A C K 🃏")

    stato = carica_stato()
    giocatori_totali = []
    mazzo = Mazzo()

    if stato:
        giocatori_totali = [
            Giocatore(g["nome"], g["saldo"], g["cpu"], g.get("difficolta","equilibrata"), g["stats"])
            for g in stato.get("giocatori", [])
        ]
        mazzo.mazzo = stato.get("mazzo", crea_mazzo())
        mazzo.usate = stato.get("usate", 0)
        print("✅ Stato precedente caricato.")
    else:
        # umano
        nome = input("Inserisci il tuo nome: ") or "Giocatore"
        giocatori_totali.append(Giocatore(nome))
        # CPU pre-create (fino a 20) con nomi reali unici
        usati = set()
        for i in range(MAX_CPU_GLOBALI):
            disponibili = [n for n in NOMI_REALI if n not in usati]
            if not disponibili:
                disponibili = NOMI_REALI[:]  # ricicla se finiscono
            nome_cpu = random.choice(disponibili)
            usati.add(nome_cpu)
            diff = random.choice(DIFFICOLTA_CPU)
            giocatori_totali.append(Giocatore(nome_cpu, cpu=True, difficolta=diff))

    while True:
        umano = next(g for g in giocatori_totali if not g.cpu)
        cpu_candidati = [g for g in giocatori_totali if g.cpu]
        cpu_in_tavolo = random.sample(cpu_candidati, k=random.randint(0, 4))
        giocatori = [umano] + cpu_in_tavolo[:MAX_GIOCATORI_TAVOLO-1]

        print(f"\n🎲 Nuova mano! Partecipanti: {', '.join(g.nome for g in giocatori)}")
        gioca_mano(mazzo, giocatori)
        salva_stato(giocatori_totali, mazzo)

        if input("\nVuoi continuare? (s/n) ").lower().strip() != "s":
            print("\nStatistiche finali:")
            for g in giocatori_totali:
                if g.stats["mani"] > 0:
                    wr = g.stats["vittorie"]/g.stats["mani"]*100
                    ruolo = f"CPU {g.difficolta}" if g.cpu else "Giocatore"
                    print(f"{g.nome} ({ruolo}) - Vittorie: {wr:.1f}% | Saldo: {g.saldo}€")
            print("\n💾 Stato salvato. Alla prossima!")
            break

if __name__ == "__main__":
    main()
