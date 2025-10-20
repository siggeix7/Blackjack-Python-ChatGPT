#!/usr/bin/env python3
# -*- coding: utf-8 -*-
import json, random, os, time, shutil, base64, sys
from pathlib import Path
from datetime import datetime
from colorama import Fore, Back, Style, init

init(autoreset=True)  # Inizializza colorama

# ===============================
# CONFIGURAZIONE
# ===============================

NUM_MAZZI = 8
CUT_PERCENT = 0.5
SALVA_FILE = Path(__file__).parent / "blackjack_save.dat"   # cifrato
HALL_OF_FAME_FILE = Path(__file__).parent / "blackjack_hof.txt"
MAX_GIOCATORI_TAVOLO = 5
MAX_CPU_GLOBALI = 20
DIFFICOLTA_CPU = ["cauta", "equilibrata", "aggressiva"]
BANCO_START_BANKROLL = 10_000
START_SALDO = 500
MAX_SPLIT = 4  # Massimo numero di mani dopo split

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

def get_color(seme):
    return Fore.RED if seme in '♥♦' else Fore.BLACK

def carta_ascii_lines(carta, coperta=False):
    if coperta:
        bg = Back.BLUE + Fore.WHITE
        return [
            f"{bg}+-----+" + Style.RESET_ALL,
            f"{bg}|#####|" + Style.RESET_ALL,
            f"{bg}|#####|" + Style.RESET_ALL,
            f"{bg}|#####|" + Style.RESET_ALL,
            f"{bg}+-----+" + Style.RESET_ALL
        ]
    valore = ''.join(ch for ch in carta if ch not in SEMI)
    seme = ''.join(ch for ch in carta if ch in SEMI)
    color = get_color(seme)
    bg = Back.WHITE  # Sfondo bianco per tutte le carte
    return [
        f"{bg}{color}+-----+{Style.RESET_ALL}",
        f"{bg}{color}|{valore:<2}   |{Style.RESET_ALL}",
        f"{bg}{color}|  {seme}  |{Style.RESET_ALL}",
        f"{bg}{color}|   {valore:>2}|{Style.RESET_ALL}",
        f"{bg}{color}+-----+{Style.RESET_ALL}"
    ]

def mostra_carte_ascii(mano, coperta=False):
    if not mano:
        return "[vuoto]"
    righe = ["", "", "", "", ""]
    carte = mano if not coperta else mano[:-1] + ["COPERTA"]
    for c in carte:
        blocco = carta_ascii_lines(c, coperta=(c == "COPERTA"))
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

def fmt_euro(n):
    return f"{n:,}".replace(",", ".") + "€"

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
    def __init__(self, nome, saldo=START_SALDO, cpu=False, difficolta="equilibrata",
                 stats=None, mani=None, puntate=None, assicurazioni=None):
        self.nome = nome
        self.saldo = saldo
        self.cpu = cpu
        self.difficolta = difficolta
        self.mani = mani if mani is not None else [[]]
        self.puntate = puntate if puntate is not None else [0]
        self.assicurazioni = assicurazioni if assicurazioni is not None else [0]
        self.stats = stats or {
            "mani": 0, "vittorie": 0, "sconfitte": 0,
            "pareggi": 0, "sballi": 0, "blackjacks": 0, "guadagno": 0,
            "doubles": 0, "splits": 0, "surrenders": 0, "assicurazioni_vinte": 0
        }
    def reset(self):
        self.mani = [[]]
        self.puntate = [0]
        self.assicurazioni = [0]
    def decide_pesca(self, punteggio):
        if self.difficolta == "cauta":
            soglia = 15
        elif self.difficolta == "aggressiva":
            soglia = 18
        else:
            soglia = 17
        return punteggio < soglia
    def decide_double(self, punteggio):
        if self.difficolta == "cauta":
            return punteggio in [9, 10, 11]
        elif self.difficolta == "aggressiva":
            return punteggio in [8, 9, 10, 11, 12]
        else:
            return punteggio in [9, 10, 11]
    def decide_split(self, valore):
        if self.difficolta == "cauta":
            return valore in ['A', '8']
        elif self.difficolta == "aggressiva":
            return valore in ['A', '2', '3', '6', '7', '8', '9']
        else:
            return valore in ['A', '8', '9']
    def decide_surrender(self, punteggio):
        if self.difficolta == "cauta":
            return punteggio in [15, 16]
        else:
            return False
    def decide_assicurazione(self):
        if self.difficolta == "cauta":
            return True
        elif self.difficolta == "aggressiva":
            return False
        else:
            return random.choice([True, False])

# ===============================
# STATO & SALVATAGGIO
# ===============================

def snapshot(giocatori, mazzo, banco_bankroll):
    return {
        "giocatori": [g.__dict__ for g in giocatori],
        "mazzo": mazzo.mazzo,
        "usate": mazzo.usate,
        "banco_bankroll": banco_bankroll
    }

def salva_stato(giocatori, mazzo, banco_bankroll):
    try:
        data = json.dumps(snapshot(giocatori, mazzo, banco_bankroll))
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
            return json.loads(data)
    except Exception:
        print("⚠️ Salvataggio non leggibile, verrà ricreato.")
        try: SALVA_FILE.unlink(missing_ok=True)
        except Exception: pass
        return None

def elimina_salvataggio():
    try: SALVA_FILE.unlink(missing_ok=True)
    except Exception: pass

# ===============================
# HALL OF FAME
# ===============================

def scrivi_hof(motivo, giocatori_totali, banco_bankroll):
    righe = []
    righe.append("======================================")
    righe.append(datetime.now().strftime("Partita chiusa il %Y-%m-%d %H:%M:%S"))
    righe.append(f"Motivo: {motivo}")
    righe.append(f"Bankroll banco finale: {fmt_euro(banco_bankroll)}")
    righe.append("Classifica finale (saldo):")
    finali = sorted(
        [(g.nome, g.saldo, ("CPU "+g.difficolta) if g.cpu else "Giocatore") for g in giocatori_totali],
        key=lambda x: x[1],
        reverse=True
    )
    vincitore = finali[0]
    for nome, saldo, ruolo in finali:
        righe.append(f" - {nome} ({ruolo}): {fmt_euro(saldo)}")
    righe.append(f"Vincitore: {vincitore[0]} con {fmt_euro(vincitore[1])}")
    righe.append("======================================\n")
    with open(HALL_OF_FAME_FILE, "a") as f:
        f.write("\n".join(righe))

def chiudi_partita(motivo, giocatori_totali, banco_bankroll, save_on_exit=False):
    scrivi_hof(motivo, giocatori_totali, banco_bankroll)
    if save_on_exit:
        try: salva_stato(giocatori_totali, Mazzo(), banco_bankroll)
        except: pass
    elimina_salvataggio()
    print("\n🏁 Partita terminata.")
    print(f"📜 Hall of Fame aggiornata: {HALL_OF_FAME_FILE.name}")
    sys.exit(0)

# ===============================
# CONTROLLI AZIONI (DOUBLE / SPLIT)
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
    if len(g.mani) >= MAX_SPLIT:
        return False, "Raggiunto limite massimo di split."
    return True, ""

# ===============================
# GRAFICA TAVOLO
# ===============================

def tavolo_grande_abbastanza():
    cols, rows = shutil.get_terminal_size(fallback=(80, 24))
    return cols >= 100 and rows >= 30, cols, rows

def render_hand_block_with_meta(mano, puntata=None, assicurazione=0, show_total=True, coperta=False):
    cards = mostra_carte_ascii(mano, coperta=coperta)
    width = block_width(cards)
    meta_lines = []
    if puntata is not None:
        meta_lines.append(center_text_line(f"Puntata: {puntata}€", width))
    if assicurazione > 0:
        meta_lines.append(center_text_line(f"Assicurazione: {assicurazione}€", width))
    if show_total:
        tot = calcola_punteggio(mano)
        meta_lines.append(center_text_line(f"Totale: {tot}", width))
    meta = "\n".join(meta_lines)
    return cards + "\n" + meta if meta else cards

def combine_blocks_horizontally(blocks, spacing=6):
    split_blocks = [b.split("\n") for b in blocks]
    height = max(len(b) for b in split_blocks)
    for b in split_blocks:
        while len(b) < height: b.append("")
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
    header = f"{giocatore.nome} ({'CPU ' + giocatore.difficolta if giocatore.cpu else 'Tu'}) [{fmt_euro(giocatore.saldo)}]"
    print(center_text_line(header, cols))
    hand_blocks = []
    for i, m in enumerate(giocatore.mani):
        hand_blocks.append(render_hand_block_with_meta(m, giocatore.puntate[i], giocatore.assicurazioni[i], show_total=True))
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

def mostra_tavolo_centrato(giocatori, banco, banco_bankroll, mostra_carta_coperta=True, pausa=True):
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
    banco_header = f"Banco [💰 {fmt_euro(banco_bankroll)}]"
    print(center_text_line(banco_header, cols))
    print_centered_block(render_hand_block_with_meta(banco, show_total=not mostra_carta_coperta, coperta=mostra_carta_coperta), cols)
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
# ANIMAZIONI (senza mostrare la 2ª carta del banco)
# ===============================

def anim_distribuzione_mano_iniziale(mazzo, giocatori, banco, salva_cb):
    # Primo giro a tutti
    for g in giocatori:
        print(f"→ Distribuisco a {g.nome}...")
        g.mani[0].append(mazzo.pesca())
        salva_cb()
        print(mostra_carte_ascii(g.mani[0]))
        time.sleep(0.45)
    # Prima carta al banco (visibile)
    print("→ Distribuisco al Banco...")
    banco.append(mazzo.pesca())
    salva_cb()
    print(mostra_carte_ascii([banco[0]]))
    time.sleep(0.55)
    # Secondo giro a tutti
    for g in giocatori:
        print(f"→ Distribuisco a {g.nome}...")
        g.mani[0].append(mazzo.pesca())
        salva_cb()
        print(mostra_carte_ascii(g.mani[0]))
        time.sleep(0.45)
    # Seconda carta al banco (NON viene mostrata!)
    print("→ Distribuisco al Banco (coperta)...")
    banco.append(mazzo.pesca())
    salva_cb()
    time.sleep(0.6)

# ===============================
# LOGICA DI GIOCO
# ===============================

def fase_assicurazione(giocatori, banco, salva_cb, banco_bankroll_ref):
    if not banco[0].startswith('A'):
        return False
    print("\n🛡️ Il banco mostra un Asso! Fase assicurazione.")
    has_bj = calcola_punteggio(banco) == 21
    for g in giocatori:
        for i in range(len(g.mani)):
            if g.puntate[i] <= 0:
                continue
            ass_max = g.puntate[i] // 2
            if g.saldo < ass_max:
                ass_max = g.saldo
            if ass_max <= 0:
                continue
            if g.cpu:
                if g.decide_assicurazione() and g.saldo >= ass_max:
                    g.assicurazioni[i] = ass_max
                    g.saldo -= ass_max
                    print(f"{g.nome} si assicura per {ass_max}€")
            else:
                while True:
                    try:
                        inp = input(f"{g.nome} (mano {i+1}), assicurazione (max {ass_max}€)? (0-{ass_max}): ") or "0"
                        ass = int(inp)
                        if 0 <= ass <= ass_max:
                            g.assicurazioni[i] = ass
                            g.saldo -= ass
                            break
                        print("Valore non valido.")
                    except ValueError:
                        print("Inserisci un numero valido.")
            salva_cb()
    # Se banco ha BJ, risolvi assicurazioni subito
    if has_bj:
        print("\n📢 Il banco ha Blackjack! Risoluzione assicurazioni.")
        for g in giocatori:
            for i in range(len(g.mani)):
                ass = g.assicurazioni[i]
                if ass > 0:
                    if has_bj:
                        vincita = ass * 2
                        g.saldo += vincita + ass  # 2:1 + rimborso
                        g.stats["assicurazioni_vinte"] += 1
                        banco_bankroll_ref[0] -= vincita
                        print(f"{g.nome} vince assicurazione: +{vincita}€")
                    else:
                        banco_bankroll_ref[0] += ass
        salva_cb()
    return has_bj

def turno_giocatore(mazzo, g, i, salva_cb, banco_prima_carta):
    mano = g.mani[i]
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

        can_dbl, why_dbl = check_double(g, i)
        can_spl, why_spl = check_split(g, i)
        can_surr = len(mano) == 2

        if g.cpu:
            time.sleep(0.6)
            valore = mano[0][:-1]
            if can_surr and g.decide_surrender(tot):
                print(f"{g.nome} si arrende.")
                g.stats["surrenders"] += 1
                g.saldo += g.puntate[i] // 2
                g.puntate[i] = -g.puntate[i] // 2  # Marca come surrender (perdita metà)
                salva_cb()
                return
            elif can_spl and g.decide_split(valore):
                c2 = mano.pop()
                g.mani.append([c2])
                g.puntate.append(g.puntate[i])
                g.assicurazioni.append(0)
                g.saldo -= g.puntate[i]
                mano.append(mazzo.pesca())
                g.mani[-1].append(mazzo.pesca())
                print(f"{g.nome} divide!")
                g.stats["splits"] += 1
                salva_cb()
                # Ricorsivo, ma con limite MAX_SPLIT
                return  # Uscirà e richiamerà per tutte le mani
            elif can_dbl and g.decide_double(tot):
                g.saldo -= g.puntate[i]
                g.puntate[i] *= 2
                mano.append(mazzo.pesca())
                print(f"{g.nome} raddoppia!")
                g.stats["doubles"] += 1
                salva_cb()
                return
            elif g.decide_pesca(tot):
                print(f"{g.nome} pesca.")
                mano.append(mazzo.pesca())
                salva_cb()
                continue
            else:
                print(f"{g.nome} sta.")
                return
        else:
            opzioni = ["[C]arta", "[S]tai"]
            if can_dbl: opzioni.append("[R]addoppia")
            if can_spl: opzioni.append("[D]ividi")
            if can_surr: opzioni.append("[U]rrenditi")
            sc = input(f"{', '.join(opzioni)} > ").lower().strip()

            if sc == "c":
                mano.append(mazzo.pesca())
                salva_cb()
            elif sc == "s":
                return
            elif sc == "r" and can_dbl:
                g.saldo -= g.puntate[i]
                g.puntate[i] *= 2
                mano.append(mazzo.pesca())
                print("Raddoppiato!")
                g.stats["doubles"] += 1
                salva_cb()
                return
            elif sc == "d" and can_spl:
                c2 = mano.pop()
                g.mani.append([c2])
                g.puntate.append(g.puntate[i])
                g.assicurazioni.append(0)
                g.saldo -= g.puntate[i]
                mano.append(mazzo.pesca())
                g.mani[-1].append(mazzo.pesca())
                print("✂️ Mano divisa!")
                g.stats["splits"] += 1
                salva_cb()
                return  # Uscirà e richiamerà per tutte le mani
            elif sc == "u" and can_surr:
                print("Ti arrendi.")
                g.stats["surrenders"] += 1
                g.saldo += g.puntate[i] // 2
                g.puntate[i] = -g.puntate[i] // 2  # Marca come surrender
                salva_cb()
                return
            else:
                print("Scelta non valida." if sc not in ["c","s","r","d","u"] else f"⛔ Non puoi: {why_dbl or why_spl}")

def turno_banco(mazzo, banco, salva_cb):
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
        if g.saldo <= 0:
            continue
        if g.cpu:
            scelte = [x for x in [10, 20, 50] if x <= g.saldo]
            if not scelte:
                continue
            g.puntate[0] = random.choice(scelte)
        else:
            while True:
                try:
                    inp = input(f"Puntata per {g.nome} (saldo {fmt_euro(g.saldo)}, min 1): ") or "10"
                    puntata = int(inp)
                    if puntata < 1 or puntata > g.saldo:
                        print("Puntata non valida.")
                        continue
                    g.puntate[0] = puntata
                    break
                except ValueError:
                    print("Inserisci un numero valido.")
        g.saldo -= g.puntate[0]
        salva_cb()
        print(f"{g.nome} punta {g.puntate[0]}€")

def applica_risultati_e_bankroll(giocatori, banco_totale, banco_bankroll, salva_cb, banco_has_bj):
    for g in giocatori:
        for i, mano in enumerate(g.mani):
            puntata = g.puntate[i]
            if puntata == 0:
                continue
            # Gestisci surrender (puntata negativa)
            if puntata < 0:
                banco_bankroll -= puntata  # Banco vince metà (negativo diventa positivo)
                g.stats["sconfitte"] += 1
                g.stats["guadagno"] += puntata
                continue
            ass = g.assicurazioni[i]
            if ass > 0 and banco_has_bj:
                # Già gestito in fase_assicurazione
                continue
            pg = calcola_punteggio(mano)
            g.stats["mani"] += 1
            is_bj = pg == 21 and len(mano) == 2
            if is_bj:
                g.stats["blackjacks"] += 1

            if pg > 21:
                g.stats["sconfitte"] += 1
                g.stats["guadagno"] -= puntata
                banco_bankroll += puntata
                msg = f"{g.nome} sballa. (-{puntata}€)"
            elif banco_has_bj:
                g.stats["sconfitte"] += 1
                g.stats["guadagno"] -= puntata
                banco_bankroll += puntata
                msg = f"{g.nome} perde contro BJ banco. (-{puntata}€)"
            elif banco_totale > 21 or pg > banco_totale:
                multiplier = 1.5 if is_bj else 1
                vincita = int(puntata * multiplier)
                g.saldo += puntata + vincita
                g.stats["vittorie"] += 1
                g.stats["guadagno"] += vincita
                banco_bankroll -= vincita
                msg = f"{g.nome} VINCE{' BJ' if is_bj else ''}! (+{vincita}€)"
            elif pg == banco_totale:
                g.saldo += puntata
                g.stats["pareggi"] += 1
                msg = f"{g.nome} PAREGGIA. (+0€)"
            else:
                g.stats["sconfitte"] += 1
                g.stats["guadagno"] -= puntata
                banco_bankroll += puntata
                msg = f"{g.nome} perde. (-{puntata}€)"

            print(f"{Fore.GREEN if 'VINCE' in msg else Fore.YELLOW if 'PAREGGIA' in msg else Fore.RED}{msg}{Style.RESET_ALL}")
            salva_cb()
    return banco_bankroll

def rimuovi_cpu_senza_soldi(giocatori_totali):
    iniz = len(giocatori_totali)
    restanti = [g for g in giocatori_totali if (not g.cpu) or (g.cpu and g.saldo > 0)]
    rimossi = iniz - len(restanti)
    return restanti, rimossi

def tutti_giocatori_senza_soldi(giocatori_totali):
    return all(g.saldo <= 0 for g in giocatori_totali)

def mostra_statistiche(giocatori_totali):
    print("\n📊 Statistiche dettagliate:")
    for g in sorted(giocatori_totali, key=lambda x: x.saldo, reverse=True):
        if g.stats["mani"] == 0:
            continue
        ruolo = f"CPU {g.difficolta}" if g.cpu else "Giocatore"
        wr = (g.stats["vittorie"] / g.stats["mani"]) * 100 if g.stats["mani"] > 0 else 0
        print(f"{g.nome} ({ruolo}):")
        print(f" - Mani giocate: {g.stats['mani']}")
        print(f" - Win Rate: {wr:.1f}%")
        print(f" - Vittorie: {g.stats['vittorie']} | Sconfitte: {g.stats['sconfitte']} | Pareggi: {g.stats['pareggi']}")
        print(f" - Blackjacks: {g.stats['blackjacks']} | Sballi: {g.stats['sballi']}")
        print(f" - Doubles: {g.stats['doubles']} | Splits: {g.stats['splits']} | Surrenders: {g.stats['surrenders']}")
        print(f" - Assicurazioni vinte: {g.stats['assicurazioni_vinte']}")
        print(f" - Guadagno netto: {fmt_euro(g.stats['guadagno'])}")
        print()

def gioca_mano(mazzo, giocatori, salva_cb, giocatori_totali, banco_bankroll_ref):
    # 1) PUNTATE
    fase_puntate(giocatori, salva_cb)

    # 2) DISTRIBUZIONE
    banco = []
    print("\n🎬 Distribuzione carte...")
    anim_distribuzione_mano_iniziale(mazzo, giocatori, banco, salva_cb)

    # 3) CHECK BJ BANCO e ASSICURAZIONE
    banco_has_bj = fase_assicurazione(giocatori, banco, salva_cb, banco_bankroll_ref)
    if banco_has_bj:
        # Risolvi immediatamente se banco ha BJ
        print("\n--- Risoluzione immediata (Banco BJ) ---")
        mostra_tavolo_centrato(giocatori, banco, banco_bankroll_ref[0], mostra_carta_coperta=False, pausa=False)
        banco_bankroll_ref[0] = applica_risultati_e_bankroll(giocatori, 21, banco_bankroll_ref[0], salva_cb, True)
        return

    # 4) TAVOLO INIZIALE
    mostrato = mostra_tavolo_centrato(giocatori, banco, banco_bankroll_ref[0], mostra_carta_coperta=True, pausa=True)
    if not mostrato:
        print(f"\n(Modalità compatta: banco {fmt_euro(banco_bankroll_ref[0])})")

    # 5) TURNI GIOCATORI
    for g in giocatori:
        # Turno per ogni mano (gestisce split internamente)
        i = 0
        while i < len(g.mani):
            turno_giocatore(mazzo, g, i, salva_cb, banco[0])
            i += 1

    # 6) BANCO
    print("\n--- Turno del Banco ---")
    print("Rivelazione carta coperta...")
    print(mostra_carte_ascii(banco))
    pb = turno_banco(mazzo, banco, salva_cb)

    # 7) TAVOLO FINALE
    mostra_tavolo_centrato(giocatori, banco, banco_bankroll_ref[0], mostra_carta_coperta=False, pausa=True)
    print(f"Banco ({pb})")

    # 8) RISULTATI + bankroll banco
    banco_bankroll_ref[0] = applica_risultati_e_bankroll(giocatori, pb, banco_bankroll_ref[0], salva_cb, False)

# ===============================
# MAIN LOOP
# ===============================

def main():
    clear_screen()
    print("🃏 BLACKJACK MADE BY CHATGPT 🃏")

    stato = carica_stato()
    giocatori_totali = []
    mazzo = Mazzo()
    if stato:
        for g in stato.get("giocatori", []):
            giocatori_totali.append(
                Giocatore(
                    g.get("nome","Giocatore"),
                    max(int(g.get("saldo", START_SALDO)), 0),
                    g.get("cpu",False),
                    g.get("difficolta","equilibrata"),
                    g.get("stats",None),
                    g.get("mani",[[]]),
                    g.get("puntate",[0]),
                    g.get("assicurazioni",[0])
                )
            )
        mazzo.mazzo = stato.get("mazzo", crea_mazzo())
        mazzo.usate = stato.get("usate", 0)
        banco_bankroll = stato.get("banco_bankroll", BANCO_START_BANKROLL)
        print(f"✅ Stato precedente caricato. Banco attuale: {fmt_euro(banco_bankroll)}")
    else:
        nome = input("Inserisci il tuo nome: ") or "Giocatore"
        giocatori_totali.append(Giocatore(nome, saldo=START_SALDO))
        usati = set()
        for i in range(MAX_CPU_GLOBALI):
            disponibili = [n for n in NOMI_REALI if n not in usati] or NOMI_REALI[:]
            nome_cpu = random.choice(disponibili)
            usati.add(nome_cpu)
            diff = random.choice(DIFFICOLTA_CPU)
            giocatori_totali.append(Giocatore(nome_cpu, saldo=START_SALDO, cpu=True, difficolta=diff))
        banco_bankroll = BANCO_START_BANKROLL
        salva_stato(giocatori_totali, mazzo, banco_bankroll)

    def salva_cb():
        salva_stato(giocatori_totali, mazzo, banco_bankroll)

    # loop partite
    while True:
        # Rimuovi CPU con saldo 0
        giocatori_totali, rimossi = rimuovi_cpu_senza_soldi(giocatori_totali)
        if rimossi:
            print(f"\n♻️ CPU eliminate per saldo 0: {rimossi}")

        # Se tutti (umano + CPU) a 0 → fine vera (HOF)
        if tutti_giocatori_senza_soldi(giocatori_totali):
            print("\n💀 Tutti i giocatori sono a 0€. Vince il banco.")
            chiudi_partita("Tutti i giocatori a 0€", giocatori_totali, banco_bankroll)

        umano = next(g for g in giocatori_totali if not g.cpu)
        cpu_candidati = [g for g in giocatori_totali if g.cpu and g.saldo > 0]
        n_cpu = random.randint(0, min(4, len(cpu_candidati)))
        cpu_in_tavolo = random.sample(cpu_candidati, k=n_cpu)
        giocatori = [umano] + cpu_in_tavolo[:MAX_GIOCATORI_TAVOLO-1]

        print(f"\n🎲 Nuova mano! Partecipanti: {', '.join(g.nome for g in giocatori)}")
        print(f"💵 Bankroll del banco: {fmt_euro(banco_bankroll)}")

        banco_bankroll_ref = [banco_bankroll]
        gioca_mano(mazzo, giocatori, salva_cb, giocatori_totali, banco_bankroll_ref)
        banco_bankroll = banco_bankroll_ref[0]
        salva_stato(giocatori_totali, mazzo, banco_bankroll)

        # Riepilogo saldi dopo la mano
        print("\n===== RIEPILOGO SALDI DOPO LA MANO =====")
        print(f"💵 Banco: {fmt_euro(banco_bankroll)}")
        attivi = sorted(giocatori_totali, key=lambda x: (x.saldo, x.nome), reverse=True)
        for g in attivi:
            stato = "" if g.saldo > 0 else " ❌ (eliminato)" if g.cpu else ""
            ruolo = f"CPU {g.difficolta}" if g.cpu else "Tu"
            print(f" - {g.nome} ({ruolo}): {fmt_euro(g.saldo)}{stato}")

        # Finali veri (HOF)
        if banco_bankroll <= 0:
            print("\n🏦 Il banco è a 0€! Il gioco termina.")
            chiudi_partita("Banco a 0€", giocatori_totali, banco_bankroll)

        if umano.saldo <= 0:
            print("\n💀 Hai finito i soldi!")
            chiudi_partita("Giocatore a 0€", giocatori_totali, banco_bankroll)

        # Mostra statistiche
        mostra_statistiche(giocatori_totali)

        # Uscita manuale → SALVA e basta (niente HOF)
        cont = input("\nVuoi continuare? (s/n) ").lower().strip()
        if cont != "s":
            print("\n💾 Partita salvata. Puoi riprendere più tardi.")
            salva_stato(giocatori_totali, mazzo, banco_bankroll)
            sys.exit(0)

if __name__ == "__main__":
    main()
