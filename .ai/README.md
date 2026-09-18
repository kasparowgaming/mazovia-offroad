# Mazovia Gemini-Local Qwen Workflow

## Co robi ten system?
Ten system pozwala na współpracę agenta Gemini (Głównego Inżyniera) z lokalnym modelem Qwen (Lokalnym Pracownikiem) w repozytorium Mazovia Offroad. Wszystko opiera się na plikach i systemie Git. Gemini decyduje co zrobić, przygotowuje zadania, a Qwen modyfikuje pliki w ramach ograniczonych zadań (IMPLEMENTATION) lub tylko czyta i przygotowuje raporty (REPORT_ONLY).

## Podział ról
- **Gemini**: Analizuje architekturę, decyduje o zmianach, tworzy pliki `.ai/local_agent/TASK.md`, ocenia wyniki w `REVIEW.md`.
- **Lokalny Qwen**: Otrzymuje zadania i realizuje je. Edytuje kod, uruchamia testy, a następnie commituje i przygotowuje `.ai/local_agent/RESULT.md`.

## Pliki `.ai`
- `PROJECT_STATE.md` i `CURRENT_TASK.md`: Stan i obecne zadania dla Gemini.
- `local_agent/TASK.md`: Ograniczone zadanie dla modelu Qwen. Określa co ma zrobić, oraz listę dozwolonych plików (FILES_ALLOWED).
- `local_agent/CHECKPOINT.md`: Postępy lokalnego agenta, aktualizowane co kilka kroków.
- `local_agent/RESULT.md`: Końcowy rezultat wykonanego zadania przez Qwen (zawsze w ścisłym schemacie).
- `archive/`: Archiwum po udanych i zrecenzowanych zadaniach.

## Jak delegować?
Gemini edytuje pliki w `.ai/local_agent`, ustawia `ACTIVE_AGENT: LOCAL` w `PROJECT_STATE.md` i zapisuje szczegóły w `TASK.md`. Następnie użytkownik uruchamia skrypt PowerShell.

## Uruchamianie lokalnego agenta
Aby wystartować agenta Qwen, w terminalu PowerShell wykonaj:
```powershell
.\scripts\local-agent.ps1
```
Skrypt ten nie może być uruchamiany w tym samym czasie, gdy Gemini edytuje repozytorium!

## Sprawdzanie statusu
Aby łatwo sprawdzić obecny status:
```powershell
.\scripts\agent-status.ps1
```

## Szczegóły techniczne i zabezpieczenia
- **BASE_COMMIT i EXPECTED_BRANCH**: Chronią przed wykonaniem zadania na innej gałęzi lub na zmodyfikowanym kodzie niż ten, dla którego Gemini wyznaczyło zadanie.
- **Kontekst Qwen**: Qwen zaczyna z czystym kontekstem (tylko 4 obowiązkowe pliki) aby nie marnować limitu (16384 tokenów). Inne pliki jak `DECISIONS.md` czyści i ładuje tylko na wyraźne żądanie (CONTEXT_FILES_REQUIRED).
- **INVALID_RESULT_SCHEMA**: Zabezpieczenie przed uszkodzeniem meta-plików. Jeśli `RESULT.md` nie trzyma schematu, skrypt zawraca kontrolę do Gemini (oczekiwany manualny przegląd), nie próbuje naprawiać tego samodzielnie.
- **Sesje i NO_PROGRESS**: Jeśli zadanie wymaga więcej niż jednej sesji (MAX_STEPS_PER_SESSION), Qwen tworzy `CHECKPOINT.md` i wyłącza się. Skrypt weryfikuje czy nastąpił postęp przed włączeniem kolejnej sesji.
- **Odłączony internet (limit Gemini)**: Lokalny agent operuje tylko na repozytorium - po przerwaniu Gemini lub utracie łączności, cała wiedza znajduje się w plikach na dysku i strukturze Git.
- **Wznawianie po BLOCKED**: Qwen może ustawić `STATUS: BLOCKED`. Wtedy Gemini musi dokonać przeglądu, pomóc Qwenowi (nowy plik TASK.md) lub wycofać zmiany.
- **Przerwane sesje**: Jeśli z powodu błędu sesja Qwena zostanie przerwana (np. przy commitowaniu), Git wstrzymuje się (diffy w staging), a manualny wgląd (lub wgląd przez Gemini) musi zadecydować co dalej, używając poleceń jak `git status --short`.

## Zakończenie zadań i archiwizacja
Po weryfikacji zadania przez Gemini (status ACCEPTED), pliki robocze z `local_agent` wędrują do `archive/<TASK_ID>`. Szablony w `.ai/local_agent/` zostają wyczyszczone do domyślnych pustych wartości i wszystko wraca do punktu wyjścia (IDLE).
