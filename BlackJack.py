#!/usr/bin/python3
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

# nomi reali comuni (50+)
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
    return max(len(r) for r in text.split("\n"))

def print_centered_block(text, cols):
    lines = text.split("\n")
    w = block_width(text)
    pad = max(0, (cols - w)//2)
    for ln in lines:
        print(" " * pad + ln)

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
        self.mani = [[]]
        self.puntate = [0]
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
# GRAFICA TAVOLO (CENTRATA)
# ===============================

def tavolo_grande_abbastanza():
    cols, rows = shutil.get_terminal_size(fallback=(80, 24))
    return cols >= 100 and rows >= 30, cols, rows

def mostra_tavolo_centrato(giocatori, banco, mostra_carta_coperta=True):
    ok, cols, rows = tavolo_grande_abbastanza()
    if not ok:
        return False
    clear_screen()
    fascia = "=" * min(100, cols)
    print(fascia)
    titolo = "🃏 TAVOLO DA BLACKJACK 🃏"
    print(" " * max(0, (cols - len(titolo))//2) + titolo)
    print(fascia)

    # Banco TOP-CENTER
    print()
    print(" " * max(0, (cols - 5)//2) + "Banco")
    mano_banco = [banco[0]] if mostra_carta_coperta and len(banco) >= 1 else banco
    print_centered_block(mostra_carte_ascii(mano_banco), cols)

    print()

    # Giocatori: uman* al bottom-center, CPU ai lati
    umano = next(g for g in giocatori if not g.cpu)
    cpu = [g for g in giocatori if g.cpu]
    cpu_sx = cpu[:2]
    cpu_dx = cpu[2:]

    # Lato sinistro (stampa con allineamento a sinistra ma con blocchi centrati in verticale)
    for g in cpu_sx:
        header = f"{g.nome} ({g.difficolta}) [{g.saldo}€]"
        print(header)
        print(mostra_carte_ascii(g.mani[0]))
        print()

    # Lato destro: indenta a destra
    indent = max(0, cols//2 + 15)
    for g in cpu_dx:
        header = f"{g.nome} ({g.difficolta}) [{g.saldo}€]"
        print(" " * indent + header)
        for ln in mostra_carte_ascii(g.mani[0]).split("\n"):
            print(" " * indent + ln)
        print()

    print()
    # Umano al centro
    header = f"{umano.nome} [Tu] [{umano.saldo}€]"
    print(" " * max(0, (cols - len(header))//2) + header)
    print_centered_block(mostra_carte_ascii(umano.mani[0]), cols)

    print("\n" + fascia + "\n")
    return True

# ===============================
# ANIMAZIONI (COMPATIBILI)
# ===============================

def anim_distribuzione_mano_iniziale(mazzo, giocatori, banco):
    """
    Animazione realistica: prima TUTTI hanno puntato.
    Distribuisco: 1 carta a ciascun giocatore in ordine, poi 1 al banco, poi la seconda tornata.
    Stampa messaggi e mostra una mini-anteprima della mano del destinatario.
    """
    # primo giro
    for g in giocatori:
        print(f"→ Distribuisco a {g.nome}...")
        g.mani[0].append(mazzo.pesca())
        print(mostra_carte_ascii(g.mani[0]))
        time.sleep(0.45)
    print("→ Distribuisco al Banco...")
    banco.append(mazzo.pesca())
    print(mostra_carte_ascii([banco[0]]))
    time.sleep(0.55)
    # secondo giro
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

    # 3) TAVOLO (se grande abbastanza) con carta del banco coperta
    mostrato = mostra_tavolo_centrato(giocatori, banco, mostra_carta_coperta=True)
    if not mostrato:
        print("\n(Centro tavolo non mostrato: terminale troppo piccolo)")

    # 4) TURNI
    for g in giocatori:
        for i in range(len(g.mani)):
            turno_giocatore(mazzo, g, i)

    # 5) BANCO
    print("\n--- Turno del Banco ---")
    pb = turno_banco(mazzo, banco)

    # 6) TAVOLO finale (banco scoperto)
    mostra_tavolo_centrato(giocatori, banco, mostra_carta_coperta=False)
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
        # crea umano
        nome = input("Inserisci il tuo nome: ") or "Giocatore"
        giocatori_totali.append(Giocatore(nome))
        # pre-crea fino a 20 CPU con nomi reali unici
        usati = set()
        for i in range(MAX_CPU_GLOBALI):
            disponibili = [n for n in NOMI_REALI if n not in usati]
            if not disponibili:
                disponibili = NOMI_REALI[:]  # se finiti, ricicla
            nome_cpu = random.choice(disponibili)
            usati.add(nome_cpu)
            diff = random.choice(DIFFICOLTA_CPU)
            giocatori_totali.append(Giocatore(nome_cpu, cpu=True, difficolta=diff))

    # loop partite
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
