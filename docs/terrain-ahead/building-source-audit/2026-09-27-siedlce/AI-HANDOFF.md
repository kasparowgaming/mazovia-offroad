# Przekazanie analizy kolejnemu modelowi AI

Przeczytaj `REPORT.md`, `metrics.json` i `area.json` w tym folderze. Obejrzyj `comparison.png`. To wykonany audyt rzeczywistych danych OSM, EGiB i LoD1 wokół testowego GPX w Siedlcach, nie implementacja rendererów ani pomiar telefonu.

Wynik: OSM 530 obiektów (2 wysokości, 371 kondygnacji), EGiB 464 (brak wysokości), LoD1 371 (371 wysokości, źródło wysokości 2023, starsze wersje obrysów). 346 obiektów OSM ma dopasowanie do LoD1 przy IoU >= 0,5. Nie traktuj niedopasowanych obiektów jako dowiedzionych błędów. Nie przypisuj wysokości automatycznie po najbliższym środku.

Rekomendacja: OSM jako baza obrysów; zachowanie wysokości/kondygnacji w kaflach; opcjonalne wzbogacenie wysokościami LoD1 po kontroli geometrii i aktualności; EGiB do kontroli/uzupełnienia. BDOT500 nie był mierzony. Nie ma dowodu, że jest potrzebny na start.

W projekcie `C:\AI_Projects\MazoviaOffroad` sprawdź aktualny `tools/tiles/process.lua` (warstwa building obecnie traci atrybuty wysokości) i `app/src/main/assets/mapstyles/mazovia_offroad_v1.json` (płaski fill). Przed zmianami przeczytaj aktualne instrukcje projektu i `docs/terrain-ahead/DESIGN.md`; w repozytorium trwa równoległa praca, nie nadpisuj cudzych zmian. Ten audyt nie upoważnia sam w sobie do zmiany zakresu produktu ani produkcyjnego pipeline'u.

Wcześniejsza wizualizacja `docs/terrain-ahead/visualizations/driver-view/index.html` nadal korzysta z syntetycznej sceny. Nie twierdź, że dane z tego audytu zostały już do niej włączone.

Jeżeli użytkownik zleci wdrożenie, zacznij od małego rzeczywistego wycinka i pomiaru na docelowym urządzeniu. Zachowaj źródło, datę i jakość wysokości; odróżniaj wysokość zmierzoną/modelową od szacowanej z kondygnacji. Uzgodnij pionowy układ odniesienia z DEM. Pamiętaj o autorstwie/licencjach i ograniczeniach opisanych w raporcie.
