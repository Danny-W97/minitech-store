# Skrypty projektu

Wszystkie skrypty uruchomieniowe projektu znajdują się w tym katalogu. Główne
wejścia do Control Center to:

- `start.sh` — uruchomienie panelu z WSL/Linux/macOS;
- `start.ps1` — uruchomienie panelu z Windows w osobnym oknie WSL;
- `control-center/control-center.sh` — właściwy panel i tryb poleceń;
- `control-center/ide-run.*` oraz `control-center/ide-environment.*` — integracja z IDE;
- `qa/` — testy jakościowe i E2E;
- `backup/` — backupy PostgreSQL;
- `deploy/` — lokalne budowanie obrazów Docker.

Przykłady uruchomienia z katalogu głównego projektu:

```bash
bash scripts/start.sh
bash scripts/control-center/control-center.sh health all
```

```powershell
.\scripts\start.ps1
```

Dokumentacja skryptów i Control Center znajduje się w głównych podręcznikach projektu:

- [General_Instruction.md — Control Center](../docs/General_Instruction.md#zacznij-tutaj-control-center)
- [General_Instruction_EN.md — Control Center (EN)](../docs/General_Instruction_EN.md#start-here-control-center)

Ten plik pozostaje jako wskaźnik dla osób szukających README w katalogu skryptów.
