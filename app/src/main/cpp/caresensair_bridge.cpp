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
// geen `askEarlier`-hertry-heuristiek, geen mkres()-dedupe-logica tegen
// Juggluco's EIGEN geschiedenis-database) — dat is allemaal Juggluco-eigen
// boekhouding die niets met de rekenstap zelf te maken heeft. In plaats
// daarvan: FCLGlucoLink's eigen, eenvoudiger status-export/import
// (`nativeExportState`/`nativeImportState` hieronder), zodat Kotlin de
// ruwe struct-bytes gewoon als één blob naar een bestand kan schrijven —
// functioneel hetzelfde doel als Juggluco's mmap-bestanden (kalibratie-
// geschiedenis overleeft een herstart van de app), alleen simpeler.
//
// `air.hpp` en `caresens_wire.hpp` zijn letterlijke kopieën (zie hun eigen
// kdoc) — dit bestand is de enige NIEUWE C++-code, en is bewust dun
// gehouden: de daadwerkelijke rekenstap gebeurt in `libCALCULATION.so`
// (closed-source, uit de gebruiker's eigen geïnstalleerde Juggluco-apk gehaald),
// hier alleen via dlopen/dlsym aangeroepen — exact zoals Juggluco's eigen
// `getlibfuncs()` dat doet, zodat dit onafhankelijk blijft van welke
// NDK/toolchain-versie die bibliotheek ooit gebouwd heeft.
#include <jni.h>
#include <android/log.h>
#include <dlfcn.h>
#include <cstring>
#include <cmath>
#include <ctime>
#include <limits>

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
constexpr size_t kExportSize =
    sizeof(air1_opcal4_device_info_t) + sizeof(air1_opcal4_arguments_t) + sizeof(int) * 2;

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
    // Vaste volgorde: sensorInfo, generated, ininfo, lastAir — zelfde
    // volgorde als de struct-velden, zie kExportSize.
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
    env->ReleaseByteArrayElements(blob, bytes, JNI_ABORT);
    return JNI_TRUE;
}

JNIEXPORT jint JNICALL
Java_com_fclglucolink_app_sensor_caresensair_CareSensAirNative_nativeGetLastSequence(
    JNIEnv *, jclass, jlong handle) {
    return reinterpret_cast<CareSensAirState *>(handle)->lastAir;
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
//       bruikbare nieuwe waarde is, anders 0
//   [2] mg/dL × 10 (alleen geldig als [1]==1 bij frameType==2)
//   [3] meettijd, epoch-seconden (idem)
//   [4] trendrate × 1000, of Long.MIN_VALUE als NaN/onbekend (idem)
//   [5] seq_number_final
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

    if (air->reg0 == 0xC4) {
        // Aankondiging: "er staan N nieuwe records klaar" — Kotlin moet nu
        // het numberRecords-commando (197,1) sturen, zie AirGattCallback.java.
        resultBuf[0] = 1;
        resultBuf[1] = air->numRecords;
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
        LOGE("nativeProcessGlucoseData: sensor meldt deviceErrorCode=%d", air->deviceErrorCode);
        resultBuf[0] = 3;
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

    air_input input{};
    input.data.sequence_number = idNow;
    input.data.measurement_time = mtime;
    input.data.glucose_array = air->glucose_array;
    input.data.temperature = static_cast<double>(air->temperature) / 100.0;

    air1_opcal4_output_t output{};
    air1_opcal4_debug_t debug{};

    const unsigned char algoRes = g_air1_opcal4_algorithm(
        &state->sensorInfo, &input.cgm_input, &input.empty, &state->generated, &output, &debug);

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
            LOGI("nativeProcessGlucoseData: mgdL=%.1f trendrate=%.3f seq=%d", mgdLdouble, trendrate,
                 output.seq_number_final);
            return emit();
        }
        LOGI("nativeProcessGlucoseData: resultaat %.1f mg/dL buiten plausibel bereik (35-505), genegeerd",
             mgdLdouble);
    } else {
        LOGE("nativeProcessGlucoseData: algoritme res=%d errcode=%d", algoRes, output.errcode);
    }
    resultBuf[1] = 0;
    return emit();
}

} // extern "C"
