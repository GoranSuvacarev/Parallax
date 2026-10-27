# Parallax: Visual 3-Way Spatial Merge Tool for Game Scenes
 
> **Tehnološki stek:** Clojure / ClojureScript (`shadow-cljs`), Node.js, Web/SVG, Godot 4.x  


---

## Poglavlje 1: Uvod i Problem: Zašto Git Pada na Grafovima Scena?

Standardni sistemi za kontrolu verzija (Git/VCS) posmatraju repozitorijume kao tokove običnih tekstualnih linija. Međutim, scene u savremenim endžinima za video igre (poput Godot 4 formata `.tscn`) su **hijerarhijski prostorni grafovi (Scene Graphs)**.

Ovaj raskorak dovodi do tri fundamentalna problema u timskom razvoju igara:
1. **Lažni tekstualni konflikti:** Ako Developer A pomeri neprijatelja na mapi, a Developer B promeni parametar skripte tog istog neprijatelja, Git prijavljuje konflikt linija teksta, iako su izmene semantički potpuno disjunktne i bezbedne za spajanje.
2. **Tihe prostorne regresije (Silent Semantic Bugs):** Git ne poseduje geometrijsku svest. Ako Developer A pomeri zid na koordinatu $(X, Y)$, a Developer B postavi prepreku na isto to mesto, Git izvršava automatski *clean merge*. U igri nastaje bag gde dva fizička objekta kolidiraju u istoj tački prostora.
3. **Odsustvo prostornog konteksta:** Klasični diff alati prikazuju tekstualne razlike koda. Developer nema vizuelni uvid *gde* se objekat nalazio u ravni nivoa, *gde* je pomeren i kakav to uticaj ima na okolinu.

**Cilj projekta Parallax:** Implementacija specijalizovanog alata koji parsira scene u stablo nepromenljivih podataka (AST/IR), primenjuje trostrano spajanje (3-way merge) na nivou pojedinačnih atributa uz očuvanje stabilnog identiteta čvorova, vizuelno detektuje prostorna preklapanja i pruža 2D CAD blueprint radno okruženje u browseru.

---

## Poglavlje 2: Arhitektura Sistema na Makro-Nivou

Sistem je koncipiran kao *Headless CLI Bridge* koji se prirodno integriše u standardni Git ekosistem putem komande `git mergetool`, uz Web interfejs za rešavanje prostornih konflikata.

```text
[ Git CLI ] (detektuje konflikt u level.tscn i poziva: git mergetool)
     │
     ▼
[ CLI Runner (Node.js / ClojureScript) ]
     │  1. Čita tri verzije fajla: Base (zajednički predak), Ours i Theirs
     │  2. Pokreće State-Machine Parser i konvertuje .tscn u Clojure EDN
     │  3. Startuje privremeni lokalni server i automatski otvara browser
     ▼
[ Core Merge Engine (Čiste Clojure Funkcije) ]
     │  1. Primenjuje formalnu matricu odlučivanja za svaki čvor
     │  2. Računa AABB preseke (detekcija mekih prostornih konflikata)
     ▼
[ Web UI Viewport (Reagent + SVG) ]
     │  1. Iscrtava 2D Blueprint nivoa u koordinatnom prostoru igre
     │  2. Prikazuje "ghosting" senke prethodnog stanja i vektore kretanja
     │  3. Developer klikom u prostoru bira željenu varijantu
     ▼
[ Serijalizator nazad u Disk ]
     │  1. Rekonstruiše validan Godot 4 .tscn format
     │  2. Gasi privremeni server i vraća Git-u status kod 0 (Conflict Resolved)
```

---

## Poglavlje 3: Matrica Odlučivanja: Per-Node State Machine

Za svaki jedinstveni identifikator čvora (`unique_id` ili stabilna putanja) prisutan u uniji skupova $(Base \cup Ours \cup Theirs)$, algoritam primenjuje strogo formalizovano grananje:

```text
                      +-------------------------------------------------------------+
                      |                 Za svaki ID u (Base ∪ Ours ∪ Theirs)        |
                      +-------------------------------------------------------------+
                                                     |
                 +-----------------------------------+-----------------------------------+
                 |                                   |                                   |
                 v                                   v                                   v
          [ Samo u Base ]                      [ U Base i jednoj grani ]           [ Samo u Ours i Theirs ]
      (Obrisano u obe grane)                  (Jedan obrisao, drugi menjao)           (Oboje dodali novi čvor)
                 |                                   |                                   |
                 v                                   v                         +---------+---------+
          🟢 OBRISANO                       🔴 STRUKTURNI KONFLIKT             v                   v
          (Auto-resolve)                     (Delete/Modify Conflict)     Isti podaci?        Različiti?
                                                                         (Auto-merge)      🔴 STRUKTURNI
                                                                                              KONFLIKT
                                                     |
                                                     |
                                                     v
                             +-----------------------------------------------+
                             |    Čvor postoji u sva tri stanja (B, O, T)    |
                             +-----------------------------------------------+
                                                     |
                 +-------------------+---------------+-------------------+
                 |                   |               |                   |
                 v                   v               v                   v
           Nisu menjali       Menjao SAMO Ours   Menjao SAMO Theirs   Menjali i Ours i Theirs
                 |                   |               |                   |
                 v                   v               v                   v
            Zadrži Base          Prihvati Ours   Prihvati Theirs   PROPERTY-LEVEL DIFF
            (Bez izmene)         (Auto-merge)     (Auto-merge)           |
                                                                         |
                      +--------------------------------------------------+
                      v
        +---------------------------------------------------------------------------------+
        |                    Analiza svojstava čvora (Transform, Properties)             |
        +---------------------------------------------------------------------------------+
                      |                                                                   |
                      v                                                                   v
           Menjali različita polja                                               Menjali isto polje
     (Npr. Ours menjao Position, Theirs brzinu)                         (Oboje promenili Position na [x,y])
                      |                                                                   |
                      v                                                                   v
             🟢 AUTO-MERGE SVOJSTAVA                                             🔴 VREDNOSNI KONFLIKT
            (Spajaju se obe izmene)                                             (Mora čovek da odabere)
```

---

## Poglavlje 4: Jezgro Sistema: Čista Clojure 3-Way Merge Funkcija

Ulaz su nepromenljive mape, a izlaz je deterministički podeljen na automatski spojena polja (`:resolved`) i polja u konfliktu (`:conflicts`).

```clojure
(ns parallax.core
  (:require [clojure.set :as set]))

(defn merge-node-3way
  "Izvršava semantičko trostrano spajanje svojstava jednog čvora scene.
   
   Ulaz:
     base, ours, theirs - Clojure mape koje predstavljaju stanje atributa
   Izlaz:
     {:resolved  {...}  ;; Mapa uspešno i automatski spojenih svojstava
      :conflicts [...]} ;; Lista mapa sa poljima gde je detektovan konflikt"
  [base ours theirs]
  (let [;; Skup svih ključeva svojstava prisutnih u bilo kojoj od tri verzije
        all-keys (set/union (set (keys base))
                            (set (keys ours))
                            (set (keys theirs)))]
    (reduce
     (fn [acc k]
       (let [b (get base k)
             o (get ours k)
             t (get theirs k)]
         (cond
           ;; Slučaj 1: Nema promene, ili su obe grane postavile IDENTIČNU novu vrednost
           (= o t)
           (assoc-in acc [:resolved k] o)

           ;; Slučaj 2: Promenu je napravio SAMO 'Ours' (Theirs je nepromenjen u odnosu na Base)
           (= t b)
           (assoc-in acc [:resolved k] o)

           ;; Slučaj 3: Promenu je napravio SAMO 'Theirs' (Ours je nepromenjen u odnosu na Base)
           (= o b)
           (assoc-in acc [:resolved k] t)

           ;; Slučaj 4: Oba developera su menjala ISTO polje na RAZLIČITE vrednosti -> KONFLIKT
           :else
           (update acc :conflicts conj {:field   k
                                        :base    b
                                        :ours    o
                                        :theirs  t}))))
     {:resolved {} :conflicts []}
     all-keys)))
```

---

## Poglavlje 5: Verifikacioni Test: Demonstracija na Godot 4 Sceni

Testiranje koncepta na realnom primeru čvora `NPCLeft` (`unique_id = 884256495`) iz 2D top-down pucačine:

### Scenario:
* **Base:** Objekat je na koordinatama `[358.0, 132.0]`, ima animaciju `"default"` i brzinu `6.0`.
* **Ours:** Developer vrši *level-design* izmenu i pomera objekat na `[400.0, 132.0]`.
* **Theirs:** Drugi developer radi na balansu mehanike, menja animaciju u `"walk"` i brzinu na `10.0`.

### REPL Izvršavanje:

```clojure
;; 1. Definicija ulaznih podataka (AST čvora)
(def base-node
  {:id 884256495
   :name "NPCLeft"
   :position [358.0 132.0]
   :animation "default"
   :speed 6.0})

(def ours-node
  {:id 884256495
   :name "NPCLeft"
   :position [400.0 132.0]  ;; Prostorna izmena
   :animation "default"
   :speed 6.0})

(def theirs-node
  {:id 884256495
   :name "NPCLeft"
   :position [358.0 132.0]
   :animation "walk"        ;; Logička izmena
   :speed 10.0})

;; 2. Poziv funkcije
(merge-node-3way base-node ours-node theirs-node)
```

### Izlaz iz funkcije (Evaluacija):

```clojure
;; Git bi na ovom fajlu prijavio tekstualni konflikt.
;; Naš Core algoritam prepoznaje nezavisnost izmena i vrši savršen clean merge:
{:resolved
 {:id 884256495
  :name "NPCLeft"
  :position [400.0 132.0]   ;; <-- Automatski prihvaćena nova pozicija
  :animation "walk"         ;; <-- Automatski spojena nova animacija
  :speed 10.0}              ;; <-- Automatski spojena nova brzina
 :conflicts []}             ;; 0 konflikata!
```

Ako bi oba developera promenila `:position` na različite koordinate, polje `:conflicts` bi vratilo struktuirane podatke sa starom i dvema novim pozicijama, spremne za slanje u SVG Viewport radi vizuelnog odabira.

---

## Poglavlje 6: Plan Daljeg Razvoja (Inženjerska Mapa Puta)

Razvoj celokupnog seminarskog rada podeljen je u pet diskretnih faza:

### Faza 1: State-Machine Parser i Serijalizator (`.tscn` ⟷ EDN)
* Implementacija linijskog parsera stanja u ClojureScript-u za Godot 4 format.
* Mapiranje `[node ...]` deklaracija u kanonski EDN format uz čuvanje hijerarhije (`parent`) i primarnih ključeva (`unique_id`).
* Implementacija reverznog serijalizatora koji rekonstruiše `.tscn` sintaksu bez oštećenja eksternih resursa (`ext_resource`, `sub_resource`).

### Faza 2: Full Scene Graph Merge Engine
* Proširenje PoC funkcije sa nivoa pojedinačnog čvora na celokupan graf scene.
* Rešavanje topoloških promena u stablu (dodavanje novih podstabala, brisanje i *Delete/Modify* konflikti).
* Podrška za instancirane pod-scene (`Sub-scenes / Prefabs`) po principu crne kutije (*Black-Box Proxy*).

### Faza 3: Modul za Detekciju Prostornih Konflikata (Spatial Engine)
* Implementacija funkcije za AABB (*Axis-Aligned Bounding Box*) presecanje pravougaonika u 2D ravni.
* Algoritam za prepoznavanje "mekih" konflikata: označavanje upozorenja kada dva različita objekta nakon automatskog spajanja kolidiraju u prostoru.

### Faza 4: Interaktivni Web UI (Reagent + SVG Blueprint Viewport)
* Razvoj šematizovanog CAD radnog prostora u browseru korišćenjem nativnog SVG-a (precizno mapiranje Godot koordinata i rotacija).
* Implementacija "ghosting" tehnike: prikaz originalne pozicije objekta (siva isprekidana linija), pozicije iz `Ours` grane (plavi okvir i vektor) i `Theirs` grane (narandžasti okvir).
* Interaktivni UX: Korisnik jednim klikom na strelicu ili okvir u 2D prostoru bira koje prostorno rešenje usvaja.

### Faza 5: Git CLI Integracija i Verifikacija
* Pakovanje alata putem `shadow-cljs` u izvršnu skriptu koja se registruje kao `git mergetool`.
* Verifikacija i testiranje u praksi na stvarnim scenama 2D top-down pucačine u Godot 4 endžinu.
* Pisanje završnog teorijskog rada sa komparativnom analizom i merenjem efikasnosti spajanja.
```
