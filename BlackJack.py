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
MAX_GIOCATORI_TAVOLO = 5
MAX_CPU_GLOBALI = 20
DIFFICOLTA_CPU = ["cauta", "equilibrata", "aggressiva"]

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
# UTILITY
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

def mostra_carte_ascii(mano):
    if not mano:
        return "[vuoto]"
    righe = ["", "", "", "", ""]
    for c in mano:
        valore = ''.join(ch for ch in c if ch not in SEMI)
        seme = ''.join(ch for ch in c if ch in SEMI)
        carta = [
            "+-----+",
            f"|{valore:<2}   |",
            f"|  {seme}  |",
            f"|   {valore:>2}|",
            "+-----+"
        ]
        for i in range(5):
            righe[i] += carta[i] + "  "
    return "\n".join(righe)

# ===============================
# CLASSI
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
# GRAFICA ASCII DEL TAVOLO
# ===============================

def mostra_tavolo(giocatori, banco):
    cols, rows = shutil.get_terminal_size()
    if cols < 100 or rows < 30:
        return  # non mostra tavolo se terminale piccolo

    os.system("cls" if os.name == "nt" else "clear")
    print("=" * 100)
    print(" " * 40 + "🃏 TAVOLO DA BLACKJACK 🃏")
    print("=" * 100)

    centro = cols // 2

    # banco in alto
    print("\n" + " " * (centro - 6) + "Banco")
    print(" " * (centro - 10) + mostra_carte_ascii(banco).replace("\n", "\n" + " " * (centro - 10)))

    print("\n")

    cpu_sx = [g for g in giocatori if g.cpu][:2]
    cpu_dx = [g for g in giocatori if g.cpu][2:]
    umano = next(g for g in giocatori if not g.cpu)

    for g in cpu_sx:
        print(f"{g.nome} ({g.difficolta}) [{g.saldo}€]")
        print(mostra_carte_ascii(g.mani[0]))
        print()

    for g in cpu_dx:
        print(f"{' ' * (centro + 20)}{g.nome} ({g.difficolta}) [{g.saldo}€]")
        c = mostra_carte_ascii(g.mani[0]).split("\n")
        for r in c:
            print(" " * (centro + 20) + r)
        print()

    print("\n" + " " * (centro - 8) + f"{umano.nome} [Tu] [{umano.saldo}€]")
    print(" " * (centro - 12) + mostra_carte_ascii(umano.mani[0]).replace("\n", "\n" + " " * (centro - 12)))

    print("\n" + "=" * 100 + "\n")

# ===============================
# LOGICA
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
            sc = input("[C]arta / [S]tai / [R]addoppia / [D]ividi > ").lower()
            if sc == "c":
                mano.append(mazzo.pesca())
            elif sc == "s":
                return
            elif sc == "r" and len(mano) == 2 and g.saldo >= g.puntate[i]:
                g.saldo -= g.puntate[i]
                g.puntate[i] *= 2
                mano.append(mazzo.pesca())
                return
            elif sc == "d" and len(mano)==2 and mano[0][:-1]==mano[1][:-1]:
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
        time.sleep(0.7)
    return calcola_punteggio(banco)

def gioca_mano(mazzo, giocatori):
    print("\n💰 Fase di puntata:")
    for g in giocatori:
        g.reset()
        if g.cpu:
            g.puntate[0] = random.choice([10, 20, 50])
        else:
            puntata = int(input(f"Puntata per {g.nome} (saldo {g.saldo}): ") or 10)
            puntata = max(1, min(puntata, g.saldo))
            g.puntate[0] = puntata
        g.saldo -= g.puntate[0]
        print(f"{g.nome} punta {g.puntate[0]}€")

    banco = [mazzo.pesca(), mazzo.pesca()]
    for g in giocatori:
        g.mani[0] = [mazzo.pesca(), mazzo.pesca()]

    mostra_tavolo(giocatori, banco[:1])

    for g in giocatori:
        for i in range(len(g.mani)):
            turno_giocatore(mazzo, g, i)

    pb = turno_banco(mazzo, banco)
    mostra_tavolo(giocatori, banco)
    print(f"\nBanco ({pb})")

    for g in giocatori:
        for i, mano in enumerate(g.mani):
            pg = calcola_punteggio(mano)
            g.stats["mani"] += 1
            if pg == 21 and len(mano)==2:
                g.stats["blackjacks"] += 1
            if pg > 21:
                g.stats["sconfitte"] += 1
            elif pb > 21 or pg > pb:
                vincita = g.puntate[i]*2
                g.saldo += vincita
                g.stats["vittorie"] += 1
                g.stats["guadagno"] += g.puntate[i]
            elif pg == pb:
                g.saldo += g.puntate[i]
                g.stats["pareggi"] += 1
            else:
                g.stats["sconfitte"] += 1
                g.stats["guadagno"] -= g.puntate[i]

# ===============================
# MAIN
# ===============================

def main():
    os.system("cls" if os.name == "nt" else "clear")
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
        nome = input("Inserisci il tuo nome: ") or "Giocatore"
        giocatori_totali.append(Giocatore(nome))
        usati = set()
        for i in range(MAX_CPU_GLOBALI):
            nome_cpu = random.choice([n for n in NOMI_REALI if n not in usati])
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

        if input("\nVuoi continuare? (s/n) ").lower() != "s":
            print("\nStatistiche finali:")
            for g in giocatori_totali:
                if g.stats["mani"] > 0:
                    wr = g.stats["vittorie"]/g.stats["mani"]*100
                    tipo = f"CPU {g.difficolta}" if g.cpu else "Giocatore"
                    print(f"{g.nome} ({tipo}) - Vittorie: {wr:.1f}% | Saldo: {g.saldo}€")
            print("\n💾 Stato salvato. Alla prossima!")
            break

if __name__ == "__main__":
    main()
