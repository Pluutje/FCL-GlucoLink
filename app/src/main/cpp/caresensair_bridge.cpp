// FCLGlucoLink — CareSens Air native calibration bridge.
//
// 01/08/2026 (editor) — dit bestand hoort bij taak "Design + build CareSens
// Air native calibration bridge". Achtergrond staat uitgebreid in README —
// samengevat: nRF Connect-onderzoek tegen een echte CSAir 0224-sensor
// toonde aan dat CareSens Air GEEN standaard Bluetooth Glucose Profile
// gebruikt (de aanname waar CareSensAirGattProtocol.kt eerder op gebaseerd
// was), maar een eigen protocol waarbij de sensor RUWE elektrochemische
// stroommetingen stuurt. Die pas omzetten naar een echte mg/dL-waarde is
// zelf al een niet-triviale, propriëtaire rekenstap (`air1_opcal4_algorithm`)
// — geen gebruikerskalibratie (fingerstick-bijstelling gebeurt toch al
// in AAPS), maar de FABRIEKS-omzetstap die elke Libre-achtige sensor
// nodig heeft, ongeacht of er ooit een fingerstick aan te pas komt.
//
// Herkomst van de aanroep hieronder: LETTERLIJK nagebouwd op basis van
// Juggluco's eigen `Common/src/main/cpp/air/java.cpp` (GPL-3, credit staat
// al in ui/AboutScreen.kt) — vooral de functies `airProcessData()`,
// `airSaveSensorInfo()`, `airSaveSensorInfo2()`, `airSaveStartSensor()` en
// `airGetLast()`/`getlibfuncs()` daar. Bewust GEEN Juggluco-interne
// app-architectuur overgenomen (geen SensorGlucoseData/mmap/backup-schijven,
// geen mkres()-dedupe-logica tegen Juggluco's EIGEN geschiedenis-database)
// — dat is Juggluco-eigen boekhouding die niets met de rekenstap zelf te
// maken heeft. In plaats daarvan: FCLGlucoLink's eigen, eenvoudiger
// status-export/import (`nativeExportState`/`nativeImportState` hieronder),
// zodat Kotlin de ruwe struct-bytes gewoon als één blob naar een bestand kan
// schrijven — functioneel hetzelfde doel als Juggluco's mmap-bestanden
// (kalibratiegeschiedenis overleeft een herstart van de app), alleen
// simpeler.
//
// 11/09/2026 (editor, RONDE 175 — na een caselog van een andere gebruiker:
// nieuwe CareSens Air-sensor, ~6 uur prima gemeten, daarna om ~03:00 elke
// reconnect "succesvol" (handshake OK) maar de teruggegeven meetdata bevroor
// bij-voor-bij hetzelfde sequentienummer, tot die gebruiker om 07:00
// handmatig overstapte op Juggluco — MET
// DEZELFDE sensor, die bij Juggluco gewoon weer verse waarden gaf) — de
// eerdere aanname hierboven dat Juggluco's `askEarlier`-hertry-heuristiek
// pure Juggluco-boekhouding was (los van de rekenstap) klopt dus NIET: het
// is precies het zelfherstel-mechanisme dat hier ontbrak. Zie Juggluco's
// java.cpp (`airProcessData`, tak `air->reg0==0xC4` met `newrecords==0`) en
// `airGetLast()` (trekt `askEarlier` af van het laatst verwerkte
// sequentienummer). Alsnog geport, zie `askEarlier`/`lastRealReadingEpochSec`
// hieronder en `nativeGetRequestSequence()`.
//
// `air.hpp` en `caresens_wire.hpp` zijn letterlijke kopieën (zie hun eigen
// kdoc) — dit bestand is de enige NIEUWE C++-code, en is bewust dun
// gehouden: de daadwerkelijke rekenstap gebeurt in `libCALCULATION.so`
// (closed-source, uit de gebruiker's eigen geïnstalleerde Juggluco-apk gehaald),
// hier alleen via dlopen/dlsym aangeroepen — exact zoals Juggluco's eigen
// `getlibfuncs()` dat doet, zodat dit onafhankelijk blijft van welke
// NDK/toolchain-versie die bibliotheek ooit gebouwd heeft.
//
// 13/09/2026 (editor, RONDE 177 — na een crash tijdens het koppelen van een
// CareSens Air-sensor, gemeld door een niet-technische tester die geen
// `adb bugreport` kon maken) — het bestaande diagnostisch logbestand
// (DiagnosticFileLogger.kt) en de globale Kotlin-crash-handler
// (FclGlucoLinkApp.installCrashLogging(), Ronde 126) vingen NIETS van deze
// crash op: beide werken alleen op de JVM/Kotlin-laag. Een crash in DEZE
// native laag (bv. een segfault) omzeilt dat volledig — het proces stopt
// met exact hetzelfde "deze app bevat een bug"-systeemscherm, maar zonder
// enig spoor in ons eigen logbestand. Zie `nativeInstallCrashHandler()`
// hieronder: een kleine, bewust MINIMALE POSIX-signal-handler
// (SIGSEGV/SIGABRT/SIGBUS/SIGILL/SIGFPE) die vlak vóórdat het proces
// alsnog stopt, het signaal + een ruwe backtrace naar hetzelfde
// dagelijkse logbestand schrijft — zodat zo'n crash voortaan gewoon met de
// bestaande "Send log files"-knop (Ronde 174) meegestuurd kan worden,
// zonder dat een tester iets technisch hoeft te doen. Zie die functie's
// eigen kdoc voor de async-signal-safety-beperkingen die dit ontwerp
// bepalen (geen malloc, geen JNIEnv, alleen kale POSIX-aanroepen).
// LET OP (13/09/2026): deze handler vangt alleen de vijf genoemde POSIX-
// signalen. Een ANR (hoofdthread te lang geblokkeerd, door Android's eigen
// watchdog afgesloten) of een SIGKILL van Android's low-memory killer zijn
// GEEN signalen die een app-eigen handler kan onderscheppen — die blijven
// dus onzichtbaar voor dit mechanisme. Als "de app sluit zichzelf steeds"
// aanhoudt zonder dat hier ooit iets over verschijnt, is dat een aanwijzing
// dat de oorzaak in die richting zit, niet in deze native laag.
//
// 13/09/2026 (editor, RONDE 178 — na de DERDE caselog van diezelfde
// gebruiker, sensor CSAir 1372: 3+ uur bevroren op sequentie 209 zonder dat de askEarlier-regel uit
// Ronde 175/176 ook maar één keer in het logbestand verscheen) — twee
// aanvullingen. Ten eerste: `nativeProcessGlucoseData`'s belangrijkste
// beslissingen (geaccepteerde meting, buiten-bereik genegeerd,
// algoritmefout, sensor-foutcode, askEarlier-ophoging) gingen tot nu toe
// ALLEEN naar Android's eigen logcat (LOGI/LOGE hieronder) — niet naar het
// bestand dat "Send log files" meestuurt. Daardoor was er geen enkele
// manier om vanuit een tester's eigen log te zien OF er ooit een meting
// geaccepteerd werd, laat staan waarom niet. Zie `diagLog()` hieronder:
// dezelfde regel gaat nu naar ZOWEL logcat als het dagelijkse logbestand
// (via [g_nativeLogPath], dezelfde weg als de crash-handler, maar met
// gewone — niet async-signal-safe beperkte — bestandsfuncties, want dit
// draait op de normale BLE-callback-thread). Ten tweede: de
// askEarlier-drempel in de 0xC4-tak hieronder keek alleen naar
// `lastRealReadingEpochSec` (moment van de LAATST geaccepteerde meting) —
// als een sensor-sessie NOOIT een geaccepteerde meting heeft gehad (bv.
// omdat de allereerste ontvangen data al buiten bereik valt of een fout
// geeft), bleef dat veld op 0 staan en kon de hele heuristiek nooit
// afgaan, hoe lang de aanvraagpositie ook al vastzat. `firstGlucoseFrameEpochSec`
// hieronder (CareSensAirState) vangt dat op: gezet bij het EERSTE
// binnengekomen data-frame in deze sessie, ongeacht of dat frame een
// bruikbare meting opleverde, en gebruikt als terugval-ankerpunt zodra er
// nog geen geaccepteerde meting is geweest.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <cmath>
#include <ctime>
#include <cstdint>
#include <cstdio>
#include <cstdarg>
#include <limits>
#include <csignal>
#include <unistd.h>
#include <fcntl.h>
#include <unwind.h>

#include "air.hpp"
#include "caresens_wire.hpp"

#define LOG_TAG "CareSensAirBridge"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {

// 01/08/2026 (editor) — zelfde functiehandtekening als Juggluco's
// air1_opcal4_algorithm_t (java.cpp regel 39) — moet exact overeenkomen met
// wat libCALCULATION.so daadwerkelijk exporteert (geverifieerd met `nm -D`
// tegen de uit de apk geëxtraheerde .so: symbool aanwezig, ongemangled
// C-linkage, exact deze naam).
using air1_opcal4_algorithm_t = unsigned char (*)(
    air1_opcal4_device_info_t *,
    air1_opcal4_cgm_input_t *,
    air1_opcal4_cal_list_t *,
    air1_opcal4_arguments_t *,
    air1_opcal4_output_t *,
    air1_opcal4_debug_t *);

air1_opcal4_algorithm_t g_air1_opcal4_algorithm = nullptr;
void *g_calculationLibHandle = nullptr;

// ============================================================================
// RONDE 177 — minimale native crash-handler (zie kdoc bovenaan dit bestand)
// ============================================================================
//
// Alles hieronder draait mogelijk BINNEN een signal-handler-context, dus
// ALLEEN async-signal-safe operaties: geen malloc/new (dus geen
// std::string/std::vector/JNIEnv-aanroepen), alleen kale POSIX-syscalls
// (open/write/close/raise) en handmatige, buffer-op-de-stack tekst-opbouw.
// `_Unwind_Backtrace` staat strikt genomen niet op POSIX's async-signal-safe
// lijst (het raakt alleen CPU-registers en read-only unwind-tabellen aan,
// geen heap), maar is de gangbare aanpak in vrijwel elke Android-
// crashreporter (Breakpad/Crashlytics-achtige tools) voor precies dit
// doel — hier bewust "best effort voor diagnose", niet een garantie.

// 13/09/2026 (editor, RONDE 178) — hernoemd van g_crashLogPath: dit pad
// wordt nu voor TWEE dingen gebruikt — de crash-handler (async-signal-safe
// pad hieronder) EN de gewone diagLog()-hulpfunctie verderop (normale
// bestandsfuncties, buiten signaalcontext). Zelfde bestand, zelfde pad,
// twee schrijfwegen naar toe — vandaar de neutralere naam.
constexpr size_t kNativeLogPathCap = 512;
char g_nativeLogPath[kNativeLogPathCap] = {0};
bool g_crashHandlerInstalled = false;

// Alternatieve signal-stack: als de crash zelf een stack-overflow is (een
// diepe recursie/oneindige lus), is de gewone stack op dat moment vol — de
// handler zou dan zelf niet meer kunnen draaien zonder deze aparte, altijd-
// gereserveerde ruimte (sigaltstack() hieronder, SA_ONSTACK-vlag).
constexpr size_t kAltStackSize = 32 * 1024;
alignas(16) char g_altStack[kAltStackSize] = {0};

// Handmatige, allocatievrije tekst-opbouw-helpers — vervangen hier bewust
// snprintf() (locale-/lock-gebruik is niet gegarandeerd async-signal-safe
// op elke libc).
void crashAppendStr(char *buf, size_t &pos, size_t bufSize, const char *s) {
    while (s && *s && pos + 1 < bufSize) buf[pos++] = *s++;
}

void crashAppendInt(char *buf, size_t &pos, size_t bufSize, long value) {
    char tmp[24];
    int n = 0;
    bool neg = value < 0;
    unsigned long v = neg ? static_cast<unsigned long>(-value) : static_cast<unsigned long>(value);
    if (v == 0) tmp[n++] = '0';
    while (v > 0 && n < static_cast<int>(sizeof(tmp))) {
        tmp[n++] = static_cast<char>('0' + (v % 10));
        v /= 10;
    }
    if (neg && pos + 1 < bufSize) buf[pos++] = '-';
    while (n > 0 && pos + 1 < bufSize) buf[pos++] = tmp[--n];
}

void crashAppendHex(char *buf, size_t &pos, size_t bufSize, uint64_t value) {
    static const char kHexDigits[] = "0123456789abcdef";
    if (pos + 2 < bufSize) {
        buf[pos++] = '0';
        buf[pos++] = 'x';
    }
    bool started = false;
    for (int shift = 60; shift >= 0; shift -= 4) {
        auto nibble = static_cast<unsigned int>((value >> shift) & 0xFu);
        if (nibble != 0) started = true;
        if (started && pos + 1 < bufSize) buf[pos++] = kHexDigits[nibble];
    }
    if (!started && pos + 1 < bufSize) buf[pos++] = '0';
}

struct CrashBacktraceState {
    void **current;
    void **end;
};

_Unwind_Reason_Code crashUnwindCallback(struct _Unwind_Context *context, void *arg) {
    auto *state = reinterpret_cast<CrashBacktraceState *>(arg);
    if (state->current >= state->end) return _URC_END_OF_STACK;
    auto pc = static_cast<uintptr_t>(_Unwind_GetIP(context));
    if (pc) *state->current++ = reinterpret_cast<void *>(pc);
    return _URC_NO_REASON;
}

// De eigenlijke handler: wordt door de OS aangeroepen bij SIGSEGV e.d. —
// schrijft signaalnummer + crash-adres + een ruwe pc-backtrace naar
// [g_nativeLogPath] (hetzelfde dagelijkse logbestand als
// DiagnosticFileLogger.kt, zie CareSensAirNative.kt's
// `installCrashHandler()`), en geeft de crash dan door aan het
// standaardgedrag — NOOIT onderdrukken, zelfde filosofie als
// FclGlucoLinkApp.installCrashLogging()'s Kotlin-kant: Android's eigen
// crash-dialoog/bugreport-mechanisme moet gewoon blijven werken.
void crashSignalHandler(int signum, siginfo_t *info, void * /*ucontext*/) {
    if (g_nativeLogPath[0] != '\0') {
        int fd = open(g_nativeLogPath, O_WRONLY | O_CREAT | O_APPEND, 0644);
        if (fd >= 0) {
            char line[768];
            size_t pos = 0;
            crashAppendStr(line, pos, sizeof(line), "\n=== NATIVE CRASH (caresensair_bridge) signal=");
            crashAppendInt(line, pos, sizeof(line), signum);
            crashAppendStr(line, pos, sizeof(line), " addr=");
            crashAppendHex(line, pos, sizeof(line),
                            reinterpret_cast<uint64_t>(info != nullptr ? info->si_addr : nullptr));
            crashAppendStr(line, pos, sizeof(line), " ===\n");
            write(fd, line, pos);

            void *frames[32];
            CrashBacktraceState state{frames, frames + 32};
            _Unwind_Backtrace(crashUnwindCallback, &state);
            const int frameCount = static_cast<int>(state.current - frames);
            for (int i = 0; i < frameCount; ++i) {
                char frameLine[64];
                size_t fpos = 0;
                crashAppendStr(frameLine, fpos, sizeof(frameLine), "  #");
                crashAppendInt(frameLine, fpos, sizeof(frameLine), i);
                crashAppendStr(frameLine, fpos, sizeof(frameLine), " pc=");
                crashAppendHex(frameLine, fpos, sizeof(frameLine),
                                reinterpret_cast<uint64_t>(frames[i]));
                crashAppendStr(frameLine, fpos, sizeof(frameLine), "\n");
                write(fd, frameLine, fpos);
            }
            close(fd);
        }
    }
    // Standaardgedrag herstellen en het signaal opnieuw afgeven — laat het
    // proces alsnog crashen zoals normaal (zie kdoc hierboven).
    signal(signum, SIG_DFL);
    raise(signum);
}

// 13/09/2026 (editor, RONDE 178) — GEEN signaalcontext: dit draait gewoon op
// de normale BLE-callback-thread, dus in tegenstelling tot crashSignalHandler
// hierboven zijn gewone C-bestandsfuncties hier wel veilig. Schrijft dezelfde
// regel naar zowel logcat (via __android_log_write) als naar het dagelijkse
// logbestand ([g_nativeLogPath], hetzelfde pad als de crash-handler) —
// zodat een tester's eigen "Send log files"-upload voortaan ook laat zien
// WAT nativeProcessGlucoseData besliste (geaccepteerd/genegeerd/fout),
// niet alleen de ruwe BLE-bytes die Kotlin al logt.
void diagLog(android_LogPriority priority, const char *fmt, ...) {
    char message[256];
    va_list args;
    va_start(args, fmt);
    vsnprintf(message, sizeof(message), fmt, args);
    va_end(args);
    __android_log_write(priority, LOG_TAG, message);
    if (g_nativeLogPath[0] != '\0') {
        FILE *f = fopen(g_nativeLogPath, "a");
        if (f) {
            fprintf(f, "%s\n", message);
            fclose(f);
        }
    }
}

#define DIAGI(...) diagLog(ANDROID_LOG_INFO, __VA_ARGS__)
#define DIAGE(...) diagLog(ANDROID_LOG_ERROR, __VA_ARGS__)

// 13/09/2026 (editor, RONDE 179 — na diezelfde DERDE caselog: Ronde 178's
// nieuwe logregels bewezen dat de al weken bekende "bevriezing" in
// werkelijkheid een DETERMINISTISCHE crash was: telkens exact hetzelfde
// record — zelfde ruwe bytes, zelfde sequentienummer, tot in de laatste
// byte — liet `air1_opcal4_algorithm()` crashen (SIGSEGV, steeds hetzelfde
// adres), drie keer op rij, tot de app het opgaf) — [g_pendingFramePath] is
// een KLEIN, apart bestand (dus geen belasting van het leesbare dagelijkse
// logbestand) dat de "vingerafdruk" bevat (zie [PendingFrameFingerprint]
// hieronder) van het 0xC5-record waarvoor de rekenbibliotheek NET wordt
// aangeroepen. Geschreven vlak VOOR die aanroep, gewist meteen NA een
// succesvolle terugkeer — blijft dus alleen op schijf staan als het proces
// PRECIES tijdens die aanroep crasht. Bij de volgende poging (na een crash
// krijgt de sensor toch weer hetzelfde record aangeboden, zie
// nativeProcessGlucoseData's kdoc) herkennen we dat zo: "dit exacte record
// hebben we net geprobeerd en toen crashten we" — en slaan de aanroep dan
// over in plaats van blind opnieuw te crashen.
//
// 24/09/2026 (editor, RONDE 180 — na de VIERDE caselog van diezelfde gebruiker, sensor CSAir
// 4779: ondanks Ronde 179 crashte de app hier toch 34x op rij, ononderbroken
// bijna 2 uur lang, exact hetzelfde patroon als vóór die fix) — de skip
// sloeg nooit aan. Oorzaak: de vergelijking hieronder ging tot nu toe over
// de VOLLEDIGE ruwe `AirData`-struct (`memcmp(..., sizeof(AirData))`) — en
// die struct bevat, VÓÓR de velden die er echt toe doen, twee 32-bits
// tellers (`deviceErrorCode`/`r_count` even daarvoor, dan `a_count` en
// `misc`) die zich als een lopende klok/heartbeat gedragen en bij ELKE
// aanbieding van hetzelfde vastgelopen record toch een ANDERE waarde
// hebben (bevestigd door twee crash-momenten 1u53 uit elkaar rechtstreeks
// uit dat logbestand te vergelijken: sequenceNumber/time/battery/
// temperature/glucose_array allemaal byte-voor-byte identiek, `a_count` en
// `misc` allebei duidelijk opgelopen). Een volledige-struct-memcmp kan
// zo'n vastgelopen record dus NOOIT als "dezelfde" herkennen, ongeacht hoe
// lang het al vaststaat. [PendingFrameFingerprint] vervangt de ruwe bytes
// door alleen de velden die daadwerkelijk in `input.data` terechtkomen
// (zie de aanroep van [g_air1_opcal4_algorithm] hieronder): sequenceNumber,
// time, temperature, glucose_array — precies de velden die bij een echt
// vastgelopen record ONVERANDERD blijven, en die bij een nieuwe/andere
// meting vrijwel zeker verschillen (zelfde motivatie als de oude kdoc
// hieronder over hoe onwaarschijnlijk een toevallige match is, nu alleen
// toegepast op een kleinere, gerichtere set velden).
struct PendingFrameFingerprint {
    uint32_t sequenceNumber;
    uint32_t time;
    uint16_t temperature;
    std::array<uint16_t, 30> glucose_array;
} __attribute__((packed));

// Bewust GEEN per-sensor-scoping (zelfde bestand voor elke sensor): een
// toevallige match tussen twee verschillende sensoren (of twee legitiem
// verschillende metingen) is praktisch onmogelijk gezien hoe specifiek een
// vingerafdruk is (tijd, temperatuur, 30 glucosewaarden en sequentienummer
// moeten ALLEMAAL exact overeenkomen), en zelfs in dat theoretische geval is
// het gevolg alleen dat één meting eenmalig overgeslagen wordt — geen risico
// op blijvend verkeerd gedrag.
constexpr size_t kPendingFramePathCap = 512;
char g_pendingFramePath[kPendingFramePathCap] = {0};

bool readPendingFrame(uint8_t *outBuf, size_t bufSize) {
    if (g_pendingFramePath[0] == '\0') return false;
    FILE *f = fopen(g_pendingFramePath, "rb");
    if (!f) return false;
    const size_t readBytes = fread(outBuf, 1, bufSize, f);
    fclose(f);
    return readBytes == bufSize;
}

void writePendingFrame(const uint8_t *buf, size_t len) {
    if (g_pendingFramePath[0] == '\0') return;
    FILE *f = fopen(g_pendingFramePath, "wb");
    if (!f) return;
    fwrite(buf, 1, len, f);
    fclose(f);
}

void clearPendingFrame() {
    if (g_pendingFramePath[0] == '\0') return;
    remove(g_pendingFramePath);
}

// 01/08/2026 (editor) — alle status die tussen BLE-notificaties (en, via
// nativeExportState/nativeImportState, tussen app-herstarts) moet blijven
// bestaan. Mirror van Juggluco's `airstream` (streamdata.hpp) +
// `SensorInfo`/`AirData`-verwerking in java.cpp, zonder de Juggluco-eigen
// SensorGlucoseData/mmap-laag eromheen.
struct CareSensAirState {
    air1_opcal4_device_info_t sensorInfo{};   // fabriekskalibratieprofiel — per sensor, via BLE ontvangen (0xC2-berichten)
    air1_opcal4_arguments_t generated{};      // algoritme-interne status — bouwt op over metingen heen, MOET persistent zijn
    air1_opcal4_output_t output{};
    air1_opcal4_debug_t debug{};
    int ininfo = 0;      // voortgang binnen de 0xC2-devicedata-overdracht (mirror van airstream::ininfo)
    int lastAir = -1;    // laatst verwerkte sequentienummer (mirror van SensorGlucoseData::getLastAir/setLastAir)

    // 11/09/2026 (editor, RONDE 175) — mirror van Juggluco's
    // `sensorGegs::askEarlier` (via `sens->getinfo()->askEarlier` in
    // java.cpp): hoeveel sequentienummers TERUG gevraagd moet worden zodra
    // de sensor te lang "0 nieuwe records" blijft aankondigen zonder dat er
    // een bruikbare meting binnenkomt. Zie nativeProcessGlucoseData's
    // 0xC4-tak (ophogen) en het succes-pad (afbouwen) hieronder, en
    // nativeGetRequestSequence() (toepassen bij het volgende 196,1-verzoek).
    int askEarlier = 0;
    // Tijdstip (telefoonklok, epoch-seconden) van de laatst succesvol
    // verwerkte, plausibele meting — mirror van Juggluco's
    // `sens->lastpoll()->gettime()`, nodig om te bepalen hoe lang het al
    // stil is sinds de laatste ECHTE meting (i.p.v. sinds de laatste
    // geslaagde BLE-verbinding, die bij een vastgelopen sequentienummer
    // immers steeds "succesvol" blijft ondanks bevroren data).
    uint32_t lastRealReadingEpochSec = 0;

    // 13/09/2026 (editor, RONDE 178 — zie het bestand's kdoc bovenaan voor
    // de volledige aanleiding) — tijdstip van het EERSTE binnengekomen
    // data-frame (0xC4 of 0xC5) in deze sensor-sessie, ONGEACHT of dat
    // frame een bruikbare/plausibele meting opleverde. `lastRealReadingEpochSec`
    // hierboven blijft daarentegen op 0 staan zolang er nog geen enkele
    // meting is GEACCEPTEERD — dat is precies het geval dat de
    // askEarlier-heuristiek (0xC4-tak in nativeProcessGlucoseData) tot nu
    // toe niet kon herstellen: zonder ooit één succesvolle meting had die
    // heuristiek geen ankerpunt om "hoe lang staat dit al vast" aan af te
    // meten. Dit veld dient als terugval-ankerpunt voor precies dat geval.
    uint32_t firstGlucoseFrameEpochSec = 0;

    // 05/08/2026 (editor, RONDE 40 — op verzoek, na de gebruiker's eigen
    // observatie van een langzaam oplopende vertraging in xDrip+/AAPS na
    // ronde 39's reconnect-fix) — beste schatting van het verschil tussen
    // onze telefoonklok en de sensor's EIGEN klok (die in `air->time`
    // meegestuurd wordt en zonder correctie 1-op-1 als meettijdstip
    // doorgegeven werd). Zie nativeProcessGlucoseData's kdoc bij de
    // toepassing hieronder voor het volledige verhaal. BEWUST NIET in
    // kExportSize/nativeExportState/nativeImportState opgenomen: dit is
    // een sessie-schatting die zichzelf elke verbinding opnieuw ververst
    // zodra er een vers record langskomt, dus hoeft niet over een
    // app-herstart heen bewaard te blijven — vers beginnen bij 0 kost
    // hooguit één cyclus voordat de eerste correctie binnenkomt.
    int64_t clockOffsetSecs = 0;
};

// Export/import-blob-layout: vaste volgorde, vaste groottes (alle velden
// zijn POD/`packed`/geen pointers) — simpel binair contract tussen native
// en Kotlin, geen versienummer nodig zolang air.hpp niet wijzigt (zie
// nativeExportState's kdoc voor wat er gebeurt als dat ooit wel gebeurt).
// 11/09/2026 (editor, RONDE 175) — +sizeof(int) (askEarlier) +
// sizeof(uint32_t) (lastRealReadingEpochSec), aangehangen ACHTER de
// bestaande velden. Een blob van vóór deze wijziging heeft de oude
// (kleinere) grootte en faalt dus de size-check in nativeImportState —
// precies het bestaande, bewust-geaccepteerde "vers beginnen"-pad hieronder
// (zelfde soort wijziging als air.hpp zelf ooit veranderen, zie die kdoc).
//
// 13/09/2026 (editor, RONDE 178) — nogmaals +sizeof(uint32_t)
// (firstGlucoseFrameEpochSec), zelfde patroon: weer ACHTER de bestaande
// velden aangehangen. Een blob van vóór deze wijziging (dus ook van vóór
// Ronde 175/176) faalt weer gewoon de size-check hieronder en start vers —
// voor een sensor die al maandenlang bevroren zit is dat geen verlies.
constexpr size_t kExportSize =
    sizeof(air1_opcal4_device_info_t) + sizeof(air1_opcal4_arguments_t) + sizeof(int) * 2
    + sizeof(int) + sizeof(uint32_t) + sizeof(uint32_t);

} // namespace

extern "C" {

JNIEXPORT jboolean JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeLoadCalculationLibrary(
    JNIEnv *env, jclass, jstring soPath) {
    if (g_air1_opcal4_algorithm != nullptr) {
        return JNI_TRUE; // al geladen (bv. na een reconnect binnen dezelfde procesinstantie)
    }
    const char *path = env->GetStringUTFChars(soPath, nullptr);
    if (!path) {
        LOGE("nativeLoadCalculationLibrary: soPath null");
        return JNI_FALSE;
    }
    // 01/08/2026 (editor) — RTLD_NOW, zelfde als Juggluco's eigen
    // getlibfuncs() (java.cpp regel 43: dlopen(fullpath, RTLD_NOW) —
    // exacte match qua vlag, voor het geval dat ooit uitmaakt).
    void *handle = dlopen(path, RTLD_NOW);
    if (!handle) {
        LOGE("dlopen(%s) failed: %s", path, dlerror());
        env->ReleaseStringUTFChars(soPath, path);
        return JNI_FALSE;
    }
    dlerror(); // bestaande fout wissen vóór dlsym, zelfde voorzichtigheid als Juggluco
    auto fn = reinterpret_cast<air1_opcal4_algorithm_t>(dlsym(handle, "air1_opcal4_algorithm"));
    const char *symErr = dlerror();
    if (!fn || symErr) {
        LOGE("dlsym(air1_opcal4_algorithm) failed: %s", symErr ? symErr : "null");
        dlclose(handle);
        env->ReleaseStringUTFChars(soPath, path);
        return JNI_FALSE;
    }
    g_calculationLibHandle = handle;
    g_air1_opcal4_algorithm = fn;
    LOGI("nativeLoadCalculationLibrary: OK (%s)", path);
    env->ReleaseStringUTFChars(soPath, path);
    return JNI_TRUE;
}

// 13/09/2026 (editor, RONDE 177) — installeert [crashSignalHandler]
// hierboven. Moet één keer per proces aangeroepen worden, VOORDAT er iets
// kan misgaan — zie CareSensAirNative.kt's `installCrashHandler()`, die dit
// meteen bij het laden van de kalibratiebibliotheek aanroept.
// [logFilePath] komt kant-en-klaar van de Kotlin-kant
// (DiagnosticFileLogger.todaysLogFilePath()) — hier alleen als kale
// bytes gekopieerd naar een vaste C-buffer, want de handler zelf mag later
// geen JNIEnv/Context meer aanraken (zie kdoc bij crashSignalHandler).
// 13/09/2026 (editor, RONDE 179) — [pendingFramePath] erbij: zelfde soort
// eenmalige setup, nu voor [g_pendingFramePath] (zie kdoc daarboven) i.p.v.
// alleen de crash-handler. Bewust in DEZELFDE functie/aanroepmoment
// ondergebracht i.p.v. een aparte JNI-functie — scheelt een extra
// round-trip vanuit Kotlin voor iets dat toch al op precies hetzelfde
// moment (bij het laden van de kalibratiebibliotheek) moet gebeuren.
JNIEXPORT void JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeInstallCrashHandler(
    JNIEnv *env, jclass, jstring logFilePath, jstring pendingFramePath) {
    if (pendingFramePath) {
        const char *path = env->GetStringUTFChars(pendingFramePath, nullptr);
        if (path) {
            const size_t len = strnlen(path, kPendingFramePathCap - 1);
            memcpy(g_pendingFramePath, path, len);
            g_pendingFramePath[len] = '\0';
            env->ReleaseStringUTFChars(pendingFramePath, path);
        }
    }
    if (g_crashHandlerInstalled) return;
    if (logFilePath) {
        const char *path = env->GetStringUTFChars(logFilePath, nullptr);
        if (path) {
            const size_t len = strnlen(path, kNativeLogPathCap - 1);
            memcpy(g_nativeLogPath, path, len);
            g_nativeLogPath[len] = '\0';
            env->ReleaseStringUTFChars(logFilePath, path);
        }
    }
    stack_t altStack{};
    altStack.ss_sp = g_altStack;
    altStack.ss_size = kAltStackSize;
    altStack.ss_flags = 0;
    sigaltstack(&altStack, nullptr);

    struct sigaction action {};
    action.sa_sigaction = crashSignalHandler;
    action.sa_flags = SA_SIGINFO | SA_ONSTACK;
    sigemptyset(&action.sa_mask);
    sigaction(SIGSEGV, &action, nullptr);
    sigaction(SIGABRT, &action, nullptr);
    sigaction(SIGBUS, &action, nullptr);
    sigaction(SIGILL, &action, nullptr);
    sigaction(SIGFPE, &action, nullptr);
    g_crashHandlerInstalled = true;
    LOGI("nativeInstallCrashHandler: geinstalleerd, logpad=%s", g_nativeLogPath);
}

JNIEXPORT jlong JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeCreateState(
    JNIEnv *, jclass) {
    // 01/08/2026 (editor) — `CareSensAirState{}` initialiseert sensorInfo
    // met air1_opcal4_device_info_t's eigen defaults (géén — die struct zelf
    // heeft geen in-class-defaults; de fallbackwaarden zoals ycept=1.0,
    // vref=1.49594 staan op DeviceInfo2Obj, niet op air1_opcal4_device_info_t
    // — zie kdoc bij nativeSaveSensorInfoChunk1 hieronder voor waarom dat
    // hier geen probleem is) en `generated` op nul — exact hetzelfde
    // startpunt als Juggluco's mmap-constructor voor een NIEUWE sensor
    // (streamdata.hpp: `sensorInfo(...,[](DeviceInfo3Obj *gegs){ *gegs={}; })`,
    // `generated(...)` zonder init-lambda = zero-initialisatie).
    auto *state = new CareSensAirState();
    return reinterpret_cast<jlong>(state);
}

JNIEXPORT void JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeDestroyState(
    JNIEnv *, jclass, jlong handle) {
    delete reinterpret_cast<CareSensAirState *>(handle);
}

JNIEXPORT jbyteArray JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeExportState(
    JNIEnv *env, jclass, jlong handle) {
    auto *state = reinterpret_cast<CareSensAirState *>(handle);
    jbyteArray out = env->NewByteArray(static_cast<jsize>(kExportSize));
    if (!out) return nullptr;
    // Vaste volgorde: sensorInfo, generated, ininfo, lastAir, askEarlier,
    // lastRealReadingEpochSec, firstGlucoseFrameEpochSec — zelfde volgorde
    // als de struct-velden, zie kExportSize.
    size_t offset = 0;
    env->SetByteArrayRegion(out, 0, sizeof(state->sensorInfo),
                             reinterpret_cast<const jbyte *>(&state->sensorInfo));
    offset += sizeof(state->sensorInfo);
    env->SetByteArrayRegion(out, static_cast<jsize>(offset), sizeof(state->generated),
                             reinterpret_cast<const jbyte *>(&state->generated));
    offset += sizeof(state->generated);
    env->SetByteArrayRegion(out, static_cast<jsize>(offset), sizeof(int),
                             reinterpret_cast<const jbyte *>(&state->ininfo));
    offset += sizeof(int);
    env->SetByteArrayRegion(out, static_cast<jsize>(offset), sizeof(int),
                             reinterpret_cast<const jbyte *>(&state->lastAir));
    offset += sizeof(int);
    env->SetByteArrayRegion(out, static_cast<jsize>(offset), sizeof(int),
                             reinterpret_cast<const jbyte *>(&state->askEarlier));
    offset += sizeof(int);
    env->SetByteArrayRegion(out, static_cast<jsize>(offset), sizeof(uint32_t),
                             reinterpret_cast<const jbyte *>(&state->lastRealReadingEpochSec));
    offset += sizeof(uint32_t);
    env->SetByteArrayRegion(out, static_cast<jsize>(offset), sizeof(uint32_t),
                             reinterpret_cast<const jbyte *>(&state->firstGlucoseFrameEpochSec));
    return out;
}

JNIEXPORT jboolean JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeImportState(
    JNIEnv *env, jclass, jlong handle, jbyteArray blob) {
    auto *state = reinterpret_cast<CareSensAirState *>(handle);
    if (!blob) return JNI_FALSE;
    const jsize len = env->GetArrayLength(blob);
    if (static_cast<size_t>(len) != kExportSize) {
        // 01/08/2026 (editor) — grootte klopt niet: waarschijnlijk een
        // eerder-opgeslagen blob van vóór een air.hpp-wijziging (zou alleen
        // gebeuren als Juggluco's eigen header ooit verandert — onwaar-
        // schijnlijk, maar dan is een verse start voor deze ene sensor
        // veiliger dan blindelings verkeerd uitgelijnde bytes terugzetten).
        LOGE("nativeImportState: size mismatch (%d != %zu), starting fresh", len, kExportSize);
        return JNI_FALSE;
    }
    jbyte *bytes = env->GetByteArrayElements(blob, nullptr);
    if (!bytes) return JNI_FALSE;
    size_t offset = 0;
    memcpy(&state->sensorInfo, bytes + offset, sizeof(state->sensorInfo));
    offset += sizeof(state->sensorInfo);
    memcpy(&state->generated, bytes + offset, sizeof(state->generated));
    offset += sizeof(state->generated);
    memcpy(&state->ininfo, bytes + offset, sizeof(int));
    offset += sizeof(int);
    memcpy(&state->lastAir, bytes + offset, sizeof(int));
    offset += sizeof(int);
    memcpy(&state->askEarlier, bytes + offset, sizeof(int));
    offset += sizeof(int);
    memcpy(&state->lastRealReadingEpochSec, bytes + offset, sizeof(uint32_t));
    offset += sizeof(uint32_t);
    memcpy(&state->firstGlucoseFrameEpochSec, bytes + offset, sizeof(uint32_t));
    env->ReleaseByteArrayElements(blob, bytes, JNI_ABORT);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeGetLastSequence(
    JNIEnv *, jclass, jlong handle) {
    return reinterpret_cast<CareSensAirState *>(handle)->lastAir;
}

// 11/09/2026 (editor, RONDE 175) — mirror van Juggluco's `airGetLast()`
// (java.cpp): het sequentienummer om daadwerkelijk in het volgende
// 196,1-verzoek te gebruiken (buildRequestDataCommand) — dat is NIET
// altijd hetzelfde als het laatst verwerkte sequentienummer (`lastAir`,
// zie nativeGetLastSequence hierboven): als er te lang "0 nieuwe
// records"-aankondigingen komen zonder bruikbare meting (zie de
// askEarlier-ophoging in nativeProcessGlucoseData's 0xC4-tak), wordt hier
// bewust TERUGGEGAAN in de tijd, zodat een vastgelopen aanvraagpositie
// (de sensor blijft anders exact hetzelfde, al eerder geziene record
// teruggeven) een kans krijgt om zich te herstellen. Juggluco's eigen
// `airGetLast()` trekt `askEarlier` alleen af als `res>0` — exact
// overgenomen (bij `lastAir<=0`, d.w.z. nog nooit een record verwerkt,
// heeft aftrekken geen zin). Kan een negatieve waarde opleveren als
// askEarlier > lastAir — buildRequestDataCommand() in
// CareSensAirGattProtocol.kt klemt dat al af naar 0 ("stuur alles, dit is
// de eerste keer"), dus geen extra klem-logica hier nodig.
JNIEXPORT jint JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeGetRequestSequence(
    JNIEnv *, jclass, jlong handle) {
    auto *state = reinterpret_cast<CareSensAirState *>(handle);
    int res = state->lastAir;
    if (res > 0) res -= state->askEarlier;
    return res;
}

// 01/08/2026 (editor) — mirror van Juggluco's airSaveSensorInfo()
// (java.cpp regel 328-362). Verwerkt het EERSTE deel van de
// devicedata-overdracht (0xC2/0x01-notificatie op charact21, zie
// AirGattCallback.java's onChar21Changed): de sensor stuurt hier zijn
// EIGEN fabriekskalibratieprofiel (ycept, slope, r2, t90, lot, sensor_id,
// vervaldatum, ...) — dit overschrijft dus de lege/nul-waardes uit
// nativeCreateState() met de echte, per-sensor-unieke waardes. Zonder dit
// bericht zou air1_opcal4_algorithm() met zinloze nul-kalibratie draaien.
JNIEXPORT jboolean JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeSaveSensorInfoChunk1(
    JNIEnv *env, jclass, jlong handle, jbyteArray value) {
    auto *state = reinterpret_cast<CareSensAirState *>(handle);
    if (!value) return JNI_FALSE;
    const jsize len = env->GetArrayLength(value);
    if (static_cast<size_t>(len) < sizeof(SensorInfo)) {
        LOGE("nativeSaveSensorInfoChunk1: size %d < %zu", len, sizeof(SensorInfo));
        return JNI_FALSE;
    }
    jbyte *bytes = env->GetByteArrayElements(value, nullptr);
    if (!bytes) return JNI_FALSE;
    const auto *air = reinterpret_cast<const SensorInfo *>(bytes);
    bool ok = true;
    if (static_cast<uint8_t>(air->reg[0]) != 0xC2) {
        LOGE("nativeSaveSensorInfoChunk1: reg[0]=%d, verwacht 0xC2", air->reg[0]);
        ok = false;
    } else if (air->reg[1] != 1) {
        LOGE("nativeSaveSensorInfoChunk1: reg[1]=%d, verwacht 1", air->reg[1]);
        ok = false;
    } else if (air->mCLibraryVersion < 2) {
        LOGE("nativeSaveSensorInfoChunk1: cLibraryVersion < 2, niet ondersteund");
        state->ininfo = 0;
        ok = true; // zelfde gedrag als Juggluco: geen harde fout, gewoon niets doen
    } else {
        // memcpy vanaf byte 2 (na het reg[0..1]-berichttype-voorvoegsel)
        // rechtstreeks op sensorInfo — SensorInfo en het begin van
        // air1_opcal4_device_info_t hebben dezelfde veldvolgorde (beide
        // letterlijk uit Juggluco's eigen bron), dus dit is een exacte
        // 1-op-1 mirror van airSaveSensorInfo's
        // `memcpy(sensorinfo,bluedata.data()+2,sizeof(SensorInfo)-2)`.
        memcpy(&state->sensorInfo, bytes + 2, sizeof(SensorInfo) - 2);
        state->sensorInfo.stabilizationInterval = 1800;
        state->ininfo = 72;
        LOGI("nativeSaveSensorInfoChunk1: OK lot=%.10s", air->lot);
    }
    env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 01/08/2026 (editor) — mirror van airSaveSensorInfo2() (java.cpp regel
// 366-405): vult de REST van air1_opcal4_device_info_t (kalman-/slope-/
// err-parameters, vref/eapp, ...) verder aan, in het exacte
// chunk-lengte-patroon dat Juggluco ook gebruikt (afhankelijk van hoever
// chunk 1 al gevorderd was — `ininfo`).
JNIEXPORT jboolean JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeSaveSensorInfoChunk2(
    JNIEnv *env, jclass, jlong handle, jbyteArray value) {
    auto *state = reinterpret_cast<CareSensAirState *>(handle);
    if (!value) return JNI_FALSE;
    const jsize len = env->GetArrayLength(value);
    if (len < 2) return JNI_FALSE;
    jbyte *bytes = env->GetByteArrayElements(value, nullptr);
    if (!bytes) return JNI_FALSE;
    const auto *data = reinterpret_cast<const uint8_t *>(bytes);
    bool ok = true;
    if (data[0] != 0xC2) {
        LOGE("nativeSaveSensorInfoChunk2: byte0=%d, verwacht 0xC2", data[0]);
        ok = false;
    } else if (data[1] != 2) {
        LOGE("nativeSaveSensorInfoChunk2: byte1=%d, verwacht 2", data[1]);
        ok = false;
    } else {
        const int ininfo = state->ininfo;
        int cplen;
        // Exacte overname van airSaveSensorInfo2's chunk-lengte-tabel —
        // hangt af van cLibraryVersion (al gezet door chunk1) en hoever
        // chunk1 gevorderd was.
        if (state->sensorInfo.cLibraryVersion >= 2) {
            cplen = (ininfo == 72) ? 157 : 205;
        } else {
            cplen = (!ininfo) ? 202 : 125;
        }
        const size_t avail = static_cast<size_t>(len) - 2;
        if (avail < static_cast<size_t>(cplen)) {
            LOGE("nativeSaveSensorInfoChunk2: payload %zu < verwachte cplen %d", avail, cplen);
            ok = false;
        } else {
            memcpy(reinterpret_cast<uint8_t *>(&state->sensorInfo) + ininfo, bytes + 2,
                   static_cast<size_t>(cplen));
            state->ininfo += cplen;
            LOGI("nativeSaveSensorInfoChunk2: OK ininfo=%d->%d cplen=%d", ininfo, state->ininfo, cplen);
        }
    }
    env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
    return ok ? JNI_TRUE : JNI_FALSE;
}

// 01/08/2026 (editor) — mirror van airSaveStartSensor() (java.cpp regel
// 414-433), MINUS Juggluco's eigen multi-sensor-lijst/backup-side-effects
// (resendResetDevices etc. — Juggluco-app-architectuur, niet relevant voor
// een enkele actieve sensor in FCLGlucoLink).
JNIEXPORT void JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeSaveStartSensor(
    JNIEnv *, jclass, jlong handle, jfloat eapp, jfloat vref, jint elapsedSecs) {
    auto *state = reinterpret_cast<CareSensAirState *>(handle);
    const time_t nowSec = time(nullptr);
    const uint32_t sensorStart = static_cast<uint32_t>(nowSec) - static_cast<uint32_t>(elapsedSecs);
    state->sensorInfo.sensor_start_time = sensorStart;
    state->sensorInfo.eapp = eapp;
    state->sensorInfo.vref = vref;
    LOGI("nativeSaveStartSensor: eapp=%f vref=%f elapsedSecs=%d start=%u", eapp, vref, elapsedSecs,
         sensorStart);
}

// Resultaat-layout (jlongArray, lengte 6) — zie kdoc bij
// CareSensAirNative.kt voor de Kotlin-kant van dit contract:
//   [0] frameType: 0=genegeerd/fout, 1=recordCountAangekondigd,
//       2=glucoseVerwerkt, 3=sensorFoutGemeld
//   [1] frameType==1: aantal nieuwe records; frameType==2: 1 als er een
//       bruikbare nieuwe waarde is, anders 0; frameType==3: de sensor-eigen
//       deviceErrorCode (RONDE 181)
//   [2] frameType==1: seconden zonder echte meting (RONDE 181, ALTIJD
//       meegegeven, 0 als nog geen ankerpunt); frameType==2 met [1]==1:
//       mg/dL × 10; frameType==2 met [1]==0: 1 als dit record net
//       overgeslagen is omdat het de vorige keer crashte, anders 0 (RONDE
//       181/180)
//   [3] frameType==1: huidig askEarlier-niveau (RONDE 181); frameType==2 met
//       [1]==1: meettijd, epoch-seconden
//   [4] trendrate × 1000, of Long.MIN_VALUE als NaN/onbekend (alleen geldig
//       bij frameType==2 met [1]==1)
//   [5] seq_number_final (idem)
JNIEXPORT jlongArray JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeProcessGlucoseData(
    JNIEnv *env, jclass, jlong handle, jbyteArray value, jlong nowMs) {
    jlong resultBuf[6] = {0, 0, 0, 0, 0, 0};
    auto emit = [&]() {
        jlongArray out = env->NewLongArray(6);
        if (out) env->SetLongArrayRegion(out, 0, 6, resultBuf);
        return out;
    };

    auto *state = reinterpret_cast<CareSensAirState *>(handle);
    if (!value) return emit();
    const jsize arlen = env->GetArrayLength(value);
    if (arlen < 4) {
        LOGE("nativeProcessGlucoseData: size %d < 4", arlen);
        return emit();
    }
    jbyte *bytes = env->GetByteArrayElements(value, nullptr);
    if (!bytes) return emit();
    const auto *air = reinterpret_cast<const AirData *>(bytes);
    const uint32_t nowSec = static_cast<uint32_t>(nowMs / 1000LL);

    if (air->reg1 != 1) {
        LOGE("nativeProcessGlucoseData: reg1=%d != 1", air->reg1);
        env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
        return emit();
    }

    // 13/09/2026 (editor, RONDE 178) — zie kdoc bij firstGlucoseFrameEpochSec
    // (CareSensAirState): dit is het EERSTE moment dat we weten "dit is
    // sensordata, geen ruis" (reg1==1-check hierboven is al gepasseerd) —
    // ongeacht of het een 0xC4-aankondiging of 0xC5-meetdata is, en
    // ongeacht of er straks een bruikbare meting uitkomt. Alleen de EERSTE
    // keer gezet (blijft daarna ongewijzigd voor de rest van deze sessie).
    if (state->firstGlucoseFrameEpochSec == 0) {
        state->firstGlucoseFrameEpochSec = nowSec;
    }

    if (air->reg0 == 0xC4) {
        // Aankondiging: "er staan N nieuwe records klaar" — Kotlin moet nu
        // het numberRecords-commando (197,1) sturen, zie AirGattCallback.java.
        resultBuf[0] = 1;
        resultBuf[1] = air->numRecords;
        // 11/09/2026 (editor, RONDE 175) — mirror van Juggluco's
        // airProcessData()'s 0xC4/newrecords==0-tak: als de sensor "niets
        // nieuws" blijft aankondigen terwijl het al (ruim) langer dan één
        // meetcyclus stil is sinds de laatst bruikbare meting, is dat het
        // signaal dat de aanvraagpositie vastgelopen is (de sensor blijft
        // anders gewoon hetzelfde, al eerder verwerkte record teruggeven op
        // het volgende 196,1-verzoek — exact de eerste caselog hierboven:
        // BLE-handshake en aankondigingen bleven "gewoon werken", de
        // teruggegeven data bevroor). `62*5`s (≈5,2 minuten) is Juggluco's
        // eigen drempel. `lastRealReadingEpochSec==0` (nog nooit een
        // bruikbare meting in deze sensor-sessie) telt bewust niet mee —
        // dan is er nog niets om op terug te vallen.
        //
        // 13/09/2026 (editor, RONDE 176 — na de TWEEDE caselog van diezelfde gebruiker, sensor
        // CSAir 1372) — de voorwaarde `!air->numRecords` hierboven bleek te
        // smal: in dat logbestand meldde de sensor bij ELKE aankondiging een
        // POSITIEF, OPLOPEND aantal (39 -> 53 over ruim een uur, dus zeker
        // niet "0 nieuwe"), terwijl de daadwerkelijk teruggegeven
        // meetdata-bytes (het latere 197,1-antwoord) al die tijd byte-voor-
        // byte identiek bleven — exact hetzelfde bevriezingspatroon, alleen
        // via een pad dat Juggluco's eigen "newrecords==0"-voorwaarde niet
        // dekt. Juggluco's heuristiek gaat er kennelijk van uit dat een
        // vastgelopen aanvraagpositie zich altijd als "0 nieuwe" aandient;
        // Dat tweede geval laat zien dat de sensor evengoed een groeiend
        // aantal kan blijven melden terwijl de opgehaalde data toch bevroren
        // blijft. Daarom hier verbreed: kijk simpelweg naar hoe lang het al
        // geleden is dat er een ECHTE, geaccepteerde meting binnenkwam,
        // ONGEACHT wat air->numRecords op dit moment beweert — als dat te
        // lang duurt, is de aanvraagpositie kennelijk toch vastgelopen. De
        // `!air->numRecords`-voorwaarde is dus vervallen; de rest van de
        // heuristiek (drempel, aftellen bij een geaccepteerde meting) is
        // ongewijzigd.
        //
        // 13/09/2026 (editor, RONDE 178) — `lastRealReadingEpochSec` blijft
        // op 0 staan zolang deze sensor-sessie nog NOOIT een meting heeft
        // geaccepteerd (zie firstGlucoseFrameEpochSec's kdoc bij
        // CareSensAirState) — in dat geval kon deze tak tot nu toe nooit
        // afgaan, hoe lang de aanvraagpositie ook al vastzat. `stallAnchor`
        // hieronder valt in dat geval terug op het moment van het EERSTE
        // ontvangen data-frame in deze sessie, zodat de heuristiek ook een
        // sessie kan herstellen die vanaf het allereerste frame al vastzit.
        const uint32_t stallAnchor = state->lastRealReadingEpochSec != 0
                                          ? state->lastRealReadingEpochSec
                                          : state->firstGlucoseFrameEpochSec;
        // 24/09/2026 (editor, RONDE 181, op verzoek om dit soort informatie
        // — tot nu toe alleen als DIAGI-logregel te vinden — ook rechtstreeks
        // in de app zelf te tonen, zodat een gebruiker niet hoeft te gissen
        // "is het de app of de sensor?") — [resultBuf][2]/[3] geven de
        // wachttijd-in-seconden en het huidige askEarlier-niveau ALTIJD mee
        // (ook onder de drempel, 0 als er nog geen ankerpunt is) — de
        // Kotlin/UI-kant beslist zelf vanaf wanneer dat de moeite van het
        // tonen waard is, dit blijft hier puur data doorgeven.
        const uint32_t waiting = stallAnchor != 0 ? nowSec - stallAnchor : 0;
        resultBuf[2] = static_cast<jlong>(waiting);
        resultBuf[3] = static_cast<jlong>(state->askEarlier);
        if (stallAnchor != 0) {
            constexpr uint32_t kStuckThresholdSecs = 62 * 5;
            if (waiting > kStuckThresholdSecs) {
                ++state->askEarlier;
                resultBuf[3] = static_cast<jlong>(state->askEarlier);
                DIAGI("nativeProcessGlucoseData: newRecords=%d, %us zonder meting (anker=%s) "
                      "-> askEarlier=%d",
                      air->numRecords, waiting,
                      state->lastRealReadingEpochSec != 0 ? "laatste_meting" : "eerste_frame",
                      state->askEarlier);
            }
        }
        env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
        return emit();
    }
    if (air->reg0 != 0xC5) {
        LOGE("nativeProcessGlucoseData: reg0=%d, verwacht 0xC4 of 0xC5", air->reg0);
        env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
        return emit();
    }
    if (static_cast<size_t>(arlen) < sizeof(AirData)) {
        LOGE("nativeProcessGlucoseData: size %d < AirData %zu", arlen, sizeof(AirData));
        env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
        return emit();
    }

    if (air->deviceErrorCode) {
        DIAGE("nativeProcessGlucoseData: sensor meldt deviceErrorCode=%d", air->deviceErrorCode);
        resultBuf[0] = 3;
        // 24/09/2026 (editor, RONDE 181) — de ruwe foutcode zelf gaat mee
        // ([resultBuf][1]), voor het logbestand/eventuele latere technische
        // weergave — de UI-kant vertaalt dit zelf naar gewone-mensen-taal
        // (zie CareSensAirDiagnostics.kt's kdoc), toont NOOIT dit kale getal
        // rechtstreeks aan de gebruiker.
        resultBuf[1] = static_cast<jlong>(air->deviceErrorCode);
        state->lastAir = static_cast<int>(air->sequenceNumber);
        env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
        return emit();
    }

    if (!g_air1_opcal4_algorithm) {
        LOGE("nativeProcessGlucoseData: kalibratiebibliotheek niet geladen — "
             "nativeLoadCalculationLibrary() eerst aanroepen");
        env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
        return emit();
    }

    // 01/08/2026 (editor) — exacte overname van airProcessData's tijd-
    // sanity-check: het toestel stuurt soms een relatieve i.p.v. absolute
    // tijd voor historische (backfill-)records; 31532400s ≈ 1 jaar is
    // Juggluco's eigen grens om dat te herkennen.
    // 01/08/2026 (editor) — BEKENDE VEREENVOUDIGING t.o.v. Juggluco: bij een
    // batch historische (backfill-)records schat Juggluco de tijd van elk
    // record binnen de batch (`nowsec - (tmptot-tmpiter)*300`, dus 5 minuten
    // uit elkaar terugrekenend vanaf nu) — hier hebben we die batch-telling
    // niet bijgehouden (bewust, zie kdoc bovenaan: geen Juggluco-eigen
    // boekhouding overgenomen), dus ELK backfill-record zonder geldige eigen
    // tijd krijgt hier gewoon "nu" als tijdstip. Raakt alleen HISTORISCHE
    // punten na een periode van niet-verbonden zijn (numberRecords>1) — een
    // LIVE meting heeft altijd al een geldige absolute tijd (>31532400) en
    // is hier niet door geraakt. Zichtbaar gevolg als dit ooit optreedt: een
    // stapel teruggehaalde punten die allemaal op hetzelfde tijdstip lijken
    // te vallen i.p.v. netjes 5 minuten uit elkaar. Op te lossen als eerste
    // live test dit laat zien; voor de live/actuele waarde (waar het nu om
    // gaat) maakt dit niets uit.
    uint32_t mtime = air->time;
    if (mtime < 31532400) {
        mtime = nowSec;
    }
    const auto idNow = static_cast<uint16_t>(air->sequenceNumber);

    // 13/09/2026 (editor, RONDE 179, bijgewerkt 24/09/2026 RONDE 180 — zie
    // kdoc bij g_pendingFramePath/PendingFrameFingerprint voor het volledige
    // waarom van de wijziging) — dit record heeft dezelfde vingerafdruk
    // (sequenceNumber/time/temperature/glucose_array) als het record
    // waarvoor we de vorige keer de rekenbibliotheek aanriepen EN daarna
    // niet meer "veilig terug" hebben laten weten (dus: toen gecrasht).
    // Nogmaals aanbieden aan dezelfde bibliotheek zou vrijwel zeker opnieuw
    // crashen — dus deze keer NIET aanroepen, gewoon overslaan en verder.
    PendingFrameFingerprint currentFingerprint{};
    currentFingerprint.sequenceNumber = air->sequenceNumber;
    currentFingerprint.time = air->time;
    currentFingerprint.temperature = air->temperature;
    currentFingerprint.glucose_array = air->glucose_array;
    const auto *fingerprintBytes = reinterpret_cast<const uint8_t *>(&currentFingerprint);
    uint8_t previousPendingFrame[sizeof(PendingFrameFingerprint)];
    if (readPendingFrame(previousPendingFrame, sizeof(previousPendingFrame)) &&
        memcmp(previousPendingFrame, fingerprintBytes, sizeof(PendingFrameFingerprint)) == 0) {
        DIAGE("nativeProcessGlucoseData: seq=%d overgeslagen — dit exacte record "
              "veroorzaakte een eerdere crash, niet opnieuw aan de rekenbibliotheek "
              "aangeboden",
              idNow);
        clearPendingFrame();
        state->lastAir = idNow;
        env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);
        resultBuf[0] = 2; // frameType: behandeld, maar bewust zonder bruikbare waarde
        // 24/09/2026 (editor, RONDE 181) — [resultBuf][2]=1 markeert specifiek
        // DIT geval (net herstelt van een crash op dit record) t.o.v. een
        // gewoon historisch vulrecord zonder bruikbare waarde ([2]=0,
        // ongewijzigd default) — zie CareSensAirDiagnostics.kt voor de
        // gewone-mensen-taal-melding die de UI hiervan maakt.
        resultBuf[2] = 1;
        return emit();
    }
    // Nog niet eerder geprobeerd (of de vorige poging kwam wél veilig
    // terug) — vastleggen VLAK VOOR de aanroep, zodat een crash TIJDENS
    // deze aanroep straks herkenbaar is.
    writePendingFrame(fingerprintBytes, sizeof(PendingFrameFingerprint));

    air_input input{};
    input.data.sequence_number = idNow;
    input.data.measurement_time = mtime;
    input.data.glucose_array = air->glucose_array;
    input.data.temperature = static_cast<double>(air->temperature) / 100.0;

    air1_opcal4_output_t output{};
    air1_opcal4_debug_t debug{};

    const unsigned char algoRes = g_air1_opcal4_algorithm(
        &state->sensorInfo, &input.cgm_input, &input.empty, &state->generated, &output, &debug);

    // 13/09/2026 (editor, RONDE 179) — de aanroep hierboven is veilig
    // teruggekomen (geen crash), dus dit record hoeft niet langer als
    // "onbevestigd" onthouden te worden.
    clearPendingFrame();

    env->ReleaseByteArrayElements(value, bytes, JNI_ABORT);

    resultBuf[0] = 2; // frameType: glucose-frame verwerkt (ook als er geen bruikbare waarde uitkomt)
    state->lastAir = idNow;

    if (algoRes && !output.errcode) {
        const double mgdLdouble = output.result_glucose;
        if (mgdLdouble > 35.0 && mgdLdouble < 505.0) {
            double trendrate = output.trendrate;
            if (trendrate > 99.0) trendrate = NAN;
            resultBuf[1] = 1;
            resultBuf[2] = static_cast<jlong>(std::llround(mgdLdouble * 10.0));
            // 05/08/2026 (editor, RONDE 40 — op verzoek, na de gebruiker's
            // eigen observatie van een langzaam oplopende vertraging in
            // xDrip+/AAPS ná ronde 39's reconnect-fix, en zijn vermoeden
            // dat Juggluco de telefoonklok gebruikt) — `measurement_time_
            // standard` komt uiteindelijk van de sensor's EIGEN klok
            // (`air->time`/`mtime` hierboven gaat als `input.data.
            // measurement_time` de kalibratiebibliotheek in), niet van onze
            // telefoon. Een goedkope BLE-transmitter-kristal loopt typisch
            // een paar seconden per uur weg; over meerdere uren telt dat op
            // tot minuten vertraging — precies wat de gebruiker zag, terwijl
            // het onderliggende reconnect-ritme zelf (bevestigd met
            // `fclglucolink_2026-08-05.txt`: 3,5 uur lang elke cyclus
            // meteen raak, binnen 0,3s van exact 5 minuten) inmiddels
            // vlekkeloos is.
            //
            // Correctie: `nowSec` (onze telefoonklok, altijd correct) is
            // hierboven al berekend. Als dit record er "vers" uitziet (zijn
            // eigen tijd ligt binnen kFreshRecordThresholdSecs van nu — geldt
            // sinds ronde 39 vrijwel elke cyclus, aangezien we nu bijna nooit
            // meer een terugval-/inhaalronde nodig hebben) wordt het verschil
            // opnieuw vastgesteld in `state->clockOffsetSecs`. Elk record —
            // ook een eventueel ouder terugval-/inhaalrecord in dezelfde
            // batch, dat zelf ver in het verleden kan liggen — krijgt
            // vervolgens dezelfde correctie: dat behoudt de onderlinge
            // 5-minuten-afstand tussen historische records terwijl het
            // geheel weer bij de echte tijd aansluit. Zelfcorrigerend per
            // verbinding, geen enkele opgebouwde afwijking kan blijven
            // hangen zolang er af en toe weer een vers record langskomt.
            constexpr int64_t kFreshRecordThresholdSecs = 600; // 10 minuten
            const auto rawMeasurementTime = static_cast<int64_t>(output.measurement_time_standard);
            const int64_t candidateOffset = static_cast<int64_t>(nowSec) - rawMeasurementTime;
            const int64_t candidateOffsetAbs = candidateOffset < 0 ? -candidateOffset : candidateOffset;
            if (candidateOffsetAbs < kFreshRecordThresholdSecs) {
                state->clockOffsetSecs = candidateOffset;
            }
            resultBuf[3] = static_cast<jlong>(rawMeasurementTime + state->clockOffsetSecs);
            resultBuf[4] = std::isnan(trendrate)
                                ? std::numeric_limits<jlong>::min()
                                : static_cast<jlong>(std::llround(trendrate * 1000.0));
            resultBuf[5] = output.seq_number_final;
            state->lastAir = output.seq_number_final;
            // 11/09/2026 (editor, RONDE 175) — mirror van Juggluco's
            // `if(sens->getinfo()->askEarlier) --sens->getinfo()->askEarlier;`
            // in het succespad van airProcessData: elke bruikbare, verse
            // meting bouwt een eerder opgebouwde askEarlier-terugval weer
            // een stap af, en het moment zelf wordt vastgelegd zodat de
            // 0xC4/newRecords==0-tak hierboven weet hoe lang het al stil is.
            state->lastRealReadingEpochSec = nowSec;
            if (state->askEarlier > 0) --state->askEarlier;
            DIAGI("nativeProcessGlucoseData: mgdL=%.1f trendrate=%.3f seq=%d askEarlier=%d", mgdLdouble,
                  trendrate, output.seq_number_final, state->askEarlier);
            return emit();
        }
        DIAGI("nativeProcessGlucoseData: resultaat %.1f mg/dL buiten plausibel bereik (35-505), "
              "genegeerd (seq=%d)",
              mgdLdouble, idNow);
    } else {
        DIAGE("nativeProcessGlucoseData: algoritme res=%d errcode=%d (seq=%d)", algoRes, output.errcode,
              idNow);
    }
    resultBuf[1] = 0;
    return emit();
}

} // extern "C"
