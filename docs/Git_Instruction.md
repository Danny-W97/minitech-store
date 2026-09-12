# Instrukcja Git dla Mini Order Platform

> Przewodnik krok po kroku: od pierwszego commita i inicjalizacji repozytorium po gałęzie, Pull Requesty, rozwiązywanie konfliktów oraz bezpieczne cofanie zmian z zachowaniem prywatności danych lokalnych (gitIgnoreOwn).

---

## Spis treści

1. [Złote zasady bezpieczeństwa repozytorium](#1-złote-zasady-bezpieczeństwa-repozytorium)
2. [Przed pierwszym commitem: github-check](#2-przed-pierwszym-commitem-github-check)
3. [Inicjalizacja i pierwszy commit](#3-inicjalizacja-i-pierwszy-commit)
4. [Podłączenie zdalnego repozytorium GitHub](#4-podłączenie-zdalnego-repozytorium-github)
5. [Praca na gałęziach (Git Workflow)](#5-praca-na-gałęziach-git-workflow)
6. [Pull Requesty i Code Review](#6-pull-requesty-i-code-review)
7. [Rozwiązywanie konfliktów (Merge Conflicts)](#7-rozwiązywanie-konfliktów-merge-conflicts)
8. [Bezpieczne cofanie i naprawianie zmian](#8-bezpieczne-cofanie-i-naprawianie-zmian)
9. [Ściąga najważniejszych komend](#9-ściąga-najważniejszych-komend)

---

## 1. Złote zasady bezpieczeństwa repozytorium

Przed jakimkolwiek commitem i pushem do GitHuba pamiętaj o kluczowych regułach projektu:

1. **Nigdy nie commituj sekretów i kluczy:**
   - Klucze age (`*.agekey`), hasła, tokeny JWT, certyfikaty SSL/TLS, pliki `.env` z hasłami.
   - Prawdziwe odszyfrowane sekrety (`*.env.sops`) lub pliki środowiskowe.
2. **Katalog `gitIgnoreOwn/` jest lokalny:**
   - Zawiera logi zadań, kopie zapasowe baz danych PostgreSQL, lokalne certyfikaty, raporty testów Playwright i cache.
   - Jest w całości wykluczony w `.gitignore` poza pustymi plikami `.gitkeep` i dokumentem `gitIgnoreOwn/README.md`.
3. **Pliki tymczasowe budowania:**
   - Katalogi `target/`, `.m2/`, `.idea/`, pliki `.pyc` i `__pycache__` są ignorowane.

---

## 2. Przed pierwszym commitem: github-check

Control Center posiada wbudowany skrypt audytujący czystość repozytorium przed publikacją:

```bash
bash scripts/control-center/control-center.sh github-check
```

Skrypt weryfikuje:
- czy nie ma niezaszyfrowanych sekretów w śledzonych plikach,
- czy `.gitignore` poprawnie chroni wrażliwe ścieżki,
- czy nie istnieją przypadkowo dodane pliki binarne z hasłami.

Sprawdź również status ignorowanych plików:

```bash
git status --short --ignored
```

---

## 3. Inicjalizacja i pierwszy commit

Jeśli repozytorium nie zostało jeszcze zainicjalizowane:

```bash
# 1. Inicjalizacja repozytorium git
git init -b main

# 2. Weryfikacja stanu
git status

# 3. Dodanie plików projektu (z poszanowaniem .gitignore)
git add .

# 4. Pierwszy commit
git commit -m "feat: initial commit of Mini Order Platform"
```

---

## 4. Podłączenie zdalnego repozytorium GitHub

Po utworzeniu pustego (bez README i bez `.gitignore`) repozytorium na GitHub:

```bash
# Dodanie zdalnego adresu URL (np. SSH lub HTTPS)
git remote add origin git@github.com:TWOJ_LOGIN/mini-order-platform.git

# Wypchnięcie głównej gałęzi
git push -u origin main
```

---

## 5. Praca na gałęziach (Git Workflow)

Zalecany model pracy to **Feature Branch Workflow**:
1. Gałąź `main` jest stabilna i zawsze przechodzi testy (`smoke`, `e2e`, `ui-test`).
2. Nowe funkcje i poprawki powstają na osobnych gałęziach:
   - `feature/nazwa-funkcji`
   - `fix/nazwa-naprawy`
   - `docs/aktualizacja-opisu`

### Tworzenie i praca na gałęzi:

```bash
# 1. Przełączenie na aktualny main
git checkout main
git pull origin main

# 2. Utworzenie nowej gałęzi
git checkout -b feature/nowa-promocja

# 3. Praca, edycja kodu i testy
bash scripts/control-center/control-center.sh smoke

# 4. Zapisanie zmian
git add promotion-service/
git commit -m "feat(promotion): dodano nowy mechanizm kuponow lojalnosciowych"

# 5. Wypchnięcie gałęzi na GitHub
git push -u origin feature/nowa-promocja
```

---

## 6. Pull Requesty i Code Review

1. Po wypchnięciu gałęzi wejdź na stronę repozytorium w GitHub.
2. Kliknij **Compare & pull request**.
3. Opisz zmiany:
   - Jaki problem rozwiązuje PR?
   - Jakie mikroserwisy zostały zmienione?
   - Jakie testy zostały wykonane (np. `app-verify`, `e2e`, `ui-test`)?
4. Po zatwierdzeniu review, scal zmiany (Merge PR) i usuń gałąź zdalną.
5. Na maszynie lokalnej zsynchronizuj `main`:

```bash
git checkout main
git pull origin main
git branch -d feature/nowa-promocja
```

---

## 7. Rozwiązywanie konfliktów (Merge Conflicts)

Gdy na gałęzi `main` pojawiły się zmiany dotykające tych samych linii kodu:

```bash
# Będąc na swojej gałęzi:
git fetch origin
git merge origin/main
```

Jeśli Git zgłosi konflikt:
1. Otwórz pliki ze statusem `both modified` w edytorze.
2. Znajdź sekcje oznaczone `<<<<<<<`, `=======`, `>>>>>>>`.
3. Wybierz poprawny kod, usuń znaczniki konfliktów.
4. Uruchom testy, aby upewnić się, że kod działa:
   ```bash
   bash scripts/control-center/control-center.sh app-verify
   ```
5. Zakończ scalanie:
   ```bash
   git add <rozwiazane-pliki>
   git commit -m "chore: resolve merge conflict with main"
   git push
   ```

---

## 8. Bezpieczne cofanie i naprawianie zmian

| Sytuacja | Bezpieczne polecenie | Działanie |
|:---|:---|:---|
| **Cofnięcie zmian w pliku przed dodaniem** | `git restore <plik>` | Przywraca stan pliku z ostatniego commita. |
| **Wycofanie pliku ze stage** | `git restore --staged <plik>` | Usuwa plik ze stage, zachowując wprowadzone zmiany. |
| **Poprawa ostatniego commita** | `git commit --amend -m "nowy opis"` | Poprawia ostatni lokalny commit (przed push). |
| **Cofnięcie zatwierdzonego commita** | `git revert <commit-hash>` | Tworzy nowy commit odwracający poprzednie zmiany. |
| **Schowanie nieskończonej pracy** | `git stash` / `git stash pop` | Zapisuje zmiany w schowku i przywraca je później. |

---

## 9. Ściąga najważniejszych komend

```bash
# Sprawdzenie stanu
git status

# Historia commitów
git log --oneline --graph --decorate -n 15

# Porównanie zmian roboczych
git diff

# Porównanie zmian w stage
git diff --staged

# Sprawdzenie remote
git remote -v
```
