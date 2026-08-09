# AllItems – Build-Anleitung

Dieses Sandbox-System hat keinen Zugriff auf das PaperMC-Maven-Repository,
deshalb kann ich die .jar hier nicht selbst kompilieren. Der komplette,
fertige Quellcode liegt aber bereit – das Bauen dauert bei dir lokal nur
einen Befehl.

## Versions-Kompatibilität
Der gesamte Plugin-Code steckt in **einer** Datei:
`src/main/java/de/futania/allitems/AllItemsPlugin.java`.

Du musst das Plugin nur **einmal** bauen. Die fertige .jar erkennt beim
ersten Start selbst, auf welcher Minecraft-Version sie läuft, und baut
daraus automatisch den passenden Item-Pool (kein Neukompilieren nötig, auch
nicht nach einem Server-Update). Sinnvoll unterstützt wird alles ab
Minecraft **1.13** aufwärts (inkl. aller zukünftigen Versionen) – das ist die
Version, seit der Items die modernen Namen haben, mit denen das Plugin
arbeitet. Ältere Versionen (vor der "Item-Flattening"-Umstellung) müssten
ein komplett anderes Item-Modell nutzen, das deckt dieses Plugin bewusst
nicht ab, da davon heute praktisch kein Server mehr betroffen ist.

## Voraussetzungen
- **Java 21 (JDK)**, z. B. [Temurin 21](https://adoptium.net/de/temurin/releases/?version=21)
- **Maven** ([maven.apache.org](https://maven.apache.org/download.cgi)) –
  alternativ reicht auch IntelliJ IDEA (Community, kostenlos), das Maven
  eingebaut hat.

## Bauen per Terminal
```
cd allitems
mvn package
```
Die fertige Datei liegt danach unter `target/allitems-1.0.jar`.
Einfach in den `plugins`-Ordner deines Servers kopieren und den Server
neu starten (oder `/reload`, aber ein echter Restart ist sauberer).

## Bauen mit IntelliJ IDEA
1. Ordner `allitems` als Projekt öffnen (IntelliJ erkennt die `pom.xml`
   automatisch und lädt die Abhängigkeit von PaperMC herunter).
2. Rechts im Maven-Tab: `allitems > Lifecycle > package` doppelklicken.
3. Die .jar liegt danach ebenfalls in `target/allitems-1.0.jar`.

## Warum wird trotzdem gegen eine feste API-Version kompiliert?
In `pom.xml` steht `1.21.11-R0.1-SNAPSHOT`, weil man beim Bauen immer gegen
irgendeine konkrete API-Version kompilieren muss – das ist nur zum
Kompilieren nötig, nicht zur Laufzeit. Der Code selbst nutzt bewusst nur
Bukkit-API, die es seit sehr vielen Versionen gibt, und fängt fehlende
Methoden zur Laufzeit ab (siehe Kommentare oben in der Java-Datei). Dadurch
läuft die eine gebaute .jar sowohl auf älteren als auch auf neueren
Paper-/Spigot-Servern.
