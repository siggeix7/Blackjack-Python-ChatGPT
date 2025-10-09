#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json, random, os, time, shutil, base64, sys
from pathlib import Path

# ===============================
# CONFIGURAZIONE
# ===============================

NUM_MAZZI = 8
CUT_PERCENT = 0.5
SALVA_FILE = Path(__file__).parent / "blackjack_save.dat"  # cifrato
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

# Chiave semplice per XOR (puoi cambiarla)
SAVE_KEY = "blackjack_secure_key_v1"

# ===============================
# UTILITY VARIE
# ===============================

def clear_screen():
    os.system("cls" if os.name == "nt" else "clear")

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

def center_text_line(line, width):
    pad = max(0, (width - len(line)) // 2)
    return " " * pad + line

def print_centered_block(text, cols):
    lines = text.split("\n")
    w = block_width(text)
    pad = max(0, (cols - w) // 2)
    for ln in lines:
        print(" " * pad + ln)

# ===============================
# CIFRATURA SALVATAGGIO (Base64 + XOR)
# ===============================

def encrypt_data(text, key=SAVE_KEY):
    data = text.encode("utf-8")
    k = key.encode("utf-8")
    enc = bytes([b ^ k[i % len(k)] for i, b in enumerate(data)])
    return base64.b64encode(enc).decode("ascii")

def decrypt_data(encoded, key=SAVE_KEY):
    raw = base64.b64decode(encoded.encode("ascii"))
    k = key.encode("utf-8")
    dec = bytes([b ^ k[i % len(k)] for i, b in enumerate(raw)])
    return dec.decode("utf-8")

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
    def __init__(self, nome, saldo=500, cpu=False, difficolta="equilibrata", stats=None, mani=None, puntate=None):
        self.nome = nome
        self.saldo = saldo
        self.cpu = cpu
        self.difficolta = difficolta
        self.mani = mani if mani is not None else [[]]      # lista di mani (per split)
        self.puntate = puntate if puntate is not None else [0]    # puntata per mano
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
# SALVATAGGIO (cifrato) — lettura/scrittura CONTINUA
# ===============================

def snapshot(giocatori, mazzo):
    return {
        "giocatori": [g.__dict__ for g in giocatori],
        "mazzo": mazzo.mazzo,
        "usate": mazzo.usate
    }

def salva_stato(giocatori, mazzo):
    try:
        data = json.dumps(snapshot(giocatori, mazzo))
        enc = encrypt_data(data)
        with open(SALVA_FILE, "w") as f:
            f.write(enc)
    except Exception as e:
        print(f"⚠️ Errore nel salvataggio: {e}")

def carica_stato():
    if not SALVA_FILE.exists():
        return None
    try:
        with open(SALVA_FILE, "r") as f:
            enc = f.read().strip()
            if not enc:
                return None
            data = decrypt_data(enc)
            obj = json.loads(data)
            return obj
    except Exception:
        print("⚠️ Salvataggio non leggibile, verrà ricreato.")
        try:
            SALVA_FILE.unlink(missing_ok=True)
        except Exception:
            pass
        return None

def elimina_salvataggio():
    try:
        SALVA_FILE.unlink(missing_ok=True)
    except Exception:
        pass

# ===============================
# CONTROLLI DISPONIBILITÀ AZIONI (DOUBLE / SPLIT)
# ===============================

def check_double(g, i):
    if len(g.mani[i]) != 2:
        return False, "Puoi raddoppiare solo con esattamente 2 carte."
    if g.saldo < g.puntate[i]:
        return False, "Saldo insufficiente per raddoppiare."
    return True, ""

def check_split(g, i):
    mano = g.mani[i]
    if len(mano) != 2:
        return False, "Puoi dividere solo con esattamente 2 carte."
    if mano[0][:-1] != mano[1][:-1]:
        return False, "Le due carte devono avere lo stesso valore."
    if g.saldo < g.puntate[i]:
        return False, "Saldo insufficiente per dividere."
    return True, ""

# ===============================
# GRAFICA TAVOLO (CENTRATA, CON PUNTATE, PUNTEGGI, SPLIT ADATTIVO)
# ===============================

def tavolo_grande_abbastanza():
    cols, rows = shutil.get_terminal_size(fallback=(80, 24))
    return cols >= 100 and rows >= 30, cols, rows

def render_hand_block_with_meta(mano, puntata=None, show_total=True):
    """
    Mostra le carte; sotto una riga con 'Puntata: XX€   Totale: YY'.
    Se puntata è None, mostra solo il totale (se show_total True).
    """
    cards = mostra_carte_ascii(mano)
    width = block_width(cards)
    meta = ""
    if show_total:
        tot = calcola_punteggio(mano)
        if puntata is None:
            meta = center_text_line(f"Totale: {tot}", width)
        else:
            meta = center_text_line(f"Puntata: {puntata}€   Totale: {tot}", width)
    else:
        if puntata is not None:
            meta = center_text_line(f"Puntata: {puntata}€", width)
        else:
            meta = " " * max(0, width // 2)  # riga vuota bilanciata
    return cards + "\n" + meta

def combine_blocks_horizontally(blocks, spacing=6):
    split_blocks = [b.split("\n") for b in blocks]
    height = max(len(b) for b in split_blocks)
    for b in split_blocks:
        while len(b) < height:
            b.append("")
    widths = [max(len(line) for line in b) for b in split_blocks]
    padded_blocks = []
    for b, w in zip(split_blocks, widths):
        padded_blocks.append([line + " " * (w - len(line)) for line in b])
    merged_lines = []
    for r in range(height):
        row = (" " * spacing).join(pb[r] for pb in padded_blocks)
        merged_lines.append(row.rstrip())
    combined = "\n".join(merged_lines)
    total_w = max(len(ln) for ln in merged_lines) if merged_lines else 0
    return combined, total_w

def mostra_giocatore_blocchi(giocatore, cols):
    header = f"{giocatore.nome} ({'CPU ' + giocatore.difficolta if giocatore.cpu else 'Tu'}) [{giocatore.saldo}€]"
    print(center_text_line(header, cols))
    hand_blocks = [render_hand_block_with_meta(m, p, show_total=True) for m, p in zip(giocatore.mani, giocatore.puntate)]
    if len(hand_blocks) == 1:
        print_centered_block(hand_blocks[0], cols)
        return
    combined, width = combine_blocks_horizontally(hand_blocks, spacing=6)
    if width + 4 <= cols:
        print_centered_block(combined, cols)
    else:
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

    print()
    print(center_text_line("Banco", cols))
    if mostra_carta_coperta and len(banco) >= 1:
        # Mostra solo la prima carta del banco, senza totale
        print_centered_block(render_hand_block_with_meta([banco[0]], puntata=None, show_total=False), cols)
    else:
        print_centered_block(render_hand_block_with_meta(banco, puntata=None, show_total=True), cols)
    print()

    umano = next(g for g in giocatori if not g.cpu)
    cpu = [g for g in giocatori if g.cpu]
    cpu_sx = cpu[:2]
    cpu_dx = cpu[2:]

    for g in cpu_sx:
        mostra_giocatore_blocchi(g, cols)
        print()
    for g in cpu_dx:
        mostra_giocatore_blocchi(g, cols)
        print()

    mostra_giocatore_blocchi(umano, cols)

    print("\n" + fascia + "\n")
    if pausa:
        input("Premi Invio per continuare...")
    return True

# ===============================
# ANIMAZIONI (COMPATIBILI)
# ===============================

def anim_distribuzione_mano_iniziale(mazzo, giocatori, banco, salva_cb):
    # Primo giro
    for g in giocatori:
        print(f"→ Distribuisco a {g.nome}...")
        g.mani[0].append(mazzo.pesca())
        salva_cb()
        print(mostra_carte_ascii(g.mani[0]))
        time.sleep(0.45)
    print("→ Distribuisco al Banco...")
    banco.append(mazzo.pesca())
    salva_cb()
    print(mostra_carte_ascii([banco[0]]))
    time.sleep(0.55)
    # Secondo giro
    for g in giocatori:
        print(f"→ Distribuisco a {g.nome}...")
        g.mani[0].append(mazzo.pesca())
        salva_cb()
        print(mostra_carte_ascii(g.mani[0]))
        time.sleep(0.45)
    print("→ Distribuisco al Banco...")
    banco.append(mazzo.pesca())
    salva_cb()
    print(mostra_carte_ascii(banco))
    time.sleep(0.6)

# ===============================
# LOGICA DI GIOCO
# ===============================

def turno_giocatore(mazzo, g, i, salva_cb):
    mano = g.mani[i]
    # blackjack naturale: fermati subito
    if len(mano) == 2 and calcola_punteggio(mano) == 21:
        print(f"\n{g.nome} ha Blackjack naturale!")
        g.stats["blackjacks"] += 1
        salva_cb()
        return

    while True:
        print(f"\n{g.nome} — Mano {i+1} ({'CPU ' + g.difficolta if g.cpu else 'Giocatore'})")
        print(mostra_carte_ascii(mano))
        tot = calcola_punteggio(mano)
        print(f"Totale: {tot}")

        if tot > 21:
            print("💥 Sballato!")
            g.stats["sballi"] += 1
            salva_cb()
            return

        if g.cpu:
            time.sleep(0.6)
            if g.decide(tot):
                print(f"{g.nome} pesca.")
                mano.append(mazzo.pesca())
                salva_cb()
                # se dopo la carta è naturale (impossibile, naturale è solo prime 2) o 21, CPU può fermarsi
                if calcola_punteggio(mano) >= 21:
                    return
            else:
                print(f"{g.nome} sta.")
                return
        else:
            # Azioni disponibili + spiegazioni
            can_dbl, why_dbl = check_double(g, i)
            can_spl, why_spl = check_split(g, i)
            opzioni = ["[C]arta", "[S]tai"]
            if can_dbl: opzioni.append("[R]addoppia")
            if can_spl: opzioni.append("[D]ividi")
            sc = input(f"{', '.join(opzioni)} > ").lower().strip()

            if sc == "c":
                mano.append(mazzo.pesca())
                salva_cb()
            elif sc == "s":
                return
            elif sc == "r":
                if not can_dbl:
                    print(f"⛔ Non puoi raddoppiare: {why_dbl}")
                    continue
                g.saldo -= g.puntate[i]
                g.puntate[i] *= 2
                mano.append(mazzo.pesca())
                salva_cb()
                return
            elif sc == "d":
                if not can_spl:
                    print(f"⛔ Non puoi dividere: {why_spl}")
                    continue
                c2 = mano.pop()
                g.mani.append([c2])
                g.puntate.append(g.puntate[i])
                g.saldo -= g.puntate[i]
                mano.append(mazzo.pesca())
                g.mani[-1].append(mazzo.pesca())
                print("✂️ Mano divisa!")
                salva_cb()
                # gioca entrambe le mani
                for j in range(len(g.mani)):
                    turno_giocatore(mazzo, g, j, salva_cb)
                return
            else:
                print("Scelta non valida.")

def turno_banco(mazzo, banco, salva_cb):
    # Se banco ha blackjack naturale (21 su 2 carte), non pesca.
    if len(banco) == 2 and calcola_punteggio(banco) == 21:
        return 21
    while calcola_punteggio(banco) < 17:
        banco.append(mazzo.pesca())
        salva_cb()
        print("\nBanco pesca...")
        print(mostra_carte_ascii(banco))
        time.sleep(0.65)
    return calcola_punteggio(banco)

def fase_puntate(giocatori, salva_cb):
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
        salva_cb()
        print(f"{g.nome} punta {g.puntate[0]}€")

def elimina_se_utente_a_zero_dopo_risultati(giocatori_totali):
    umano = next(g for g in giocatori_totali if not g.cpu)
    if umano.saldo <= 0:
        print("\n💀 Hai finito i soldi. Il salvataggio verrà eliminato.")
        elimina_salvataggio()
        sys.exit(0)

def gioca_mano(mazzo, giocatori, salva_cb, giocatori_totali):
    # 1) PUNTATE (non eliminiamo se saldo scende a 0: si gioca comunque all-in)
    fase_puntate(giocatori, salva_cb)

    # 2) DISTRIBUZIONE CON ANIMAZIONE
    banco = []
    print("\n🎬 Distribuzione carte...")
    anim_distribuzione_mano_iniziale(mazzo, giocatori, banco, salva_cb)

    # 3) TAVOLO INIZIALE (banco coperto) — con pausa
    mostrato = mostra_tavolo_centrato(giocatori, banco, mostra_carta_coperta=True, pausa=True)
    if not mostrato:
        print("\n(Modalità compatta: terminale troppo piccolo per il tavolo)")

    # 4) TURNI
    for g in giocatori:
        for i in range(len(g.mani)):
            turno_giocatore(mazzo, g, i, salva_cb)

    # 5) BANCO
    print("\n--- Turno del Banco ---")
    pb = turno_banco(mazzo, banco, salva_cb)

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
                msg = f"{g.nome} sballa."
            elif pb > 21 or pg > pb:
                vincita = g.puntate[i] * 2
                g.saldo += vincita
                g.stats["vittorie"] += 1
                g.stats["guadagno"] += g.puntate[i]
                msg = f"{g.nome} VINCE! (+{g.puntate[i]}€)"
            elif pg == pb:
                g.saldo += g.puntate[i]
                g.stats["pareggi"] += 1
                msg = f"{g.nome} PAREGGIA."
            else:
                g.stats["sconfitte"] += 1
                g.stats["guadagno"] -= g.puntate[i]
                msg = f"{g.nome} perde."
            print(msg)
            salva_cb()

    # 8) Dopo i risultati: se l'umano è a 0, elimina salvataggio ora (non prima)
    elimina_se_utente_a_zero_dopo_risultati(giocatori_totali)

# ===============================
# MAIN LOOP
# ===============================

def main():
    clear_screen()
    print("🃏 B L A C K J A C K — Versione 4.6 (ASCII, secure, punteggi sul tavolo) 🃏")

    stato = carica_stato()
    giocatori_totali = []
    mazzo = Mazzo()

    if stato:
        # ricostruisci oggetti
        for g in stato.get("giocatori", []):
            giocatori_totali.append(
                Giocatore(
                    g.get("nome","Giocatore"),
                    g.get("saldo",500),
                    g.get("cpu",False),
                    g.get("difficolta","equilibrata"),
                    g.get("stats",None),
                    g.get("mani",[[]]),
                    g.get("puntate",[0])
                )
            )
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
            disponibili = [n for n in NOMI_REALI if n not in usati] or NOMI_REALI[:]
            nome_cpu = random.choice(disponibili)
            usati.add(nome_cpu)
            diff = random.choice(DIFFICOLTA_CPU)
            giocatori_totali.append(Giocatore(nome_cpu, cpu=True, difficolta=diff))
        # salva SUBITO (salvataggio continuo)
        salva_stato(giocatori_totali, mazzo)

    # helper per salvataggio continuo
    def salva_cb():
        salva_stato(giocatori_totali, mazzo)

    # loop partite
    while True:
        umano = next(g for g in giocatori_totali if not g.cpu)
        cpu_candidati = [g for g in giocatori_totali if g.cpu]
        cpu_in_tavolo = random.sample(cpu_candidati, k=random.randint(0, 4))
        giocatori = [umano] + cpu_in_tavolo[:MAX_GIOCATORI_TAVOLO-1]

        print(f"\n🎲 Nuova mano! Partecipanti: {', '.join(g.nome for g in giocatori)}")
        gioca_mano(mazzo, giocatori, salva_cb, giocatori_totali)
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
